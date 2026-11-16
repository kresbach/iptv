import SwiftUI

struct RootView: View {
    @Environment(PlaylistStore.self) private var store

    var body: some View {
        switch store.phase {
        case .restoring:
            ProgressView()
                .controlSize(.large)
        case .empty:
            NavigationStack {
                AddPlaylistView()
            }
        case .ready:
            MainTabView()
        }
    }
}

enum AppTab: Hashable {
    case home
    case section(ContentKind)
    case search
}

struct MainTabView: View {
    @State private var selection: AppTab = .home
    @Bindable private var playback = PlaybackCoordinator.shared

    var body: some View {
        TabView(selection: $selection) {
            Tab("Start", systemImage: "house.fill", value: AppTab.home) {
                NavigationStack {
                    HomeView(selection: $selection)
                        .withAppDestinations()
                }
            }

            ForEach(ContentKind.allCases) { kind in
                Tab(kind.title, systemImage: kind.symbol, value: AppTab.section(kind)) {
                    NavigationStack {
                        SectionView(kind: kind)
                            .withAppDestinations()
                    }
                }
            }

            Tab(value: AppTab.search, role: .search) {
                NavigationStack {
                    SearchView()
                        .withAppDestinations()
                }
            }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .fullScreenCover(item: $playback.request) { request in
            PlayerScreen(request: request)
                .appEnvironment()
        }
    }
}

/// Ziel einer Kategorie-Kachel.
struct CategoryRoute: Hashable {
    let kind: ContentKind
    let filter: CategoryFilter

    var title: String {
        switch filter {
        case .all: "Alle \(kind.title)"
        case .favorites: "Favoriten"
        case .category(let name): name
        }
    }
}

extension View {
    /// Navigationsziele, die in allen Tabs gebraucht werden.
    func withAppDestinations() -> some View {
        navigationDestination(for: CategoryRoute.self) { route in
            CategoryDestinationView(route: route).appEnvironment()
        }
        .navigationDestination(for: SeriesShow.self) { show in
            SeriesDetailView(show: show).appEnvironment()
        }
        .navigationDestination(for: Channel.self) { movie in
            MovieDetailView(movie: movie).appEnvironment()
        }
    }
}

/// Erlaubt jeder Liste, einen Eintrag abzuspielen, ohne selbst einen Player zu verwalten.
struct PlayChannelAction {
    let handler: @MainActor (Channel, [Channel]) -> Void

    /// - Parameter queue: Liste, aus der gestartet wurde – zum Umschalten im Player
    ///   (Live TV) bzw. für die nächste Episode (Serien).
    @MainActor
    func callAsFunction(_ channel: Channel, queue: [Channel] = []) { handler(channel, queue) }
}

/// Was der Player abspielen soll, samt Nachbarn zum Umschalten.
struct PlaybackRequest: Identifiable {
    let id = UUID()
    let channel: Channel
    let queue: [Channel]
}

/// Hält die aktuelle Wiedergabe-Anfrage. So lässt sich der Player aus jeder Ansicht
/// starten, auch aus Blättern und eigenen Fenstern (iPad, Mac).
@MainActor
@Observable
final class PlaybackCoordinator {
    static let shared = PlaybackCoordinator()
    var request: PlaybackRequest?
}

extension EnvironmentValues {
    @Entry var playChannel = PlayChannelAction { channel, queue in
        PlaybackCoordinator.shared.request = PlaybackRequest(channel: channel, queue: queue)
    }
}

extension View {
    /// Gibt die App-Daten ausdrücklich weiter. Blätter, Vollbild-Ansichten und
    /// Navigationsziele erben sie nicht auf jedem Gerät zuverlässig (vor allem auf dem Mac).
    @MainActor
    func appEnvironment() -> some View {
        self
            .environment(PlaylistStore.shared)
            .environment(UserDataStore.shared)
            .environment(SyncService.shared)
            .environment(DownloadManager.shared)
            .environment(CategoryPreferences.shared)
            .tint(Theme.orange)
    }
}
