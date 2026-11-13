import AVKit
import SwiftUI

/// Vollbild-Player für einen Sender: nativer AVKit-Player mit
/// Bild-in-Bild, AirPlay und Systemsteuerung.
struct PlayerScreen: View {
    let channel: Channel

    @Environment(\.dismiss) private var dismiss
    @State private var player = AVPlayer()
    @State private var errorMessage: String?
    @State private var attempt = 0

    var body: some View {
        VStack(spacing: 0) {
            header

            ZStack {
                Color.black
                SystemPlayerView(player: player)

                if let errorMessage {
                    errorOverlay(errorMessage)
                }
            }
        }
        .background(.black)
        .preferredColorScheme(.dark)
        .task(id: attempt) { await startPlayback() }
        .onDisappear {
            player.pause()
            player.replaceCurrentItem(with: nil)
        }
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

            Label("LIVE", systemImage: "dot.radiowaves.left.and.right")
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(Theme.gradient, in: .capsule)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
    }

    private func errorOverlay(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.largeTitle)
                .foregroundStyle(Theme.orange)
            Text("Stream kann nicht abgespielt werden")
                .font(.headline)
            Text(message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Erneut versuchen", systemImage: "arrow.clockwise") { attempt += 1 }
                .buttonStyle(.glassProminent)
        }
        .padding(24)
        .glassEffect(in: .rect(cornerRadius: 24))
        .padding()
    }

    private func startPlayback() async {
        errorMessage = nil
        let item = AVPlayerItem(url: channel.url)
        player.replaceCurrentItem(with: item)
        player.play()

        // Fehler (z. B. nicht unterstütztes Format oder abgelaufener Link) anzeigen.
        for await status in item.publisher(for: \.status).values where status == .failed {
            errorMessage = item.error?.localizedDescription
                ?? "Das Format wird möglicherweise nicht unterstützt."
        }
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
