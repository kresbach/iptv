@preconcurrency import AVFoundation
import AVKit
import Foundation

/// Welcher Player abspielen soll (Einstellung).
enum PlayerEngine: String, CaseIterable, Identifiable {
    case automatic
    case apple
    case vlc

    /// Getrennte Einstellungen für Live TV sowie Filme und Serien.
    static let liveStorageKey = "playerEngineLive"
    static let vodStorageKey = "playerEngineVOD"
    static let liveDefault: PlayerEngine = .apple
    static let vodDefault: PlayerEngine = .vlc

    var id: String { rawValue }

    var title: String {
        switch self {
        case .automatic: "Automatisch"
        case .apple: "Apple (AVPlayer)"
        case .vlc: "VLC"
        }
    }

    /// `true`, wenn das Paket „vlckit-spm“ im Projekt eingebunden ist.
    static var isVLCAvailable: Bool {
        #if canImport(VLCKitSPM) || canImport(MobileVLCKit)
        true
        #else
        false
        #endif
    }

    /// Formate, die AVPlayer nicht oder nur unzuverlässig abspielt.
    private static let vlcExtensions: Set<String> = [
        "ts", "mkv", "avi", "flv", "wmv", "mpg", "mpeg", "webm", "m2ts", "mts", "divx", "rmvb", "3gp",
    ]
    private static let vlcSchemes: Set<String> = ["rtmp", "rtsp", "udp", "rtp", "mms"]

    /// Reihenfolge der Versuche: Scheitert einer, wird automatisch der nächste probiert.
    func candidates(for channel: Channel) -> [PlaybackCandidate] {
        let url = channel.url
        guard Self.isVLCAvailable else { return [.init(player: .apple, url: url)] }

        switch self {
        case .apple:
            return [.init(player: .apple, url: Self.appleCompatibleURL(url, kind: channel.kind))]
        case .vlc:
            return [.init(player: .vlc, url: url)]
        case .automatic:
            if Self.needsVLC(url) {
                // Formate, die nur VLC kann.
                return [.init(player: .vlc, url: url)]
            }
            if channel.kind != .live {
                // Filme/Episoden: VLC nutzt eine einzige Verbindung. Der Apple-Player lädt
                // Dateien in mehreren parallelen Verbindungen – das lehnen IPTV-Konten mit
                // nur einer erlaubten Verbindung ab („Öffnen fehlgeschlagen“).
                return [.init(player: .vlc, url: url), .init(player: .apple, url: url)]
            }
            return [.init(player: .apple, url: url), .init(player: .vlc, url: url)]
        }
    }

    /// Der Apple-Player kann kein reines MPEG-TS. Xtream-Live-Links werden daher
    /// von `.ts` auf `.m3u8` (HLS) umgestellt, das der Server ebenfalls anbietet.
    private static func appleCompatibleURL(_ url: URL, kind: ContentKind) -> URL {
        guard kind == .live, url.path().contains("/live/"), url.pathExtension.lowercased() == "ts" else { return url }
        return url.deletingPathExtension().appendingPathExtension("m3u8")
    }

    private static func needsVLC(_ url: URL) -> Bool {
        vlcExtensions.contains(url.pathExtension.lowercased())
            || vlcSchemes.contains(url.scheme?.lowercased() ?? "")
    }

}

enum ResolvedPlayer: Equatable {
    case apple
    case vlc
}

struct PlaybackCandidate: Equatable {
    let player: ResolvedPlayer
    let url: URL

    var playerName: String { player == .vlc ? "VLC" : "Apple-Player" }

    @MainActor
    func makeEngine() -> any PlaybackEngine {
        switch player {
        case .apple:
            return ApplePlaybackEngine()
        case .vlc:
            #if canImport(VLCKitSPM) || canImport(MobileVLCKit)
            return VLCPlaybackEngine()
            #else
            return ApplePlaybackEngine()
            #endif
        }
    }
}

/// Eine Untertitel- oder Tonspur.
struct MediaTrack: Identifiable, Hashable, Sendable {
    let id: Int
    let name: String
}

/// Gemeinsame Schnittstelle für Apple-Player und VLC, damit die Oberfläche
/// (Steuerung, Zeitleiste, Fortschritt) für beide gleich ist.
@MainActor
protocol PlaybackEngine: AnyObject {
    var isPlaying: Bool { get }
    var isBuffering: Bool { get }
    /// Aktuelle Position in Sekunden.
    var time: Double { get }
    /// Gesamtlänge in Sekunden, 0 bei Live-Streams oder unbekannt.
    var duration: Double { get }
    var failureMessage: String? { get }
    /// `true`, sobald tatsächlich Bild/Ton läuft (für den Start-Watchdog).
    var hasStartedPlayback: Bool { get }

    /// Verfügbare Untertitel (ohne „Aus“) und die gewählte Spur (`nil` = aus).
    var subtitleTracks: [MediaTrack] { get }
    var selectedSubtitle: Int? { get }
    var audioTracks: [MediaTrack] { get }
    var selectedAudio: Int? { get }

    func selectSubtitle(_ id: Int?)
    func selectAudio(_ id: Int)

    func start(url: URL, isLive: Bool, resumeAt: Double?)
    /// Wird regelmäßig aufgerufen und aktualisiert den Zustand.
    func update()
    func togglePlayPause()
    func seek(to seconds: Double)
    func stop()
}

extension PlaybackEngine {
    func skip(by seconds: Double) {
        guard duration > 0 else { return }
        seek(to: min(max(time + seconds, 0), duration - 1))
    }
}

// MARK: - Apple-Player

/// AVPlayer mit eigener Oberfläche, Bild-in-Bild über `AVPictureInPictureController`.
@MainActor
@Observable
final class ApplePlaybackEngine: PlaybackEngine {
    let player = AVPlayer()

