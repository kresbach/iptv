import AVKit
import Combine
import SwiftUI
import UniformTypeIdentifiers

/// Vollbild-Player mit eigener Steuerung für Apple-Player und VLC:
/// Video über den ganzen Bildschirm, Bedienelemente blenden sich automatisch aus,
/// Zeitleiste zum Spulen bei Filmen und Episoden, TV-Programm bei Live-Sendern.
struct PlayerScreen: View {
    let request: PlaybackRequest

    /// Aktuell laufender Eintrag; ändert sich beim Umschalten oder bei der nächsten Episode.
    @State private var current: Channel
    private var channel: Channel { current }

    init(request: PlaybackRequest) {
        self.request = request
        _current = State(initialValue: request.channel)
    }

    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    private var store: PlaylistStore { .shared }
    private var userData: UserDataStore { .shared }
    private var downloads: DownloadManager { .shared }
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
    @State private var showsChannelList = false
    @State private var showsSubtitleSearch = false
    @State private var airPlayHint: String?

    /// Nachbar in der Liste, aus der gestartet wurde. Live TV läuft am Ende wieder von vorn.
    private func neighbor(_ offset: Int) -> Channel? {
        let queue = request.queue
        guard queue.count > 1, let index = queue.firstIndex(where: { $0.id == current.id }) else { return nil }
        let target = index + offset
        if isLive { return queue[(target % queue.count + queue.count) % queue.count] }
        return queue.indices.contains(target) ? queue[target] : nil
    }

    /// Tastaturkürzel (Mac und iPad mit Tastatur): Leertaste = Pause, Pfeile links/rechts =
    /// spulen bzw. Sender wechseln, Esc = schließen.
    private var keyboardShortcuts: some View {
        Group {
            Button("Wiedergabe/Pause") {
                engine?.togglePlayPause()
                showsControls = true
                scheduleHide()
            }
            .keyboardShortcut(.space, modifiers: [])

            Button("Schließen") { close() }
                .keyboardShortcut(.escape, modifiers: [])

            Button("Zurück") { arrowKey(-1) }
                .keyboardShortcut(.leftArrow, modifiers: [])

            Button("Vor") { arrowKey(1) }
                .keyboardShortcut(.rightArrow, modifiers: [])
        }
        .opacity(0)
        .frame(width: 0, height: 0)
        .accessibilityHidden(true)
    }

    private func arrowKey(_ direction: Int) {
        if isLive {
            if let next = neighbor(direction) { switchTo(next) }
        } else {
            engine?.skip(by: Double(direction) * 10)
            showsControls = true
            scheduleHide()
        }
    }

    /// Aktion für die Tasten „vorheriger/nächster Sender“ (nur Live TV mit Senderliste).
    private func zapAction(_ offset: Int) -> (() -> Void)? {
        guard isLive, let target = neighbor(offset) else { return nil }
        return { switchTo(target) }
    }

    /// Wechselt im laufenden Player zu einem anderen Sender bzw. einer anderen Episode.
    private func switchTo(_ next: Channel) {
        guard next.id != current.id else { return }
        saveProgress()
        engine?.stop()
        engine = nil
        errorMessage = nil
        findings = []
        current = next
        downloads.playbackStarted(isLocalFile: downloads.localURL(for: next) != nil)
        showsControls = true
        attempt += 1
    }

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

            // Tippen, Doppeltipp, Wischen (Helligkeit, Lautstärke, Senderwechsel).
            PlayerGestureLayer(
                allowsSeek: !isLive,
                allowsZap: isLive && request.queue.count > 1,
                onTap: { toggleControls() },
                onSeek: { engine?.skip(by: $0) },
                onZap: { offset in
                    if let next = neighbor(offset) { switchTo(next) }
                }
            )
            .ignoresSafeArea()

