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
    @AppStorage(PlayerEngine.liveStorageKey) private var liveEngine: PlayerEngine = PlayerEngine.liveDefault
    @AppStorage(PlayerEngine.vodStorageKey) private var vodEngine: PlayerEngine = PlayerEngine.vodDefault

    /// Player-Einstellung passend zum Inhalt: Live TV oder Filme/Serien.
    private var engineSetting: PlayerEngine { isLive ? liveEngine : vodEngine }

    @State private var engine: (any PlaybackEngine)?
    @State private var candidates: [PlaybackCandidate] = []
    @State private var candidateIndex = 0
    @State private var errorMessage: String?
    @State private var attempt = 0

    @State private var showsControls = true
    @State private var hideTask: Task<Void, Never>?
    @State private var isScrubbing = false
    @State private var findings: [StreamDiagnostics.Finding] = []
    /// Fehler aller Versuche, z. B. „Apple-Player: Öffnen fehlgeschlagen“.
    @State private var attemptErrors: [String] = []
    @State private var connectionWaitMessage: String?
    @State private var isDiagnosing = false
    @State private var isZoomed = false

    private var isLive: Bool { channel.kind == .live }

    /// Was gerade versucht wird, z. B. „VLC · Versuch 2 von 2“.
    private var statusText: String {
        guard candidates.indices.contains(candidateIndex) else { return "" }
        let name = candidates[candidateIndex].playerName
        return candidates.count > 1 ? "\(name) · Versuch \(candidateIndex + 1) von \(candidates.count)" : name
    }

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

            if let engine, errorMessage == nil {
                BufferingIndicator(engine: engine, status: statusText)
            } else if engine == nil, errorMessage == nil {
                VStack(spacing: 12) {
                    ProgressView().controlSize(.large).tint(.white)
                    if let connectionWaitMessage {
                        Text(connectionWaitMessage)
                            .font(.footnote)
                            .foregroundStyle(.white.opacity(0.85))
                            .multilineTextAlignment(.center)
                    }
                }
                .padding()
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
                .background(.black.opacity(0.45), in: .circle)

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

    @ViewBuilder
    private var centerControls: some View {
        if let engine {
            CenterControls(engine: engine, isLive: isLive, onInteraction: scheduleHide)
        }
    }

    @ViewBuilder
    private var bottomBar: some View {
        if let engine, !isLive {
            PlaybackTimeline(
                engine: engine,
                onScrubbingChanged: { scrubbing in
                    isScrubbing = scrubbing
                    if scrubbing { hideTask?.cancel() } else { scheduleHide() }
                }
            )
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
            PlayerButtonLabel(symbol: symbol, size: size, tint: tint)
        }
        .buttonStyle(.plain)
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
            guard !Task.isCancelled, engine?.isPlaying == true, !isScrubbing else { return }
            showsControls = false
        }
    }

    // MARK: - Ablauf

    private func runPlayback() async {
        errorMessage = nil
        attemptErrors = []
        if attempt == 0 { userData.recordPlayback(of: channel) }
        candidates = engineSetting.candidates(for: channel)
        candidateIndex = 0
        // Nur lokal warten, bis ein vorheriger VLC-Player gestoppt ist – nicht auf den
        // Verbindungszähler des Servers, der oft veraltet ist.
        await finishPreviousPlayback()
        guard !Task.isCancelled else { return }
        startCandidate()
        showsControls = true
        scheduleHide()

        var ticks = 0
        var retryRound = 0
        let maxRetryRounds = 4
        var candidateStart = ContinuousClock.now
        while !Task.isCancelled {
            if let engine {
                engine.update()

                // Startet ein Versuch nicht in angemessener Zeit, direkt den nächsten probieren,
                // statt auf das (oft sehr lange) Timeout des Players zu warten.
                let hasAlternative = candidateIndex + 1 < candidates.count
                let canRetry = hasAlternative || retryRound < maxRetryRounds
                let timedOut = canRetry && !engine.hasStartedPlayback
                    && ContinuousClock.now - candidateStart > .seconds(isLive ? 15 : 25)

                if let failure = engine.failureMessage ?? (timedOut ? "Zeitüberschreitung" : nil) {
                    print("Wiedergabe mit \(candidates[candidateIndex].playerName) fehlgeschlagen: \(failure)")
                    let line = "\(candidates[candidateIndex].playerName): \(failure)"
                    if !attemptErrors.contains(line) { attemptErrors.append(line) }
                    engine.stop()
                    if hasAlternative {
                        self.engine = nil
                        // IPTV-Konten erlauben oft nur eine Verbindung: warten, bis der Server
                        // die alte freigegeben hat, bevor der nächste Versuch startet.
                        try? await Task.sleep(for: .seconds(1.5))
                        await finishPreviousPlayback()
                        guard !Task.isCancelled else { break }
                        candidateIndex += 1
                        startCandidate()
                        candidateStart = .now
                    } else if retryRound < maxRetryRounds {
                        // Häufigster Grund bei Konten mit einer Verbindung: Der Server hat die
                        // vorherige noch nicht freigegeben. Automatisch erneut versuchen.
                        retryRound += 1
                        self.engine = nil
                        connectionWaitMessage = "Stream startet noch nicht – vermutlich ist die vorherige Verbindung beim Server noch offen.\nNeuer Versuch \(retryRound) von \(maxRetryRounds) …"
                        try? await Task.sleep(for: .seconds(4))
                        await finishPreviousPlayback()
                        guard !Task.isCancelled else { break }
                        connectionWaitMessage = nil
                        candidateIndex = 0
                        startCandidate()
                        candidateStart = .now
                    } else {
                        self.engine = nil
                        let ext = channel.url.pathExtension.isEmpty ? "ohne Endung" : ".\(channel.url.pathExtension)"
                        var lines = attemptErrors + ["Dateityp: \(ext)"]
                        let vlcOnly = ["mkv", "avi", "ts", "flv", "wmv"].contains(channel.url.pathExtension.lowercased())
                        if vlcOnly && !PlayerEngine.isVLCAvailable {
                            lines.append("Dieses Format kann nur VLC abspielen, VLC ist aber nicht eingebunden.")
                        } else if vlcOnly && engineSetting == .apple {
                            lines.append("Dieses Format kann nur VLC abspielen. Stelle unter Einstellungen → Wiedergabe „Automatisch“ ein.")
                        }
                        errorMessage = lines.joined(separator: "\n")
                        return
                    }
                }
            }
            ticks += 1
            if ticks % 30 == 0 { saveProgress() }  // alle 15 Sekunden
            try? await Task.sleep(for: .milliseconds(500))
        }

        // Ansicht wurde geschlossen.
        saveProgress()
        engine?.stop()
    }

    /// Wartet (höchstens 10 Sekunden), bis ein zuvor genutzter VLC-Player vollständig
    /// gestoppt ist und seine Verbindung geschlossen hat.
    private func finishPreviousPlayback() async {
        #if canImport(VLCKitSPM) || canImport(MobileVLCKit)
        guard VLCPlayerRetirement.isBusy else { return }
        connectionWaitMessage = "Vorherige Wiedergabe wird beendet …"
        defer { connectionWaitMessage = nil }
        for _ in 0..<40 {
            guard VLCPlayerRetirement.isBusy, !Task.isCancelled else { return }
            try? await Task.sleep(for: .milliseconds(250))
        }
        #endif
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
        engine = nil
        dismiss()
    }

    // MARK: - Fehler

    private func diagnose() async {
        isDiagnosing = true
        defer { isDiagnosing = false }
        findings = await StreamDiagnostics.run(for: channel, source: store.activePlaylist?.source)
    }

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
            if !findings.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(findings) { finding in
                        Label {
                            Text(finding.text).font(.caption)
                        } icon: {
                            Image(systemName: finding.level == .ok ? "checkmark.circle.fill"
                                  : finding.level == .warning ? "exclamationmark.circle.fill" : "xmark.octagon.fill")
                                .foregroundStyle(finding.level == .ok ? Color.green : finding.level == .warning ? Color.yellow : Color.red)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            HStack {
                Button("Schließen", systemImage: "xmark") { close() }
                    .buttonStyle(.glass)
                Button("Erneut versuchen", systemImage: "arrow.clockwise") {
                    errorMessage = nil
                    findings = []
                    attempt += 1
                }
                .buttonStyle(.glassProminent)
            }

            HStack(spacing: 16) {
                Button {
                    Task { await diagnose() }
                } label: {
                    if isDiagnosing {
                        ProgressView()
                    } else {
                        Label("Diagnose", systemImage: "stethoscope")
                    }
                }
                .disabled(isDiagnosing)

                Button("Link kopieren", systemImage: "doc.on.doc") {
                    UIPasteboard.general.string = channel.url.absoluteString
                }
            }
            .font(.footnote)
            .foregroundStyle(Theme.orange)
        }
        .padding(24)
        .frame(maxWidth: 460)
        .background(.black.opacity(0.75), in: .rect(cornerRadius: 24))
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
            .background(.black.opacity(0.55), in: .rect(cornerRadius: 22))
        }
    }
}

