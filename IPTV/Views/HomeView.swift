import SwiftUI

/// Startbildschirm: Kacheln für Live TV, Filme und Serien, darunter
/// „Weiterschauen“, „Zuletzt gesehen“ und „Favoriten“.
struct HomeView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @Binding var selection: AppTab

    @State private var showsSettings = false
    @State private var showsAddPlaylist = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 28) {
                VStack(spacing: 16) {
                    tile(for: .live, isLarge: true)
                    HStack(spacing: 16) {
                        tile(for: .movie)
                        tile(for: .series)
                    }
                }

                if let error = store.errorMessage {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .font(.callout)
                        .foregroundStyle(.red)
                        .padding(14)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .glassEffect(.regular.tint(.red.opacity(0.1)), in: .rect(cornerRadius: 18))
                }

                ContinueWatchingRow()
                RecentChannelsRow()
                FavoritesRow()
            }
            .padding()
            .frame(maxWidth: 900)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.backgroundGlow.ignoresSafeArea())
        .overlay {
            if store.isLoading && store.content.isEmpty {
                ProgressView("Playlist wird geladen …")
                    .padding(24)
                    .glassEffect(in: .rect(cornerRadius: 20))
            }
        }
        .navigationTitle(store.activePlaylist?.name ?? "IPTV")
        .toolbarTitleMenu {
            ForEach(store.playlists) { playlist in
                Button {
                    Task { await store.activate(playlist.id) }
                } label: {
                    Label(
                        playlist.name,
                        systemImage: playlist.id == store.activeID ? "checkmark" : playlist.source.symbol
                    )
                }
            }
            Divider()
            Button("Playlist hinzufügen", systemImage: "plus") { showsAddPlaylist = true }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Einstellungen", systemImage: "gearshape.fill") { showsSettings = true }
            }
        }
        .sheet(isPresented: $showsSettings) {
            NavigationStack { SettingsView() }
        }
        .sheet(isPresented: $showsAddPlaylist) {
            NavigationStack { AddPlaylistView(isSheet: true) }
        }
        .refreshable { await store.refresh() }
    }

    private func tile(for kind: ContentKind, isLarge: Bool = false) -> some View {
        let count = store.content.count(of: kind)
        return Button {
            selection = .section(kind)
        } label: {
            HomeTile(kind: kind, count: count, isLarge: isLarge)
        }
        .buttonStyle(.plain)
        .disabled(count == 0)
    }
}

private struct HomeTile: View {
    let kind: ContentKind
    let count: Int
    let isLarge: Bool

    var body: some View {
        VStack(alignment: .leading) {
            Image(systemName: kind.symbol)
                .font(.system(size: isLarge ? 44 : 34, weight: .semibold))
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.15), radius: 6, y: 3)

            Spacer(minLength: 16)

            Text(kind.title)
                .font(isLarge ? .largeTitle.bold() : .title2.bold())
                .foregroundStyle(.white)
            Text(count > 0 ? "\(count.formatted()) \(kind.unit)" : "Keine Inhalte")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white.opacity(0.85))
        }
        .frame(maxWidth: .infinity, minHeight: isLarge ? 180 : 140, alignment: .leading)
        .padding(20)
        .background(alignment: .bottomTrailing) {
            // Großes, angeschnittenes Symbol als dezente Hintergrundgrafik.
            Image(systemName: kind.symbol)
                .font(.system(size: isLarge ? 170 : 120))
                .foregroundStyle(.white.opacity(0.12))
                .offset(x: 30, y: 30)
        }
        .background(Theme.gradient)
        .clipShape(.rect(cornerRadius: 28))
        .shadow(color: Theme.orange.opacity(0.35), radius: 16, y: 8)
        .opacity(count > 0 ? 1 : 0.5)
        .accessibilityElement(children: .combine)
    }
}

