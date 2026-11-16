import AVFoundation
import SwiftUI

@main
struct IPTVApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var store = PlaylistStore.shared
    @State private var downloads = DownloadManager.shared
    @State private var categoryPreferences = CategoryPreferences.shared
    @State private var userData = UserDataStore.shared
    @State private var sync = SyncService.shared
    @Environment(\.scenePhase) private var scenePhase

    init() {
        // Ton auch im Stumm-Modus, im Hintergrund und für Bild-in-Bild.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .appEnvironment()
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
                categoryPreferences.lock()
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
