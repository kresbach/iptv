import Foundation

/// Testmodus für automatische UI-Tests (Startparameter `-UITestSampleData`):
/// beginnt mit leerem Speicher und lädt eine eingebaute Beispiel-Playlist mit
/// öffentlichen Test-Streams. Deine echten Daten werden dabei nicht verändert,
/// weil der Simulator eine eigene Installation hat.
enum UITestSupport {
    static let argument = "-UITestSampleData"

    /// Nur im Simulator: Auf einem echten Gerät würde das Zurücksetzen deine Daten löschen.
    static var isActive: Bool {
        #if targetEnvironment(simulator)
        ProcessInfo.processInfo.arguments.contains(argument)
        #else
        false
        #endif
    }

    /// Vor dem Laden: gespeicherte Daten und Einstellungen entfernen.
    static func resetStorage() {
        try? FileManager.default.removeItem(at: Storage.directory)
        if let domain = Bundle.main.bundleIdentifier {
            UserDefaults.standard.removePersistentDomain(forName: domain)
        }
    }

    /// Nach dem Laden: Beispiel-Playlist anlegen, falls noch keine da ist.
    @MainActor
    static func installSamplePlaylist(into store: PlaylistStore) async {
        guard store.playlists.isEmpty else { return }
        await store.addPlaylist(name: "Testliste", source: .file(originalName: "test.m3u"), fileData: Data(samplePlaylist.utf8))
    }

    static let samplePlaylist = """
    #EXTM3U
    #EXTINF:-1 tvg-id="apple.bipbop" group-title="Test-Sender",Apple BipBop
    https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8
    #EXTINF:-1 tvg-id="apple.bipbop.ts" group-title="Test-Sender",Apple BipBop TS
    https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8
    #EXTINF:-1 group-title="Test-Sender",Mux Test
    https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8
    #EXTINF:-1 tvg-logo="https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/BigBuckBunny.jpg" group-title="VOD | Animation",Big Buck Bunny
    https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4
    #EXTINF:-1 tvg-logo="https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/images/Sintel.jpg" group-title="VOD | Fantasy",Sintel
    https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4
    #EXTINF:-1 group-title="Serien | Kurzclips",Kurzclips S01 E01
    https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4
    #EXTINF:-1 group-title="Serien | Kurzclips",Kurzclips S01 E02
    https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4
    """
}
