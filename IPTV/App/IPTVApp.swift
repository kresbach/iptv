import AVFoundation
import SwiftUI

@main
struct IPTVApp: App {
    @State private var store = PlaylistStore()
    @State private var userData = UserDataStore()
    @State private var sync = SyncService()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Ton auch im Stumm-Modus, im Hintergrund und für Bild-in-Bild.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(store)
                .environment(userData)
                .environment(sync)
                .tint(Theme.orange)
                .task {
                    await userData.restore()
                    await store.restore()
                    // Nach lokalen Änderungen (Favoriten, Fortschritt) gebündelt abgleichen.
                    userData.onChange = { [sync, store, userData] in
                        sync.scheduleSync(store: store, userData: userData)
                    }
                    await sync.sync(store: store, userData: userData)
                }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                Task { await sync.sync(store: store, userData: userData) }
            case .active where store.phase != .restoring:
                store.refreshIfStale()
                Task { await sync.sync(store: store, userData: userData) }
            default:
                break
            }
        }
    }
}
