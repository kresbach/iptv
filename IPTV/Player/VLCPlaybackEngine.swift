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

    /// VLC soll erst starten, wenn die Videofläche existiert.
    func attach(_ view: UIView) {
        player.drawable = view
        hasDrawable = true
        if wantsPlayback { player.play() }
    }

    func start(url: URL, isLive: Bool, resumeAt: Double?) {
        let media = VLCMedia(url: url)
        media.addOptions([
            // Live: kurzer Puffer für schnellen Start, VOD: größerer Puffer gegen Ruckler.
            "network-caching": isLive ? 1500 : 3000,
            "http-user-agent": HTTPClient.userAgent,
        ])
        player.media = media
        pendingSeek = resumeAt
        wantsPlayback = true
        if hasDrawable { player.play() }
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
        let playing = player.isPlaying
        let current = Double(player.time.intValue) / 1000

        set(\.isPlaying, playing)
        set(\.isBuffering, state == .opening || (state == .buffering && !playing))
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

/// Fläche, in die VLC das Video zeichnet.
struct VLCVideoSurface: UIViewRepresentable {
    let engine: VLCPlaybackEngine

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        // Berührungen gehen an die SwiftUI-Steuerung darüber.
        view.isUserInteractionEnabled = false
        engine.attach(view)
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {}
}
#endif
