#if canImport(VLCKitSPM) || canImport(MobileVLCKit)
import SwiftUI
#if canImport(VLCKitSPM)
@preconcurrency import VLCKitSPM
#else
@preconcurrency import MobileVLCKit
#endif

/// VLC-Player für Formate, die AVPlayer nicht kann (.ts, .mkv, rtmp …).
/// Wird nur gebaut, wenn das Paket „vlckit-spm“ im Projekt eingebunden ist.
struct VLCPlayerView: View {
    let channel: Channel
    let onFailure: (String) -> Void

    @Environment(UserDataStore.self) private var userData
    @State private var model = VLCPlayerModel()
    @State private var showsControls = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrubPosition: Double?

    var body: some View {
        ZStack {
            VLCVideoSurface(player: model.player)
                .contentShape(.rect)
                .onTapGesture { toggleControls() }

            if model.isBuffering {
                ProgressView()
                    .controlSize(.large)
                    .tint(.white)
            }

            if showsControls {
                controls
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: showsControls)
        .task { await run() }
        .onDisappear {
            saveProgress()
            model.stop()
        }
    }

    // MARK: - Steuerung

    private var controls: some View {
        VStack {
            Spacer()

            Button {
                model.togglePlayPause()
                scheduleHide()
            } label: {
                Image(systemName: model.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 34, weight: .semibold))
                    .frame(width: 70, height: 70)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .accessibilityLabel(model.isPlaying ? "Pause" : "Abspielen")

            Spacer()

            if channel.kind != .live, model.duration > 0 {
                VStack(spacing: 4) {
                    Slider(
                        value: Binding(
                            get: { scrubPosition ?? model.time },
                            set: { scrubPosition = $0 }
                        ),
                        in: 0...model.duration
                    ) { editing in
                        if !editing, let target = scrubPosition {
                            model.seek(to: target)
                            scrubPosition = nil
                            scheduleHide()
                        }
                    }
                    HStack {
                        Text(Self.format(scrubPosition ?? model.time))
                        Spacer()
                        Text("-" + Self.format(max(model.duration - (scrubPosition ?? model.time), 0)))
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.85))
                }
                .padding()
                .glassEffect(in: .rect(cornerRadius: 20))
                .padding()
            }
        }
    }

    private func toggleControls() {
        showsControls.toggle()
        if showsControls { scheduleHide() }
    }

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, model.isPlaying, scrubPosition == nil else { return }
            showsControls = false
        }
    }

    // MARK: - Ablauf

    private func run() async {
        let resume = channel.kind == .live ? nil : userData.resumePosition(for: channel)
        model.start(url: channel.url, resumeAt: resume)
        scheduleHide()

        var ticks = 0
        while !Task.isCancelled {
            model.update()
            if model.didFail {
                onFailure("VLC konnte den Stream nicht öffnen. Ist der Link noch gültig?")
                return
            }
            ticks += 1
            if ticks % 10 == 0 { saveProgress() }
            try? await Task.sleep(for: .milliseconds(500))
        }
    }

    private func saveProgress() {
        guard channel.kind != .live else { return }
        userData.saveProgress(for: channel, position: model.time, duration: model.duration)
    }

    private static func format(_ seconds: Double) -> String {
        Duration.seconds(seconds).formatted(.time(pattern: seconds >= 3600 ? .hourMinuteSecond : .minuteSecond))
    }
}

/// Zustand des VLC-Players, wird regelmäßig abgefragt.
@MainActor
@Observable
final class VLCPlayerModel {
    let player = VLCMediaPlayer()

    private(set) var isPlaying = false
    private(set) var isBuffering = true
    private(set) var time: Double = 0
    private(set) var duration: Double = 0
    private(set) var didFail = false

    @ObservationIgnored private var pendingSeek: Double?

    func start(url: URL, resumeAt: Double?) {
        let media = VLCMedia(url: url)
        // Etwas größerer Puffer für Live-Streams über Mobilfunk.
        media.addOptions(["network-caching": 1500, "http-user-agent": HTTPClient.userAgent])
        player.media = media
        pendingSeek = resumeAt
        player.play()
    }

    func update() {
        let state = player.state
        isPlaying = player.isPlaying
        isBuffering = state == .opening || state == .buffering && !player.isPlaying
        didFail = state == .error
        time = Double(player.time.intValue) / 1000
        duration = Double(player.media?.length.intValue ?? 0) / 1000

        // Weiterschauen erst, wenn VLC die Länge kennt.
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
        player.time = VLCTime(int: Int32(seconds * 1000))
        time = seconds
    }

    func stop() {
        player.stop()
    }
}

/// Fläche, in die VLC das Video zeichnet.
private struct VLCVideoSurface: UIViewRepresentable {
    let player: VLCMediaPlayer

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .black
        player.drawable = view
        return view
    }

    func updateUIView(_ view: UIView, context: Context) {}
}
#endif
