import AVKit
import SwiftUI

/// Vollbild-Player mit eigener Steuerung für Apple-Player und VLC:
/// Video über den ganzen Bildschirm, Bedienelemente blenden sich automatisch aus,
/// Zeitleiste zum Spulen bei Filmen und Episoden, TV-Programm bei Live-Sendern.
struct PlayerScreen: View {
    let channel: Channel

    @Environment(\.dismiss) private var dismiss
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @AppStorage(PlayerEngine.storageKey) private var engineSetting: PlayerEngine = .automatic

    @State private var engine: (any PlaybackEngine)?
    @State private var candidates: [PlaybackCandidate] = []
    @State private var candidateIndex = 0
    @State private var errorMessage: String?
    @State private var attempt = 0

    @State private var showsControls = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrubPosition: Double?
    @State private var isZoomed = false

    private var isLive: Bool { channel.kind == .live }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            videoSurface
                .ignoresSafeArea()

            // Fängt Tipps auf das Video ab und blendet die Steuerung ein/aus.
            Color.clear
                .contentShape(.rect)
                .ignoresSafeArea()
                .onTapGesture { toggleControls() }

            if let engine, engine.isBuffering, errorMessage == nil {
                ProgressView()
                    .controlSize(.large)
                    .tint(.white)
                    .allowsHitTesting(false)
            }

            if showsControls, errorMessage == nil {
                controlsOverlay
                    .transition(.opacity)
            }

            if let errorMessage {
                errorOverlay(errorMessage)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: showsControls)
        .statusBarHidden(!showsControls)
        .persistentSystemOverlays(showsControls ? .automatic : .hidden)
        .preferredColorScheme(.dark)
        .task(id: attempt) { await runPlayback() }
    }

    // MARK: - Video

    @ViewBuilder
    private var videoSurface: some View {
        if let apple = engine as? ApplePlaybackEngine {
            AppleVideoSurface(engine: apple, isZoomed: isZoomed)
        }
        #if canImport(VLCKitSPM) || canImport(MobileVLCKit)
        if let vlc = engine as? VLCPlaybackEngine {
            VLCVideoSurface(engine: vlc)
                .id(ObjectIdentifier(vlc))
        }
        #endif
    }

    // MARK: - Steuerung

    private var controlsOverlay: some View {
        VStack(spacing: 0) {
            topBar
            Spacer(minLength: 0)
            centerControls
            Spacer(minLength: 0)
            bottomBar
        }
        .background {
            LinearGradient(
                colors: [.black.opacity(0.7), .clear, .clear, .black.opacity(0.75)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()
            .allowsHitTesting(false)
        }
    }

    private var topBar: some View {
        HStack(spacing: 10) {
            roundButton("xmark", label: "Schließen") { close() }

            VStack(alignment: .leading, spacing: 2) {
                Text(channel.name)
                    .font(.headline)
                    .lineLimit(1)
                if let group = channel.group {
                    Text(group)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 8)

            if isLive {
                Label("LIVE", systemImage: "dot.radiowaves.left.and.right")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(Theme.gradient, in: .capsule)
            }

            AirPlayButton()
                .frame(width: 44, height: 44)
                .glassEffect(.regular.interactive(), in: .circle)

            if let apple = engine as? ApplePlaybackEngine {
                if apple.supportsPictureInPicture {
                    roundButton(
                        apple.isPictureInPictureActive ? "pip.exit" : "pip.enter",
                        label: "Bild-in-Bild"
                    ) { apple.togglePictureInPicture() }
                }
                roundButton(
                    isZoomed ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right",
                    label: isZoomed ? "Ganzes Bild" : "Bildschirm füllen"
                ) { isZoomed.toggle() }
            }

            roundButton(
                userData.isFavorite(channel) ? "heart.fill" : "heart",
                label: userData.isFavorite(channel) ? "Aus Favoriten entfernen" : "Zu Favoriten",
                tint: Theme.orange
            ) { userData.toggleFavorite(channel) }
        }
        .padding(.horizontal)
        .padding(.top, 8)
    }

    private var centerControls: some View {
        HStack(spacing: 36) {
            if !isLive, (engine?.duration ?? 0) > 0 {
                roundButton("gobackward.10", label: "10 Sekunden zurück", size: 56) { engine?.skip(by: -10) }
            }

            Button {
                engine?.togglePlayPause()
                scheduleHide()
            } label: {
                Image(systemName: engine?.isPlaying == true ? "pause.fill" : "play.fill")
                    .font(.system(size: 34, weight: .semibold))
                    .frame(width: 76, height: 76)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .accessibilityLabel(engine?.isPlaying == true ? "Pause" : "Abspielen")

            if !isLive, (engine?.duration ?? 0) > 0 {
                roundButton("goforward.10", label: "10 Sekunden vor", size: 56) { engine?.skip(by: 10) }
            }
        }
    }

    @ViewBuilder
    private var bottomBar: some View {
        if let engine, !isLive, engine.duration > 0 {
            let position = scrubPosition ?? engine.time
            VStack(spacing: 6) {
                Slider(
                    value: Binding(get: { position }, set: { scrubPosition = $0 }),
                    in: 0...max(engine.duration, 1)
                ) { editing in
                    if editing {
                        hideTask?.cancel()
                    } else if let target = scrubPosition {
                        engine.seek(to: target)
                        scrubPosition = nil
                        scheduleHide()
                    }
                }
                .tint(Theme.orange)

                HStack {
                    Text(Self.format(position))
                    Spacer()
                    Text("-" + Self.format(max(engine.duration - position, 0)))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white.opacity(0.85))
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
            .glassEffect(in: .rect(cornerRadius: 22))
            .padding(.horizontal)
            .padding(.bottom, 8)
        } else if isLive, let guide = store.epg, !guide.schedule(for: channel).isEmpty {
            PlayerEPGPanel(channel: channel, guide: guide)
                .padding(.horizontal)
                .padding(.bottom, 8)
        }
    }

    private func roundButton(
        _ symbol: String,
        label: String,
        size: CGFloat = 44,
        tint: Color = .white,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            action()
            scheduleHide()
        } label: {
            Image(systemName: symbol)
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: size - 16, height: size - 16)
        }
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .accessibilityLabel(label)
    }

    private func toggleControls() {
        showsControls.toggle()
        if showsControls { scheduleHide() }
    }

    /// Blendet die Steuerung nach 4 Sekunden aus, solange abgespielt wird.
    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, engine?.isPlaying == true, scrubPosition == nil else { return }
            showsControls = false
        }
    }

    // MARK: - Ablauf

    private func runPlayback() async {
        errorMessage = nil
        if attempt == 0 { userData.recordPlayback(of: channel) }
        candidates = engineSetting.candidates(for: channel)
        candidateIndex = 0
        startCandidate()
        showsControls = true
        scheduleHide()

        var ticks = 0
        while !Task.isCancelled {
            if let engine {
                engine.update()
                if let failure = engine.failureMessage {
                    engine.stop()
                    if candidateIndex + 1 < candidates.count {
                        // Nächsten Player bzw. nächstes Format probieren.
                        candidateIndex += 1
                        startCandidate()
                    } else {
                        self.engine = nil
                        errorMessage = failure
                        return
                    }
                }
            }
            ticks += 1
            if ticks % 10 == 0 { saveProgress() }
            try? await Task.sleep(for: .milliseconds(500))
        }

        // Ansicht wurde geschlossen.
        saveProgress()
        engine?.stop()
    }

    private func startCandidate() {
        guard candidates.indices.contains(candidateIndex) else { return }
        let candidate = candidates[candidateIndex]
        let newEngine = candidate.makeEngine()
        newEngine.start(
            url: candidate.url,
            isLive: isLive,
            resumeAt: isLive ? nil : userData.resumePosition(for: channel)
        )
        engine = newEngine
    }

    private func saveProgress() {
        guard !isLive, let engine else { return }
        userData.saveProgress(for: channel, position: engine.time, duration: engine.duration)
    }

    private func close() {
        saveProgress()
        engine?.stop()
        dismiss()
    }

    private static func format(_ seconds: Double) -> String {
        Duration.seconds(seconds).formatted(.time(pattern: seconds >= 3600 ? .hourMinuteSecond : .minuteSecond))
    }

    // MARK: - Fehler

    private func errorOverlay(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.largeTitle)
                .foregroundStyle(Theme.orange)
            Text("Wiedergabe nicht möglich")
                .font(.headline)
            Text(message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            HStack {
                Button("Schließen", systemImage: "xmark") { close() }
                    .buttonStyle(.glass)
                Button("Erneut versuchen", systemImage: "arrow.clockwise") {
                    errorMessage = nil
                    attempt += 1
                }
                .buttonStyle(.glassProminent)
            }
        }
        .padding(24)
        .frame(maxWidth: 420)
        .glassEffect(in: .rect(cornerRadius: 24))
        .padding()
    }
}