            if let apple = engine as? ApplePlaybackEngine, apple.isExternalPlaybackActive {
                VStack(spacing: 10) {
                    Image(systemName: "airplayvideo")
                        .font(.system(size: 54))
                    Text("Wiedergabe über AirPlay")
                        .font(.headline)
                }
                .foregroundStyle(.white.opacity(0.85))
                .allowsHitTesting(false)
            }

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
        .background { keyboardShortcuts }
        .animation(.easeInOut(duration: 0.25), value: showsControls)
        .statusBarHidden(!showsControls)
        .persistentSystemOverlays(showsControls ? .automatic : .hidden)
        .preferredColorScheme(.dark)
        .task(id: attempt) { await runPlayback() }
        .onAppear {
            // VLC hält den Bildschirm nicht von selbst wach (der Apple-Player schon).
            UIApplication.shared.isIdleTimerDisabled = true
            store.playbackStarted()
            downloads.playbackStarted(isLocalFile: downloads.localURL(for: channel) != nil)
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            saveProgress()
            engine?.stop()
            engine = nil
            store.playbackEnded()
            downloads.playbackEnded(
                channel: channel,
                finished: userData.playbackProgress(for: channel)?.isFinished == true
            )
        }
        .onChange(of: scenePhase) { _, phase in
            handleScenePhase(phase)
        }
        .onReceive(
            NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification).receive(on: DispatchQueue.main)
        ) { _ in
            handleRouteChange()
        }
        .sheet(isPresented: $showsChannelList) {
            NavigationStack {
                PlayerChannelList(channels: request.queue, current: current) { selected in
                    showsChannelList = false
                    switchTo(selected)
                }
            }
            .appEnvironment()
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showsSubtitleSearch) {
            if let engine {
                NavigationStack {
                    SubtitleSearchView(channel: channel, engine: engine)
                }
                .appEnvironment()
                .presentationDetents([.medium, .large])
            }
        }
    }

    /// AirPlay gewählt, während VLC läuft: auf den Apple-Player wechseln, denn nur er
    /// kann Video per AirPlay an den Fernseher übergeben.
    private func handleRouteChange() {
        guard PlayerEngine.isAirPlayRouteActive else {
            airPlayHint = nil
            return
        }
        guard engine != nil, !(engine is ApplePlaybackEngine) else { return }
        let playbackChannel = downloads.localURL(for: channel).map { channel.with(url: $0) } ?? channel
        if PlayerEngine.airPlayCandidates(for: playbackChannel) != nil {
            saveProgress()
            engine?.stop()
            engine = nil
            attempt += 1
        } else {
            airPlayHint = "Dieses Format kann nicht direkt per AirPlay gesendet werden. Nutze im Kontrollzentrum „Bildschirmsynchronisierung“, um es auf dem TV zu sehen."
        }
    }

    /// VLC zeichnet mit der GPU und darf im Hintergrund nicht weiterlaufen; außerdem
    /// Fortschritt sichern. Der Apple-Player läuft im Hintergrund als Bild-in-Bild weiter.
    private func handleScenePhase(_ phase: ScenePhase) {
        guard phase == .background else { return }
        saveProgress()
        #if canImport(VLCKitSPM) || canImport(MobileVLCKit)
        if let vlc = engine as? VLCPlaybackEngine, vlc.isPlaying {
            vlc.togglePlayPause()
            showsControls = true
        }
        #endif
    }

    // MARK: - Video

    @ViewBuilder
    private var videoSurface: some View {
        if let apple = engine as? ApplePlaybackEngine {
            AppleVideoSurface(engine: apple, isZoomed: isZoomed)
                .id(ObjectIdentifier(apple))
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

            if let engine {
                TrackMenu(
                    engine: engine,
                    onOpen: { hideTask?.cancel() },
                    onSelect: { scheduleHide() },
                    onSearchOnline: !isLive && engine.supportsExternalSubtitles ? {
                        hideTask?.cancel()
                        showsSubtitleSearch = true
                    } : nil
                )
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
            CenterControls(
                engine: engine,
                isLive: isLive,
                onPrevious: zapAction(-1),
                onNext: zapAction(1),
                onInteraction: scheduleHide
            )
        }
    }

    @ViewBuilder
    private var bottomBar: some View {
        if let airPlayHint {
            Text(airPlayHint)
                .font(.footnote)
                .foregroundStyle(.white)
                .padding(12)
                .background(.black.opacity(0.6), in: .rect(cornerRadius: 14))
                .padding(.horizontal)
                .padding(.bottom, 6)
        }
        if let engine, !isLive {
            if let next = neighbor(1) {
                HStack {
                    Spacer()
                    Button {
                        switchTo(next)
                    } label: {
                        Label("Nächste Episode", systemImage: "forward.end.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 9)
                            .background(.black.opacity(0.55), in: .capsule)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal)
                .padding(.bottom, 6)
            }
            PlaybackTimeline(
                engine: engine,
                onScrubbingChanged: { scrubbing in
                    isScrubbing = scrubbing
                    if scrubbing { hideTask?.cancel() } else { scheduleHide() }
                }
            )
        } else if isLive {
            if request.queue.count > 1 {
                HStack {
                    Button {
                        hideTask?.cancel()
                        showsChannelList = true
                    } label: {
                        Label("Senderliste", systemImage: "list.bullet")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 9)
                            .background(.black.opacity(0.55), in: .capsule)
                    }
                    .buttonStyle(.plain)
                    Spacer()
                }
                .padding(.horizontal)
                .padding(.bottom, 6)
            }
            if let guide = store.epg, !guide.schedule(for: channel).isEmpty {
                PlayerEPGPanel(channel: channel, guide: guide)
                    .padding(.horizontal)
                    .padding(.bottom, 8)
            }
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
        userData.recordPlayback(of: channel)
        // Heruntergeladene Filme/Episoden von der lokalen Datei abspielen (auch offline).
        let playbackChannel = downloads.localURL(for: channel).map { channel.with(url: $0) } ?? channel
        candidates = engineSetting.candidates(for: playbackChannel)
        // Ist ein Fernseher per AirPlay gewählt, den Apple-Player nehmen (nur er überträgt Video).
        if PlayerEngine.isAirPlayRouteActive {
            if let airPlay = PlayerEngine.airPlayCandidates(for: playbackChannel) {
                candidates = airPlay + candidates.filter { $0.player != .apple }
                airPlayHint = nil
            } else {
                airPlayHint = "Dieses Format kann nicht direkt per AirPlay gesendet werden. Nutze im Kontrollzentrum „Bildschirmsynchronisierung“, um es auf dem TV zu sehen."
            }
        }
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
        // Live-HLS-Sitzungen gibt der Server oft erst nach 20–60 s frei.
        let maxRetryRounds = 10
        var candidateStart = ContinuousClock.now
        var stalledSince: ContinuousClock.Instant?
        var reconnects = 0
        var stableSince: ContinuousClock.Instant?
        var appliedSavedSubtitle = false
        var lastTime = -1.0
        var lastTimeChange = ContinuousClock.now
        var sawTimeAdvance = false
        while !Task.isCancelled {
            if let engine {
                engine.update()

                if engine.hasStartedPlayback, !appliedSavedSubtitle {
                    appliedSavedSubtitle = true
                    // Früher gewählten Online-Untertitel für diesen Film wieder einblenden.
                    if !isLive, engine.supportsExternalSubtitles,
                       let file = SubtitleService.shared.savedSubtitle(for: channel) {
                        engine.addSubtitle(file: file)
                    }
                }
                if engine.hasStartedPlayback {
                    // Live: Hängt das Bild länger oder trennt der Server, automatisch neu verbinden.
                    if engine.isBuffering {
                        stalledSince = stalledSince ?? .now
                        stableSince = nil
                    } else {
                        stalledSince = nil
                        stableSince = stableSince ?? .now
                        // Nach einer Minute störungsfreier Wiedergabe zählt der Zähler neu.
                        if let stable = stableSince, ContinuousClock.now - stable > .seconds(60) { reconnects = 0 }
                    }
                    // Eingefrorenes Bild erkennen: Player meldet „läuft“, die Zeit steht aber.
                    if engine.isPlaying, engine.time == lastTime {
                        // unverändert
                    } else {
                        if engine.isPlaying, lastTime >= 0, engine.time != lastTime { sawTimeAdvance = true }
                        lastTime = engine.time
                        lastTimeChange = .now
                    }
                    let frozen = sawTimeAdvance && ContinuousClock.now - lastTimeChange > .seconds(12)
                    let stalled = frozen || (stalledSince.map { ContinuousClock.now - $0 > .seconds(12) } ?? false)

                    if isLive, engine.hasEnded || stalled || engine.failureMessage != nil, reconnects < 15 {
                        reconnects += 1
                        engine.stop()
                        self.engine = nil
                        connectionWaitMessage = "Verbindung unterbrochen – wird wiederhergestellt …"
                        try? await Task.sleep(for: .seconds(reconnects > 3 ? 4 : 1.5))
                        await finishPreviousPlayback()
                        guard !Task.isCancelled else { break }
                        connectionWaitMessage = nil
                        stalledSince = nil
                        stableSince = nil
                        lastTime = -1
                        lastTimeChange = .now
                        sawTimeAdvance = false
                        startCandidate()
                        candidateStart = .now
                        continue
                    }
                    if isLive, engine.hasEnded || stalled, engine.failureMessage == nil {
                        engine.stop()
                        self.engine = nil
                        errorMessage = "Die Verbindung zum Sender bricht immer wieder ab. Bitte prüfe deine Internetverbindung oder versuche es später erneut."
                        return
                    }
                    if !isLive, engine.hasEnded {
                        // Zu Ende: bei Serien direkt die nächste Episode, sonst Player schließen.
                        if let next = neighbor(1) {
                            switchTo(next)
                        } else {
                            close()
                        }
                        return
                    }
                }

                // Startet ein Versuch nicht in angemessener Zeit, direkt den nächsten probieren,
                // statt auf das (oft sehr lange) Timeout des Players zu warten.
                let hasAlternative = candidateIndex + 1 < candidates.count
                let canRetry = hasAlternative || retryRound < maxRetryRounds
                let timedOut = canRetry && !engine.hasStartedPlayback
                    && ContinuousClock.now - candidateStart > .seconds(isLive ? 15 : 25)

                if let failure = engine.failureMessage ?? (timedOut ? "Zeitüberschreitung" : nil) {
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
                        var message = "Der Server gibt die vorherige Verbindung noch frei …\nNeuer Versuch \(retryRound) von \(maxRetryRounds)"
                        if isLive, engineSetting == .apple, PlayerEngine.isVLCAvailable {
                            message += "\n\nTipp: Mit „Live TV: Automatisch“ in den Einstellungen wird beim Umschalten sofort freigegeben."
                        }
                        connectionWaitMessage = message
                        try? await Task.sleep(for: .seconds(5))
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
                            lines.append("Dieses Format kann nur VLC abspielen. Stelle unter Einstellungen → Player „VLC“ oder „Automatisch“ ein.")
                        }
                        errorMessage = lines.joined(separator: "\n")
                        return
                    }
                }
            }
            ticks += 1
            // Beim Start häufiger nachsehen, damit Bild und Ton ohne Verzögerung freigegeben werden.
            let started = engine?.hasStartedPlayback ?? false
            if started, ticks % 30 == 0 { saveProgress() }  // etwa alle 15 Sekunden
            try? await Task.sleep(for: .milliseconds(started ? 500 : 120))
        }

        // Aufräumen übernehmen `close()`, `switchTo(_:)` bzw. `onDisappear` – nicht hier,
        // sonst würde ein beendeter Durchlauf den Player des nächsten stoppen.
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
                    // Der Link enthält Zugangsdaten: nur auf diesem Gerät und nur für zwei Minuten.
                    UIPasteboard.general.setItems(
                        [[UTType.plainText.identifier: channel.url.absoluteString]],
                        options: [.localOnly: true, .expirationDate: Date.now.addingTimeInterval(120)]
                    )
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

/// Menü für Untertitel und Tonspur. Erscheint nur, wenn es etwas zu wählen gibt.
private struct TrackMenu: View {
    let engine: any PlaybackEngine
    let onOpen: () -> Void
    let onSelect: () -> Void
    /// Öffnet die Online-Suche; `nil`, wenn der Player keine externen Untertitel kann.
    var onSearchOnline: (() -> Void)?

    var body: some View {
        let subtitles = engine.subtitleTracks
        let audio = engine.audioTracks

        if !subtitles.isEmpty || audio.count > 1 || onSearchOnline != nil {
            Menu {
                if let onSearchOnline {
                    Button("Untertitel online suchen …", systemImage: "magnifyingglass", action: onSearchOnline)
                }
                if !subtitles.isEmpty {
                    Section("Untertitel") {
                        Button {
                            engine.selectSubtitle(nil)
                            onSelect()
                        } label: {
                            optionLabel("Aus", isSelected: engine.selectedSubtitle == nil)
                        }
                        ForEach(subtitles) { track in
                            Button {
                                engine.selectSubtitle(track.id)
                                onSelect()
                            } label: {
                                optionLabel(track.name, isSelected: engine.selectedSubtitle == track.id)
                            }
                        }
                    }
                }
                if audio.count > 1 {
                    Section("Tonspur") {
                        ForEach(audio) { track in
                            Button {
                                engine.selectAudio(track.id)
                                onSelect()
                            } label: {
                                optionLabel(track.name, isSelected: engine.selectedAudio == track.id)
                            }
                        }
                    }
                }
            } label: {
                PlayerButtonLabel(
                    symbol: engine.selectedSubtitle == nil ? "captions.bubble" : "captions.bubble.fill",
                    size: 44
                )
            }
            .simultaneousGesture(TapGesture().onEnded { onOpen() })
            .accessibilityLabel("Untertitel und Tonspur")
        }
    }

    @ViewBuilder
    private func optionLabel(_ title: String, isSelected: Bool) -> some View {
        if isSelected {
            Label(title, systemImage: "checkmark")
        } else {
            Text(title)
        }
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
    var onPrevious: (() -> Void)?
    var onNext: (() -> Void)?
    let onInteraction: () -> Void

    var body: some View {
        let canSeek = !isLive && engine.duration > 0
        HStack(spacing: 36) {
            if let onPrevious {
                button("backward.end.fill", size: 56, label: "Vorheriger Sender", action: onPrevious)
            }
            if canSeek {
                button("gobackward.10", size: 56, label: "10 Sekunden zurück") { engine.skip(by: -10) }
            }
            button(engine.isPlaying ? "pause.fill" : "play.fill", size: 80, label: engine.isPlaying ? "Pause" : "Abspielen") {
                engine.togglePlayPause()
            }
            if canSeek {
                button("goforward.10", size: 56, label: "10 Sekunden vor") { engine.skip(by: 10) }
            }
            if let onNext {
                button("forward.end.fill", size: 56, label: "Nächster Sender", action: onNext)
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

/// Senderliste im Player zum direkten Umschalten.
private struct PlayerChannelList: View {
    let channels: [Channel]
    let current: Channel
    let onSelect: (Channel) -> Void

    @State private var query = ""

    var body: some View {
        let filtered = query.isEmpty ? channels : channels.filter { $0.name.localizedStandardContains(query) }

        ScrollViewReader { proxy in
            List(filtered) { channel in
                Button {
                    onSelect(channel)
                } label: {
                    HStack {
                        ChannelRow(channel: channel, showsCategory: false)
                        if channel.id == current.id {
                            Image(systemName: "speaker.wave.2.fill")
                                .foregroundStyle(Theme.orange)
                                .accessibilityLabel("Läuft gerade")
                        }
                    }
                }
                .buttonStyle(.plain)
                .id(channel.id)
            }
            .listStyle(.plain)
            .onAppear { proxy.scrollTo(current.id, anchor: .center) }
        }
        .searchable(text: $query, prompt: "Sender suchen")
        .navigationTitle("Senderliste")
        .navigationBarTitleDisplayMode(.inline)
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
            engine.attach(layer: view.playerLayer)
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
