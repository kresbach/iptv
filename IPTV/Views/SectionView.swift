import SwiftUI

/// Ziel einer Kategorie-Kachel. `category == nil` steht für „Alle“.
struct CategoryRoute: Hashable {
    let kind: ContentKind
    let category: String?
}

/// Einstieg in einen Bereich (Live TV, Filme oder Serien): Kategorien als Kacheln.
struct SectionView: View {
    @Environment(PlaylistStore.self) private var store
    let kind: ContentKind

    private let columns = [GridItem(.adaptive(minimum: 160), spacing: 14)]

    var body: some View {
        let section = store.section(kind)

        ScrollView {
            LazyVGrid(columns: columns, spacing: 14) {
                NavigationLink(value: CategoryRoute(kind: kind, category: nil)) {
                    CategoryCard(title: "Alle", count: section.items.count, unit: kind.unit, symbol: kind.symbol)
                }
                .buttonStyle(.plain)

                ForEach(section.categories) { category in
                    NavigationLink(value: CategoryRoute(kind: kind, category: category.name)) {
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
            if section.isEmpty {
                ContentUnavailableView(
                    "Keine \(kind.title)",
                    systemImage: kind.symbol,
                    description: Text("Deine Playlist enthält keine Einträge in diesem Bereich.")
                )
            }
        }
        .navigationTitle(kind.title)
        .navigationSubtitle("\(section.items.count.formatted()) \(kind.unit) in \(section.categories.count) Kategorien")
        .navigationDestination(for: CategoryRoute.self) { route in
            destination(for: route)
        }
        .navigationDestination(for: SeriesShow.self) { show in
            SeriesDetailView(show: show)
        }
        .refreshable { await store.refresh() }
    }

    @ViewBuilder
    private func destination(for route: CategoryRoute) -> some View {
        let items = store.section(route.kind).items(in: route.category)
        let title = route.category ?? "Alle \(route.kind.title)"

        switch route.kind {
        case .live:
            ChannelListView(title: title, channels: items, showsCategory: route.category == nil)
        case .movie:
            MovieGridView(title: title, movies: items)
        case .series:
            SeriesGridView(title: title, episodes: items)
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