// MARK: - Unter-Views
// Jede liest nur den Zustand, den sie braucht. So zeichnet SwiftUI bei jeder
// Zeitänderung nur die Zeitleiste neu und nicht den ganzen Player.

private struct PlayerButtonLabel: View {
    let symbol: String
    let size: CGFloat
    var tint: Color = .white

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.4, weight: .semibold))
            .foregroundStyle(tint)
            .frame(width: size, height: size)
            .background(.black.opacity(0.45), in: .circle)
            .contentShape(.circle)
    }
}

private struct BufferingIndicator: View {
    let engine: any PlaybackEngine
    let status: String

    var body: some View {
        if engine.isBuffering || !engine.hasStartedPlayback {
            VStack(spacing: 12) {
                ProgressView()
                    .controlSize(.large)
                    .tint(.white)
                if !engine.hasStartedPlayback, !status.isEmpty {
                    Text("Verbinde … (\(status))")
                        .font(.footnote)
                        .foregroundStyle(.white.opacity(0.8))
                }
            }
            .allowsHitTesting(false)
        }
    }
}

private struct CenterControls: View {
    let engine: any PlaybackEngine
    let isLive: Bool
    let onInteraction: () -> Void

    var body: some View {
        let canSeek = !isLive && engine.duration > 0
        HStack(spacing: 36) {
            if canSeek {
                button("gobackward.10", size: 56, label: "10 Sekunden zurück") { engine.skip(by: -10) }
            }
            button(engine.isPlaying ? "pause.fill" : "play.fill", size: 80, label: engine.isPlaying ? "Pause" : "Abspielen") {
                engine.togglePlayPause()
            }
            if canSeek {
                button("goforward.10", size: 56, label: "10 Sekunden vor") { engine.skip(by: 10) }
            }
        }
    }

