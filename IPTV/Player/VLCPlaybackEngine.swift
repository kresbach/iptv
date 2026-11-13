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

    @ObservationIgnored private var pendingSeek: Double?
    @ObservationIgnored private var hasDrawable = false
    @ObservationIgnored private var wantsPlayback = false

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
            "network-caching": isLive ? 1000 : 3000,
            "http-user-agent": HTTPClient.userAgent,
            "http-reconnect": true,
        ])
        player.media = media
        pendingSeek = resumeAt
        wantsPlayback = true
        if hasDrawable { player.play() }
    }

    func update() {
        let state = player.state
        if state == .error {
            failureMessage = "VLC konnte den Stream nicht öffnen. Ist der Link noch gültig?"
            return
        }
        isPlaying = player.isPlaying
        isBuffering = state == .opening || (state == .buffering && !player.isPlaying)
        time = Double(player.time.intValue) / 1000

        let length = Double(player.media?.length.intValue ?? 0) / 1000
        if length > 0 {
            duration = length
        } else if player.position > 0.001, time > 0 {
            // Manche Streams melden keine Länge – dann aus der Position schätzen.
            duration = time / Double(player.position)
        }

        // Weiterschauen erst, wenn VLC läuft und die Länge kennt.
        if let target = pendingSeek, duration > 0, player.isPlaying {
            pendingSeek = nil
            seek(to: target)
        }
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
        wantsPlayback = false
        player.stop()
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
