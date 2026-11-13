import SwiftUI

struct CategoriesView: View {
    @Environment(PlaylistStore.self) private var store

    private let columns = [GridItem(.adaptive(minimum: 160), spacing: 14)]

    var body: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 14) {
                ForEach(store.categories) { category in
                    NavigationLink(value: category) {
                        CategoryCard(category: category)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        }
        .background(Theme.backgroundGlow.ignoresSafeArea())
        .navigationTitle("Kategorien")
        .navigationSubtitle("\(store.channels.count) Sender in \(store.categories.count) Kategorien")
        .navigationDestination(for: ChannelCategory.self) { category in
            ChannelListView(
                title: category.name,
                channels: store.channels(in: category.name),
                showsCategory: false
            )
        }
        .refreshable { await store.refresh() }
    }
}

private struct CategoryCard: View {
    let category: ChannelCategory

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Image(systemName: CategoryIcon.symbol(for: category.name))
                .font(.title2.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 46, height: 46)
                .background(Theme.gradient, in: .circle)

            VStack(alignment: .leading, spacing: 2) {
                Text(category.name)
                    .font(.headline)
                    .lineLimit(2, reservesSpace: true)
                    .multilineTextAlignment(.leading)
                Text("\(category.count) Sender")
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
        (["movie", "film", "kino", "cinema", "vod"], "film.fill"),
        (["serie", "series", "show"], "tv.fill"),
        (["kids", "kinder", "children", "cartoon", "junior"], "teddybear.fill"),
        (["music", "musik", "radio"], "music.note"),
        (["doku", "docu", "documentary", "wissen", "nature", "natur"], "globe.europe.africa.fill"),
        (["adult", "xxx", "18+"], "eye.slash.fill"),
    ]

    static func symbol(for name: String) -> String {
        let lowered = name.lowercased()
        if name == Channel.ungroupedTitle { return "questionmark.folder.fill" }
        return rules.first { rule in rule.keywords.contains { lowered.contains($0) } }?.symbol
            ?? "play.rectangle.fill"
    }
}
