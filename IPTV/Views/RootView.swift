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
    @State private var playingChannel: Channel?

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
        .environment(\.playChannel, PlayChannelAction { playingChannel = $0 })
        .fullScreenCover(item: $playingChannel) { channel in
            PlayerScreen(channel: channel)
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
            CategoryDestinationView(route: route)
        }
        .navigationDestination(for: SeriesShow.self) { show in
            SeriesDetailView(show: show)
        }
    }
}

/// Erlaubt jeder Liste, einen Eintrag abzuspielen, ohne selbst einen Player zu verwalten.
struct PlayChannelAction {
    let handler: @MainActor (Channel) -> Void

    @MainActor
    func callAsFunction(_ channel: Channel) { handler(channel) }
}

extension EnvironmentValues {
    @Entry var playChannel = PlayChannelAction { _ in }
}
