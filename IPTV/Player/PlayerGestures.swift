import AVFoundation
import MediaPlayer
import SwiftUI

/// Gesten über dem Video:
/// - Tippen: Steuerung ein-/ausblenden
/// - Doppeltipp links/rechts (Filme): 10 Sekunden zurück/vor
/// - Hoch/runter wischen: links Helligkeit, rechts Lautstärke
/// - Seitlich wischen (Live TV): vorheriger/nächster Sender
struct PlayerGestureLayer: View {
    let allowsSeek: Bool
    let allowsZap: Bool
    let onTap: () -> Void
    let onSeek: (Double) -> Void
    /// +1 = nächster, -1 = vorheriger Sender
    let onZap: (Int) -> Void

    private enum Mode { case brightness, volume, horizontal }

    private struct HUD: Equatable {
        let symbol: String
        /// 0 … 1 für den Balken, `nil` ohne Balken.
        let level: Double?
    }

    @State private var mode: Mode?
    @State private var startLevel = 0.0
    @State private var hud: HUD?
    @State private var hudTask: Task<Void, Never>?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                tapLayer(width: geometry.size.width)
                    .gesture(dragGesture(size: geometry.size))

                // Unsichtbarer Systemregler: ermöglicht das Ändern der Gerätelautstärke.
                SystemVolumeView()
                    .frame(width: 1, height: 1)
                    .opacity(0.01)
                    .allowsHitTesting(false)

                if let hud {
                    hudView(hud)
                        .transition(.opacity)
                        .allowsHitTesting(false)
                }
            }
            .animation(.easeOut(duration: 0.15), value: hud)
        }
    }

    @ViewBuilder
    private func tapLayer(width: CGFloat) -> some View {
        if allowsSeek {
            Color.clear
                .contentShape(.rect)
                .onTapGesture(count: 2, coordinateSpace: .local) { location in
                    let forward = location.x > width / 2
                    onSeek(forward ? 10 : -10)
                    show(HUD(symbol: forward ? "goforward.10" : "gobackward.10", level: nil))
                }
                .onTapGesture { onTap() }
        } else {
            // Ohne Doppeltipp reagiert der einfache Tipp sofort.
            Color.clear
                .contentShape(.rect)
                .onTapGesture { onTap() }
        }
    }

    private func dragGesture(size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 14)
            .onChanged { value in
                if mode == nil {
                    if abs(value.translation.height) > abs(value.translation.width) {
                        let isLeft = value.startLocation.x < size.width / 2
                        mode = isLeft ? .brightness : .volume
                        startLevel = isLeft ? Double(UIScreen.main.brightness) : SystemVolume.shared.level
                    } else {
                        mode = .horizontal
                    }
                }
                // Ganze Bildschirmhöhe = ganzer Regelbereich.
                let delta = Double(-value.translation.height / max(size.height, 1)) * 1.2
                let level = min(max(startLevel + delta, 0), 1)
                switch mode {
                case .brightness:
                    UIScreen.main.brightness = CGFloat(level)
                    show(HUD(symbol: level > 0.5 ? "sun.max.fill" : "sun.min.fill", level: level), autoHide: false)
                case .volume:
                    SystemVolume.shared.set(level)
                    show(HUD(symbol: level == 0 ? "speaker.slash.fill" : "speaker.wave.2.fill", level: level), autoHide: false)
                case .horizontal, nil:
                    break
                }
            }
            .onEnded { value in
                if mode == .horizontal, allowsZap, abs(value.translation.width) > 80 {
                    let direction = value.translation.width < 0 ? 1 : -1
                    onZap(direction)
                    show(HUD(symbol: direction > 0 ? "forward.end.fill" : "backward.end.fill", level: nil))
                } else if hud != nil {
                    show(hud, autoHide: true)
                }
                mode = nil
            }
    }

    private func show(_ newHUD: HUD?, autoHide: Bool = true) {
        hud = newHUD
        hudTask?.cancel()
        guard autoHide else { return }
        hudTask = Task {
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled else { return }
            hud = nil
        }
    }

    private func hudView(_ hud: HUD) -> some View {
        VStack(spacing: 10) {
            Image(systemName: hud.symbol)
                .font(.title)
            if let level = hud.level {
                ProgressView(value: level)
                    .tint(.white)
                    .frame(width: 110)
            }
        }
        .foregroundStyle(.white)
        .padding(18)
        .background(.black.opacity(0.6), in: .rect(cornerRadius: 18))
    }
}

/// Zugriff auf die Gerätelautstärke über den Systemregler.
@MainActor
final class SystemVolume {
    static let shared = SystemVolume()
    weak var slider: UISlider?

    var level: Double { Double(AVAudioSession.sharedInstance().outputVolume) }

    func set(_ level: Double) {
        slider?.value = Float(level)
    }
}

private struct SystemVolumeView: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let view = MPVolumeView(frame: .zero)
        SystemVolume.shared.slider = view.subviews.compactMap { $0 as? UISlider }.first
        return view
    }

    func updateUIView(_ view: MPVolumeView, context: Context) {
        if SystemVolume.shared.slider == nil {
            SystemVolume.shared.slider = view.subviews.compactMap { $0 as? UISlider }.first
        }
    }
}
