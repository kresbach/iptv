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

struct MainTabView: View {
    @State private var playingChannel: Channel?

    var body: some View {
        TabView {
            Tab("Kategorien", systemImage: "square.grid.2x2.fill") {
                NavigationStack {
                    CategoriesView()
                }
            }

            Tab("Alle Sender", systemImage: "play.tv.fill") {
                NavigationStack {
                    AllChannelsView()
                }
            }

            Tab("Einstellungen", systemImage: "gearshape.fill") {
                NavigationStack {
                    SettingsView()
                }
            }

            Tab(role: .search) {
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

/// Erlaubt jeder Liste, einen Sender abzuspielen, ohne selbst einen Player zu verwalten.
struct PlayChannelAction {
    let handler: @MainActor (Channel) -> Void

    @MainActor
    func callAsFunction(_ channel: Channel) { handler(channel) }
}

extension EnvironmentValues {
    @Entry var playChannel = PlayChannelAction { _ in }
}