/// Aktuelle und nächste Sendung im Player.
private struct PlayerEPGPanel: View {
    let channel: Channel
    let guide: EPGGuide

    var body: some View {
        TimelineView(.everyMinute) { context in
            VStack(alignment: .leading, spacing: 8) {
                if let now = guide.current(for: channel, at: context.date) {
                    HStack {
                        Text("JETZT").font(.caption2.bold()).foregroundStyle(Theme.orange)
                        Text(now.timeRange).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                    Text(now.title).font(.headline).lineLimit(1)
                    ProgressView(value: now.progress(at: context.date)).tint(Theme.orange)
                }
                if let next = guide.next(for: channel, at: context.date) {
                    HStack {
                        Text("DANACH").font(.caption2.bold()).foregroundStyle(.secondary)
                        Text("\(next.start.formatted(date: .omitted, time: .shortened))  \(next.title)")
                            .font(.subheadline)
                            .lineLimit(1)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .glassEffect(in: .rect(cornerRadius: 22))
        }
    }
}

// MARK: - Video-Flächen

/// UIView, deren Layer direkt ein `AVPlayerLayer` ist.
final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

private struct AppleVideoSurface: UIViewRepresentable {
    let engine: ApplePlaybackEngine
    let isZoomed: Bool

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.backgroundColor = .black
        view.isUserInteractionEnabled = false
        view.playerLayer.player = engine.player
        view.playerLayer.videoGravity = .resizeAspect
        engine.attach(layer: view.playerLayer)
        return view
    }

    func updateUIView(_ view: PlayerLayerView, context: Context) {
        if view.playerLayer.player !== engine.player {
            view.playerLayer.player = engine.player
        }
        view.playerLayer.videoGravity = isZoomed ? .resizeAspectFill : .resizeAspect
    }
}

/// AirPlay-Auswahl des Systems.
private struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.tintColor = .white
        picker.activeTintColor = UIColor(Theme.orange)
        picker.prioritizesVideoDevices = true
        return picker
    }

    func updateUIView(_ view: AVRoutePickerView, context: Context) {}
}
