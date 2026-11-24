#if canImport(VLCKitSPM) || canImport(MobileVLCKit)
import SwiftUI
#if canImport(VLCKitSPM)
@preconcurrency import VLCKitSPM
#else
@preconcurrency import MobileVLCKit
#endif

/// VLC für Formate, die AVPlayer nicht kann (.ts, .mkv, rtmp …).
/// Wird nur gebaut, wenn das Paket „vlckit-spm“ im Projekt eingebunden ist.
@MainActor
@Observable
final class VLCPlaybackEngine: PlaybackEngine {
    let player = VLCMediaPlayer()

    private(set) var isPlaying = false
    private(set) var isBuffering = true
    private(set) var time: Double = 0
    private(set) var duration: Double = 0
    private(set) var failureMessage: String?
    private(set) var hasStartedPlayback = false
    private(set) var hasEnded = false
    private(set) var subtitleTracks: [MediaTrack] = []
    private(set) var selectedSubtitle: Int?
    private(set) var audioTracks: [MediaTrack] = []
    private(set) var selectedAudio: Int?

    @ObservationIgnored private var lastTrackRefresh = ContinuousClock.now - .seconds(60)

    @ObservationIgnored private var pendingSeek: Double?
    @ObservationIgnored private var hasDrawable = false
    @ObservationIgnored private var wantsPlayback = false
    @ObservationIgnored private var sawActivity = false
    @ObservationIgnored private var isStopped = false
    @ObservationIgnored private var appliedFit: VideoFit?
    @ObservationIgnored private var appliedFitSize: CGSize = .zero

    /// VLC soll erst starten, wenn die Videofläche existiert.
    func attach(_ view: UIView) {
        player.drawable = view
        hasDrawable = true
        if wantsPlayback { player.play() }
    }

    func start(url: URL, isLive: Bool, resumeAt: Double?) {
        let media = VLCMedia(url: url)
        var options: [AnyHashable: Any] = [
            // Live: eingestellter Puffer (Schnell/Normal/Groß), VOD: größerer Puffer gegen Ruckler.
            "network-caching": isLive ? LiveBuffer.current.vlcCaching : 3000,
            "http-user-agent": HTTPClient.userAgent,
            // Ungenaue Zeitstempel vieler IPTV-Streams nicht „korrigieren“ – das verursacht Ruckler.
            "clock-jitter": 0,
            "clock-synchro": 0,
        ]
        // Halbbilder (1080i) in Vollbilder umwandeln, sonst wirken Bewegungen zackig.
        options.merge(DeinterlaceMode.current.vlcOptions) { _, new in new }
        media.addOptions(options)
        player.media = media
        pendingSeek = resumeAt
        wantsPlayback = true
        if hasDrawable { player.play() }
    }

    /// Bildformat anwenden. VLC zeigt von sich aus das ganze Bild im Originalformat;
    /// für „Zoom“ wird auf das Seitenverhältnis der Fläche zugeschnitten, für „Strecken“
    /// das Seitenverhältnis des Videos auf das der Fläche gesetzt.
    func apply(fit: VideoFit, viewSize: CGSize) {
        guard viewSize.width >= 1, viewSize.height >= 1 else { return }
        guard fit != appliedFit || viewSize != appliedFitSize else { return }
        appliedFit = fit
        appliedFitSize = viewSize
        let ratio = "\(Int(viewSize.width.rounded())):\(Int(viewSize.height.rounded()))"
        switch fit {
        case .original:
            setAspectRatio(nil)
            setCropGeometry(nil)
        case .fill:
            setAspectRatio(nil)
            setCropGeometry(ratio)
        case .stretch:
            setCropGeometry(nil)
            setAspectRatio(ratio)
        }
    }

    // libVLC kopiert die Zeichenkette sofort, der Zeiger muss nur während des Aufrufs gültig sein.
    private func setAspectRatio(_ value: String?) {
        guard let value else {
            player.videoAspectRatio = nil
            return
        }
        value.withCString { player.videoAspectRatio = UnsafeMutablePointer(mutating: $0) }
    }

