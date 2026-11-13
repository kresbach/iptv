import AVKit
import Foundation

/// Welcher Player abspielen soll (Einstellung).
enum PlayerEngine: String, CaseIterable, Identifiable {
    case automatic
    case apple
    case vlc

    static let storageKey = "playerEngine"

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
        let vlc = Self.isVLCAvailable
        // Xtream-Live als MPEG-TS startet mit VLC deutlich schneller als HLS.
        let fastLiveURL = channel.kind == .live ? Self.xtreamTSVariant(of: url) : nil

        var result: [PlaybackCandidate] = []
        switch self {
        case .apple:
            result = [.init(player: .apple, url: url)]
        case .vlc:
            result = vlc
                ? [fastLiveURL.map { .init(player: .vlc, url: $0) }, .init(player: .vlc, url: url)].compactMap { $0 }
                : [.init(player: .apple, url: url)]
        case .automatic:
            guard vlc else { return [.init(player: .apple, url: url)] }
            if let fastLiveURL {
                result = [.init(player: .vlc, url: fastLiveURL), .init(player: .apple, url: url), .init(player: .vlc, url: url)]
            } else if Self.needsVLC(url) {
                result = [.init(player: .vlc, url: url)]
            } else {
                result = [.init(player: .apple, url: url), .init(player: .vlc, url: url)]
            }
        }
        return result
    }

    private static func needsVLC(_ url: URL) -> Bool {
        vlcExtensions.contains(url.pathExtension.lowercased())
            || vlcSchemes.contains(url.scheme?.lowercased() ?? "")
    }

    /// `…/live/user/pass/123.m3u8` → `…/live/user/pass/123.ts`
    private static func xtreamTSVariant(of url: URL) -> URL? {
        guard url.path().contains("/live/"), url.pathExtension.lowercased() == "m3u8" else { return nil }
        return url.deletingPathExtension().appendingPathExtension("ts")
    }
}

enum ResolvedPlayer: Equatable {
    case apple
    case vlc
}

struct PlaybackCandidate: Equatable {
    let player: ResolvedPlayer
    let url: URL

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
    private(set) var isPictureInPictureActive = false

    let supportsPictureInPicture = AVPictureInPictureController.isPictureInPictureSupported()

    @ObservationIgnored private var pipController: AVPictureInPictureController?
    @ObservationIgnored private var pendingSeek: Double?

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
        let item = AVPlayerItem(url: url)
        // Kleiner Vorpuffer bei Live: schnellerer Start und Senderwechsel.
        item.preferredForwardBufferDuration = isLive ? 3 : 0
        pendingSeek = resumeAt
        player.replaceCurrentItem(with: item)
        player.play()
    }

    func update() {
        guard let item = player.currentItem else { return }
        if item.status == .failed {
            failureMessage = item.error?.localizedDescription ?? "Das Format wird möglicherweise nicht unterstützt."
            return
        }
        isPlaying = player.timeControlStatus != .paused
        isBuffering = item.status == .unknown || player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        let current = player.currentTime().seconds
        time = current.isFinite ? current : 0
        let total = item.duration.seconds
        duration = total.isFinite ? total : 0
        isPictureInPictureActive = pipController?.isPictureInPictureActive ?? false

        if item.status == .readyToPlay, let target = pendingSeek {
            pendingSeek = nil
            seek(to: target)
        }
    }

    func togglePlayPause() {
        if player.timeControlStatus == .paused { player.play() } else { player.pause() }
        isPlaying.toggle()
    }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 600))
        time = seconds
    }

    func stop() {
        if pipController?.isPictureInPictureActive == true { pipController?.stopPictureInPicture() }
        player.pause()
        player.replaceCurrentItem(with: nil)
    }
}