/// Überschrift plus horizontale Leiste.
private struct HomeShelf<Content: View>: View {
    let title: String
    let symbol: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: symbol)
                .font(.title3.bold())
                .labelStyle(ShelfLabelStyle())
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 14) {
                    content
                }
                .scrollTargetLayout()
            }
            .scrollClipDisabled()
            .scrollTargetBehavior(.viewAligned)
        }
    }
}

private struct ShelfLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 8) {
            configuration.icon.foregroundStyle(Theme.orange)
            configuration.title
        }
    }
}

/// Angefangene Filme und Episoden.
private struct ContinueWatchingRow: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @Environment(\.playChannel) private var playChannel

    var body: some View {
        let entries = Array(userData.continueWatching.prefix(15))
        if !entries.isEmpty {
            HomeShelf(title: "Weiterschauen", symbol: "play.circle.fill") {
                ForEach(entries, id: \.channel.stableKey) { entry in
                    let channel = store.channel(forKey: entry.channel.stableKey) ?? entry.channel
                    Button {
                        playChannel(channel)
                    } label: {
                        PosterCard(
                            title: channel.name,
                            subtitle: "Noch \(Duration.seconds(entry.remaining).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated)))",
                            imageURL: channel.logoURL,
                            symbol: channel.kind.symbol,
                            progress: entry.fraction
                        )
                        .frame(width: 120)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

/// Zuletzt gesehene Live-Sender.
private struct RecentChannelsRow: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @Environment(\.playChannel) private var playChannel

    var body: some View {
        let recent = userData.history
            .filter { $0.channel.kind == .live }
            .prefix(15)
            .map { store.channel(forKey: $0.key) ?? $0.channel }
        if !recent.isEmpty {
            HomeShelf(title: "Zuletzt gesehen", symbol: "clock.fill") {
                ForEach(recent, id: \.stableKey) { channel in
                    Button {
                        playChannel(channel)
                    } label: {
                        ChannelTile(channel: channel)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

/// Favoriten aus allen Bereichen.
private struct FavoritesRow: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @Environment(\.playChannel) private var playChannel

    var body: some View {
        let channels = userData.favorites.compactMap { store.channel(forKey: $0) }
        let shows = userData.favorites.compactMap { store.content.series.show(forKey: $0) }

        if !channels.isEmpty || !shows.isEmpty {
            HomeShelf(title: "Favoriten", symbol: "heart.fill") {
                ForEach(channels.prefix(20), id: \.stableKey) { channel in
                    if channel.kind == .movie {
                        NavigationLink(value: channel) {
                            PosterCard(title: channel.name, subtitle: nil, imageURL: channel.logoURL, symbol: channel.kind.symbol)
                                .frame(width: 120)
                        }
                        .buttonStyle(.plain)
                    } else {
                        Button {
                            playChannel(channel)
                        } label: {
                            if channel.kind == .live {
                                ChannelTile(channel: channel)
                            } else {
                                PosterCard(title: channel.name, subtitle: nil, imageURL: channel.logoURL, symbol: channel.kind.symbol)
                                    .frame(width: 120)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                ForEach(shows.prefix(20)) { show in
                    NavigationLink(value: show) {
                        PosterCard(title: show.title, subtitle: "Serie", imageURL: show.posterURL, symbol: ContentKind.series.symbol)
                            .frame(width: 120)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

/// Kompakte Kachel für Live-Sender mit aktueller Sendung.
private struct ChannelTile: View {
    @Environment(PlaylistStore.self) private var store
    let channel: Channel

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ChannelBadge(channel: channel, size: 56)
            Text(channel.name)
                .font(.footnote.weight(.semibold))
                .lineLimit(2, reservesSpace: true)
            if let now = store.epg?.current(for: channel) {
                Text(now.title)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .frame(width: 120, alignment: .leading)
        .padding(12)
        .glassEffect(.regular.interactive(), in: .rect(cornerRadius: 20))
    }
}
