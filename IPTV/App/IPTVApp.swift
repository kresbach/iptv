import AVFoundation
import SwiftUI

@main
struct IPTVApp: App {
    @State private var store = PlaylistStore()

    init() {
        // Ton auch im Stumm-Modus, im Hintergrund und für Bild-in-Bild.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .tint(Theme.orange)
                .task { await store.restore() }
        }
    }
}
