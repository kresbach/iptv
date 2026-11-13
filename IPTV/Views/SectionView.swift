import SwiftUI

/// Einstieg in einen Bereich (Live TV, Filme oder Serien): Kategorien als Kacheln,
/// davor „Alle“ und – falls vorhanden – „Favoriten“.
struct SectionView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    let kind: ContentKind

    private let columns = [GridItem(.adaptive(minimum: 160), spacing: 14)]

    private var categories: [ChannelCategory] {
        kind == .series ? store.content.series.categories : store.section(kind).categories
    }

    private var favoriteCount: Int {
        switch kind {
        case .series: store.content.series.shows.count(where: { userData.isFavorite($0) })
        default: store.section(kind).items.count(where: { userData.isFavorite($0) })
        }
    }

    var body: some View {
        let total = store.content.count(of: kind)

        ScrollView {
            LazyVGrid(columns: columns, spacing: 14) {
                NavigationLink(value: CategoryRoute(kind: kind, filter: .all)) {
                    CategoryCard(title: "Alle", count: total, unit: kind.unit, symbol: kind.symbol)
                }
                .buttonStyle(.plain)

                if favoriteCount > 0 {
                    NavigationLink(value: CategoryRoute(kind: kind, filter: .favorites)) {
                        CategoryCard(title: "Favoriten", count: favoriteCount, unit: kind.unit, symbol: "heart.fill")
                    }
                    .buttonStyle(.plain)
                }

                ForEach(categories) { category in
                    NavigationLink(value: CategoryRoute(kind: kind, filter: .category(category.name))) {
                        CategoryCard(
                            title: category.name,
                            count: category.count,
                            unit: kind.unit,
                            symbol: CategoryIcon.symbol(for: category.name, fallback: kind.symbol)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        }
        .background(Theme.backgroundGlow.ignoresSafeArea())
        .overlay {
            if total == 0 && !store.isLoading {
                ContentUnavailableView(
                    "Keine \(kind.title)",
                    systemImage: kind.symbol,
                    description: Text("Die aktive Playlist enthält keine Einträge in diesem Bereich.")
                )
            }
        }
        .navigationTitle(kind.title)
        .navigationSubtitle("\(total.formatted()) \(kind.unit) in \(categories.count) Kategorien")
        .refreshable { await store.refresh() }
    }
}

/// Inhalt einer Kategorie je nach Bereich: Senderliste, Film-Poster oder Serien-Poster.
struct CategoryDestinationView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    let route: CategoryRoute

    var body: some View {
        switch route.kind {
        case .live:
            ChannelListView(title: route.title, channels: channels, showsCategory: !isSingleCategory)
        case .movie:
            MovieGridView(title: route.title, movies: channels)
        case .series:
            SeriesGridView(title: route.title, shows: shows)
        }
    }

    private var isSingleCategory: Bool {
        if case .category = route.filter { true } else { false }
    }

    private var channels: [Channel] {
        let section = store.section(route.kind)
        switch route.filter {
        case .all: return section.items
        case .favorites: return section.items.filter { userData.isFavorite($0) }
        case .category(let name): return section.items(in: name)
        }
    }

    private var shows: [SeriesShow] {
        let series = store.content.series
        switch route.filter {
        case .all: return series.shows
        case .favorites: return series.shows.filter { userData.isFavorite($0) }
        case .category(let name): return series.shows(in: name)
        }
    }
}

private struct CategoryCard: View {
    let title: String
    let count: Int
    let unit: String
    let symbol: String

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: symbol)
                .font(.title2.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 46, height: 46)
                .background(Theme.gradient, in: .circle)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                    .lineLimit(2, reservesSpace: true)
                    .multilineTextAlignment(.leading)
                Text("\(count.formatted()) \(unit)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .glassEffect(.regular.tint(Theme.orange.opacity(0.12)).interactive(), in: .rect(cornerRadius: 24))
        .accessibilityElement(children: .combine)
    }
}

/// Wählt anhand typischer Begriffe im Kategorienamen ein passendes SF Symbol.
enum CategoryIcon {
    private static let rules: [(keywords: [String], symbol: String)] = [
        (["sport", "fußball", "fussball", "football", "soccer", "bundesliga"], "sportscourt.fill"),
        (["news", "nachrichten", "info"], "newspaper.fill"),
        (["kids", "kinder", "children", "cartoon", "junior", "anim"], "teddybear.fill"),
        (["music", "musik", "radio"], "music.note"),
        (["doku", "docu", "documentary", "wissen", "nature", "natur"], "globe.europe.africa.fill"),
        (["action", "thriller", "krimi", "crime"], "flame.fill"),
        (["comedy", "komödie", "komoedie"], "face.smiling.fill"),
        (["horror"], "moon.stars.fill"),
        (["4k", "uhd"], "4k.tv.fill"),
        (["adult", "xxx", "18+"], "eye.slash.fill"),
    ]

    static func symbol(for name: String, fallback: String) -> String {
        if name == Channel.ungroupedTitle { return "questionmark.folder.fill" }
        let lowered = name.lowercased()
        return rules.first { rule in rule.keywords.contains { lowered.contains($0) } }?.symbol ?? fallback
    }
}