    private func button(_ symbol: String, size: CGFloat, label: String, action: @escaping () -> Void) -> some View {
        Button {
            action()
            onInteraction()
        } label: {
            PlayerButtonLabel(symbol: symbol, size: size)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Zeitleiste zum Spulen. Während des Ziehens wird nur lokal aktualisiert,
/// gesprungen wird erst beim Loslassen.
private struct PlaybackTimeline: View {
    let engine: any PlaybackEngine
    let onScrubbingChanged: (Bool) -> Void

    @State private var scrubPosition: Double?

    var body: some View {
        if engine.duration > 0 {
            let position = scrubPosition ?? engine.time
            VStack(spacing: 6) {
                Slider(
                    value: Binding(get: { position }, set: { scrubPosition = $0 }),
                    in: 0...max(engine.duration, 1)
                ) { editing in
                    onScrubbingChanged(editing)
                    if !editing, let target = scrubPosition {
                        engine.seek(to: target)
                        scrubPosition = nil
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
            .background(.black.opacity(0.55), in: .rect(cornerRadius: 22))
            .padding(.horizontal)
            .padding(.bottom, 8)
        }
    }

    private static func format(_ seconds: Double) -> String {
        Duration.seconds(seconds).formatted(.time(pattern: seconds >= 3600 ? .hourMinuteSecond : .minuteSecond))
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