    private(set) var isPlaying = false
    private(set) var isBuffering = true
    private(set) var time: Double = 0
    private(set) var duration: Double = 0
    private(set) var failureMessage: String?
    private(set) var hasStartedPlayback = false
    private(set) var isPictureInPictureActive = false
    private(set) var subtitleTracks: [MediaTrack] = []
    private(set) var selectedSubtitle: Int?
    private(set) var audioTracks: [MediaTrack] = []
    private(set) var selectedAudio: Int?

    @ObservationIgnored private var legibleGroup: AVMediaSelectionGroup?
    @ObservationIgnored private var audibleGroup: AVMediaSelectionGroup?
    @ObservationIgnored private var didLoadTracks = false

    let supportsPictureInPicture = AVPictureInPictureController.isPictureInPictureSupported()

    @ObservationIgnored private var pipController: AVPictureInPictureController?
    @ObservationIgnored private var pendingSeek: Double?
    @ObservationIgnored private var isLive = false
    @ObservationIgnored private var pausedByUser = false

    func attach(layer: AVPlayerLayer) {
        guard supportsPictureInPicture, pipController == nil else { return }
        let controller = AVPictureInPictureController(playerLayer: layer)
        controller?.canStartPictureInPictureAutomaticallyFromInline = true
        pipController = controller
    }

    func togglePictureInPicture() {
        guard let pipController else { return }
        if pipController.isPictureInPictureActive {
            pipController.stopPictureInPicture()
        } else {
            pipController.startPictureInPicture()
        }
    }

    func start(url: URL, isLive: Bool, resumeAt: Double?) {
        self.isLive = isLive
        try? AVAudioSession.sharedInstance().setActive(true)
        let item = AVPlayerItem(url: url)
        // Mit der ersten Qualitätsstufe beginnen statt erst die beste auszuhandeln.
        item.startsOnFirstEligibleVariant = true
        if isLive {
            // Kleiner Vorpuffer: Live-Sender laufen schneller an.
            item.preferredForwardBufferDuration = 4
        }
        pendingSeek = resumeAt
        pausedByUser = false
        player.replaceCurrentItem(with: item)
        player.play()
    }

    func update() {
        guard let item = player.currentItem else { return }
        if item.status == .failed {
            set(\.failureMessage, item.error?.localizedDescription ?? "Das Format wird möglicherweise nicht unterstützt.")
            return
        }

        let status = player.timeControlStatus
        let current = player.currentTime().seconds
        let total = item.duration.seconds

        set(\.isPlaying, status != .paused)
        set(\.isBuffering, item.status == .unknown || status == .waitingToPlayAtSpecifiedRate
            || (isLive && status == .playing && item.isPlaybackBufferEmpty))
        set(\.time, current.isFinite ? current.rounded() : 0)
        set(\.duration, total.isFinite ? total.rounded() : 0)
        set(\.isPictureInPictureActive, pipController?.isPictureInPictureActive ?? false)
        if !hasStartedPlayback, status == .playing { hasStartedPlayback = true }

        if item.status == .readyToPlay, let target = pendingSeek {
            pendingSeek = nil
            seek(to: target)
        }
        if item.status == .readyToPlay, !didLoadTracks {
            didLoadTracks = true
            Task { await loadTracks(of: item) }
        }

    }

    private func loadTracks(of item: AVPlayerItem) async {
        let asset = item.asset
        let legible = try? await asset.loadMediaSelectionGroup(for: .legible)
        let audible = try? await asset.loadMediaSelectionGroup(for: .audible)
        guard player.currentItem === item else { return }

        legibleGroup = legible
        audibleGroup = audible
        subtitleTracks = Self.tracks(in: legible)
        audioTracks = Self.tracks(in: audible)
        selectedSubtitle = Self.selectedIndex(in: legible, of: item)
        selectedAudio = Self.selectedIndex(in: audible, of: item)
    }

    private static func tracks(in group: AVMediaSelectionGroup?) -> [MediaTrack] {
        guard let group else { return [] }
        return group.options.enumerated().map { MediaTrack(id: $0.offset, name: $0.element.displayName) }
    }

    private static func selectedIndex(in group: AVMediaSelectionGroup?, of item: AVPlayerItem) -> Int? {
        guard let group, let option = item.currentMediaSelection.selectedMediaOption(in: group) else { return nil }
        return group.options.firstIndex(of: option)
    }

    func selectSubtitle(_ id: Int?) {
        guard let item = player.currentItem, let group = legibleGroup else { return }
        let option = id.flatMap { group.options.indices.contains($0) ? group.options[$0] : nil }
        item.select(option, in: group)
        selectedSubtitle = option == nil ? nil : id
    }

    func selectAudio(_ id: Int) {
        guard let item = player.currentItem, let group = audibleGroup, group.options.indices.contains(id) else { return }
        item.select(group.options[id], in: group)
        selectedAudio = id
    }

    func togglePlayPause() {
        if player.timeControlStatus == .paused {
            pausedByUser = false
            player.play()
        } else {
            pausedByUser = true
            player.pause()
        }
        isPlaying.toggle()
    }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        time = seconds
    }

    func stop() {
        if pipController?.isPictureInPictureActive == true { pipController?.stopPictureInPicture() }
        pausedByUser = true
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    /// Setzt nur bei Änderung, damit SwiftUI nicht unnötig neu zeichnet.
    private func set<Value: Equatable>(_ keyPath: ReferenceWritableKeyPath<ApplePlaybackEngine, Value>, _ value: Value) {
        if self[keyPath: keyPath] != value { self[keyPath: keyPath] = value }
    }
}