    private func setCropGeometry(_ value: String?) {
        guard let value else {
            player.videoCropGeometry = nil
            return
        }
        value.withCString { player.videoCropGeometry = UnsafeMutablePointer(mutating: $0) }
    }

    func update() {
        let state = player.state
        let activeStates: [VLCMediaPlayerState] = [.opening, .buffering, .playing, .esAdded]
        if activeStates.contains(state) { sawActivity = true }

        // VLC meldet abgelehnte Streams oft als „beendet“ statt als Fehler.
        let endedWithoutPlaying = sawActivity && !hasStartedPlayback && (state == .ended || state == .stopped)
        if state == .error || (wantsPlayback && endedWithoutPlaying) {
            set(\.failureMessage, "Der Stream konnte nicht geöffnet werden. Der Server hat die Verbindung abgelehnt oder der Link ist nicht mehr gültig.")
            return
        }
        if hasStartedPlayback, wantsPlayback, state == .ended || state == .stopped {
            // Film zu Ende oder Live-Stream vom Server getrennt.
            set(\.hasEnded, true)
            set(\.isPlaying, false)
            return
        }
        let playing = player.isPlaying
        let current = Double(player.time.intValue) / 1000

        set(\.isPlaying, playing)
        set(\.isBuffering, state == .opening || (state == .buffering && !playing))
        // Ton-Versatz erst setzen, wenn VLC abspielt – vorher wird er ignoriert.
        if playing, appliedAudioDelay != audioDelay {
            player.currentAudioPlaybackDelay = Int(audioDelay * 1_000_000)  // Mikrosekunden
            appliedAudioDelay = audioDelay
        }
        set(\.time, current.rounded())
        if !hasStartedPlayback, playing { hasStartedPlayback = true }

        let length = Double(player.media?.length.intValue ?? 0) / 1000
        if length > 0 {
            set(\.duration, length.rounded())
        } else if player.position > 0.001, current > 0 {
            // Manche Streams melden keine Länge – dann aus der Position schätzen.
            set(\.duration, (current / Double(player.position)).rounded())
        }

        if hasStartedPlayback { refreshTracksIfNeeded() }

        // Weiterschauen erst, wenn VLC läuft und die Länge kennt.
        if let target = pendingSeek, duration > 0, playing {
            pendingSeek = nil
            seek(to: target)
        }
    }

    // MARK: - Untertitel und Tonspuren

    /// Spuren werden erst während der Wiedergabe bekannt; alle 3 Sekunden abgleichen.
    private func refreshTracksIfNeeded() {
        guard ContinuousClock.now - lastTrackRefresh > .seconds(3) else { return }
        lastTrackRefresh = .now

        set(\.subtitleTracks, Self.tracks(names: player.videoSubTitlesNames, indexes: player.videoSubTitlesIndexes))
        let subtitle = Int(player.currentVideoSubTitleIndex)
        set(\.selectedSubtitle, subtitle >= 0 ? subtitle : nil)

        set(\.audioTracks, Self.tracks(names: player.audioTrackNames, indexes: player.audioTrackIndexes))
        let audio = Int(player.currentAudioTrackIndex)
        set(\.selectedAudio, audio >= 0 ? audio : nil)
    }

    /// VLC liefert Namen und Indizes als getrennte Listen; Index -1 ist „Deaktivieren“.
    private static func tracks(names: [Any]?, indexes: [Any]?) -> [MediaTrack] {
        let names = names ?? []
        let indexes = indexes ?? []
        return zip(names, indexes).compactMap { name, index in
            guard let number = (index as? NSNumber)?.intValue, number >= 0 else { return nil }
            return MediaTrack(id: number, name: "\(name)")
        }
    }

    func selectSubtitle(_ id: Int?) {
        player.currentVideoSubTitleIndex = Int32(id ?? -1)
        selectedSubtitle = id
    }

    func selectAudio(_ id: Int) {
        player.currentAudioTrackIndex = Int32(id)
        selectedAudio = id
    }

    private(set) var volume = 1.0
    private(set) var isUserMuted = false

    func setVolume(_ value: Double) {
        volume = min(max(value, 0), 1)
        isUserMuted = false
        player.audio?.volume = Int32(volume * 100)
    }

