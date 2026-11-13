import SwiftUI

struct RootView: View {
    @Environment(PlaylistStore.self) private var store

    var body: some View {
        switch store.phase {
        case .restoring:
            ProgressView()
                .controlSize(.large)
        case .empty:
            PlaylistSetupView()
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
                }
            }

            ForEach(ContentKind.allCases) { kind in
                Tab(kind.title, systemImage: kind.symbol, value: AppTab.section(kind)) {
                    NavigationStack {
                        SectionView(kind: kind)
                    }
                }
            }

            Tab(value: AppTab.search, role: .search) {
                NavigationStack {
                    SearchView()
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

/// Erlaubt jeder Liste, einen Eintrag abzuspielen, ohne selbst einen Player zu verwalten.
struct PlayChannelAction {
    let handler: @MainActor (Channel) -> Void

    @MainActor
    func callAsFunction(_ channel: Channel) { handler(channel) }
}

extension EnvironmentValues {
    @Entry var playChannel = PlayChannelAction { _ in }
}
