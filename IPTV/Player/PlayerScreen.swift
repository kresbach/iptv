import AVKit
import SwiftUI

/// Vollbild-Player: wählt Apple-Player oder VLC, merkt sich den Fortschritt
/// bei Filmen und Episoden und zeigt bei Live-Sendern das aktuelle Programm.
struct PlayerScreen: View {
    let channel: Channel

    @Environment(\.dismiss) private var dismiss
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @AppStorage(PlayerEngine.storageKey) private var engineSetting: PlayerEngine = .automatic

    @State private var engine: ResolvedPlayer?
    @State private var errorMessage: String?
    @State private var attempt = 0

    var body: some View {
        VStack(spacing: 0) {
            header

            ZStack {
                Color.black
                if errorMessage == nil {
                    switch engine {
                    case .apple?:
                        ApplePlayerView(channel: channel, onFailure: handleFailure)
                            .id(attempt)
                    case .vlc?:
                        vlcPlayer
                    case nil:
                        ProgressView().tint(.white)
                    }
                }
                if let errorMessage {
                    errorOverlay(errorMessage)
                }
            }

            if channel.kind == .live, let guide = store.epg, !guide.schedule(for: channel).isEmpty {
                PlayerEPGPanel(channel: channel, guide: guide)
            }
        }
        .background(.black)
        .preferredColorScheme(.dark)
        .task {
            engine = engineSetting.resolve(for: channel.url)
            userData.recordPlayback(of: channel)
        }
    }

    @ViewBuilder
    private var vlcPlayer: some View {
        #if canImport(VLCKitSPM) || canImport(MobileVLCKit)
        VLCPlayerView(channel: channel, onFailure: handleFailure)
            .id(attempt)
        #else
        EmptyView()
        #endif
    }

    private func handleFailure(_ message: String) {
        // Scheitert der Apple-Player, im Automatik-Modus VLC versuchen.
        if engine == .apple, engineSetting == .automatic, PlayerEngine.isVLCAvailable {
            engine = .vlc
            return
        }
        errorMessage = message
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.headline)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .accessibilityLabel("Schließen")

            VStack(alignment: .leading, spacing: 1) {
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

            Spacer()

            if engine == .vlc {
                Text("VLC")
                    .font(.caption2.bold())
                    .foregroundStyle(.secondary)
            }

            Button {
                userData.toggleFavorite(channel)
            } label: {
                Image(systemName: userData.isFavorite(channel) ? "heart.fill" : "heart")
                    .font(.headline)
                    .foregroundStyle(Theme.orange)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .accessibilityLabel(userData.isFavorite(channel) ? "Aus Favoriten entfernen" : "Zu Favoriten")

            if channel.kind == .live {
                Label("LIVE", systemImage: "dot.radiowaves.left.and.right")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(Theme.gradient, in: .capsule)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
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
            if !PlayerEngine.isVLCAvailable {
                Text("Tipp: Mit eingebundenem VLC lassen sich auch .ts- und .mkv-Streams abspielen.")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .multilineTextAlignment(.center)
            }
            Button("Erneut versuchen", systemImage: "arrow.clockwise") {
                errorMessage = nil
                engine = engineSetting.resolve(for: channel.url)
                attempt += 1
            }
            .buttonStyle(.glassProminent)
        }
        .padding(24)
        .glassEffect(in: .rect(cornerRadius: 24))
        .padding()
    }
}

/// Aktuelle und nächste Sendung unter dem Video.
private struct PlayerEPGPanel: View {
    let channel: Channel
    let guide: EPGGuide

    var body: some View {
        TimelineView(.everyMinute) { context in
            VStack(alignment: .leading, spacing: 10) {
                if let now = guide.current(for: channel, at: context.date) {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("JETZT").font(.caption2.bold()).foregroundStyle(Theme.orange)
                            Text(now.timeRange).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                        }
                        Text(now.title).font(.headline)
                        if let description = now.description {
                            Text(description).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        ProgressView(value: now.progress(at: context.date)).tint(Theme.orange)
                    }
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
            .padding()
            .glassEffect(in: .rect(cornerRadius: 22))
            .padding()
        }
    }
}

// MARK: - Apple-Player

/// AVKit-Player mit Bild-in-Bild und AirPlay, Weiterschauen und Fehlererkennung.
private struct ApplePlayerView: View {
    let channel: Channel
    let onFailure: (String) -> Void

    @Environment(UserDataStore.self) private var userData
    @State private var player = AVPlayer()

    var body: some View {
        SystemPlayerView(player: player)
            .task { await run() }
            .onDisappear {
                saveProgress()
                player.pause()
                player.replaceCurrentItem(with: nil)
            }
    }

    private func run() async {
        let item = AVPlayerItem(url: channel.url)
        player.replaceCurrentItem(with: item)
        player.play()

        var didResume = false
        var ticks = 0
        while !Task.isCancelled {
            switch item.status {
            case .failed:
                var message = item.error?.localizedDescription ?? "Das Format wird möglicherweise nicht unterstützt."
                if channel.url.pathExtension.lowercased() == "mkv" {
                    message = "MKV-Dateien kann der Apple-Player nicht abspielen."
                }
                onFailure(message)
                return
            case .readyToPlay where !didResume:
                didResume = true
                if channel.kind != .live, let position = userData.resumePosition(for: channel) {
                    player.seek(to: CMTime(seconds: position, preferredTimescale: 600))
                }
            default:
                break
            }

            ticks += 1
            if ticks % 5 == 0 { saveProgress() }
            try? await Task.sleep(for: .seconds(1))
        }
    }

    private func saveProgress() {
        guard channel.kind != .live, let item = player.currentItem else { return }
        userData.saveProgress(for: channel, position: player.currentTime().seconds, duration: item.duration.seconds)
    }
}

/// Bindet `AVPlayerViewController` ein, weil nur er Bild-in-Bild,
/// AirPlay und die vollständigen Systemsteuerelemente mitbringt.
private struct SystemPlayerView: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.player = player
        controller.allowsPictureInPicturePlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = true
        controller.videoGravity = .resizeAspect
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        if controller.player !== player {
            controller.player = player
        }
    }
}
