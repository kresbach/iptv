import AVFoundation
import SwiftUI

@main
struct IPTVApp: App {
    @State private var store = PlaylistStore()
    @State private var userData = UserDataStore()

    init() {
        // Ton auch im Stumm-Modus, im Hintergrund und für Bild-in-Bild.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(userData)
                .tint(Theme.orange)
                .task {
                    await userData.restore()
                    await store.restore()
                }
        }
    }
}