    func toggleMute() {
        isUserMuted.toggle()
        player.audio?.volume = isUserMuted ? 0 : Int32(volume * 100)
    }

    var supportsAudioDelay: Bool { true }
    private(set) var audioDelay: Double = 0
    @ObservationIgnored private var appliedAudioDelay: Double = 0

    func setAudioDelay(_ seconds: Double) {
        audioDelay = seconds
    }

    var supportsExternalSubtitles: Bool { true }
    private(set) var subtitleDelay: Double = 0

    /// Blendet eine Untertitel-Datei ein und wählt sie aus.
    func addSubtitle(file: URL) {
        _ = player.addPlaybackSlave(file, type: .subtitle, enforce: true)
        // Spurliste beim nächsten Durchlauf sofort neu einlesen.
        lastTrackRefresh = ContinuousClock.now - .seconds(60)
    }

    func setSubtitleDelay(_ seconds: Double) {
        player.currentVideoSubTitleDelay = Int(seconds * 1_000_000)  // Mikrosekunden
        subtitleDelay = seconds
    }

    /// Setzt nur bei Änderung, damit SwiftUI nicht unnötig neu zeichnet.
    private func set<Value: Equatable>(_ keyPath: ReferenceWritableKeyPath<VLCPlaybackEngine, Value>, _ value: Value) {
        if self[keyPath: keyPath] != value { self[keyPath: keyPath] = value }
    }

    func togglePlayPause() {
        if player.isPlaying { player.pause() } else { player.play() }
        isPlaying.toggle()
    }

    func seek(to seconds: Double) {
        if Double(player.media?.length.intValue ?? 0) > 0 {
            player.time = VLCTime(int: Int32(seconds * 1000))
        } else if duration > 0 {
            player.position = Float(seconds / duration)
        }
        time = seconds
    }

    func stop() {
        guard !isStopped else { return }
        isStopped = true
        wantsPlayback = false
        pendingSeek = nil
        player.stop()
        // VLC stoppt im Hintergrund. Den Player festhalten, bis er wirklich beendet ist
        // und die Netzwerkverbindung geschlossen hat – sonst bleibt bei Konten mit nur
        // einer Verbindung der nächste Stream blockiert.
        VLCPlayerRetirement.retire(player)
    }
}

/// Hält beendete VLC-Player, bis sie vollständig gestoppt sind, und löst dann das
/// Medium, damit Eingabe-Thread und HTTP-Verbindung sicher geschlossen werden.
@MainActor
enum VLCPlayerRetirement {
    private static var retired: [VLCMediaPlayer] = []

    /// `true`, solange ein beendeter Player noch herunterfährt.
    static var isBusy: Bool { !retired.isEmpty }

    static func retire(_ player: VLCMediaPlayer) {
        retired.append(player)
        Task { @MainActor in
            // Bis zu 8 Sekunden auf den Stopp warten.
            for _ in 0..<32 {
                let state = player.state
                if state == .stopped || state == .ended || state == .error { break }
                try? await Task.sleep(for: .milliseconds(250))
            }
            player.media = nil
            retired.removeAll { $0 === player }
        }
    }
}

/// Meldet Größenänderungen (Drehen, Fenstergröße), damit das Bildformat passt.
final class VLCDrawableView: UIView {
    var onLayout: (@MainActor (CGSize) -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        onLayout?(bounds.size)
    }
}

/// Fläche, in die VLC das Video zeichnet.
struct VLCVideoSurface: UIViewRepresentable {
    let engine: VLCPlaybackEngine
    var fit: VideoFit = .original

    func makeUIView(context: Context) -> VLCDrawableView {
        let view = VLCDrawableView()
        view.backgroundColor = .black
        // Berührungen gehen an die SwiftUI-Steuerung darüber.
        view.isUserInteractionEnabled = false
        engine.attach(view)
        return view
    }

    func updateUIView(_ view: VLCDrawableView, context: Context) {
        let engine = engine
        let fit = fit
        view.onLayout = { size in engine.apply(fit: fit, viewSize: size) }
        engine.apply(fit: fit, viewSize: view.bounds.size)
    }
}
#endif
