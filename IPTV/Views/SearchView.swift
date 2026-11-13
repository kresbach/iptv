import SwiftUI

/// Globale Suche über Live TV, Filme und Serien (Name und Kategorie).
struct SearchView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(\.playChannel) private var playChannel

    @State private var query = ""
    @State private var live: [Channel] = []
    @State private var movies: [Channel] = []
    @State private var shows: [SeriesShow] = []

    /// Maximale Treffer pro Bereich, damit die Liste flüssig bleibt.
    private let limit = 150

    private var isEmpty: Bool { live.isEmpty && movies.isEmpty && shows.isEmpty }

    var body: some View {
        List {
            channelSection(.live, live)
            channelSection(.movie, movies)

            if !shows.isEmpty {
                Section {
                    ForEach(shows.prefix(limit)) { show in
                        NavigationLink(value: show) {
                            HStack(spacing: 14) {
                                PosterCard(title: nil, subtitle: nil, imageURL: show.posterURL, symbol: "rectangle.stack.fill")
                                    .frame(width: 40)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(show.title).font(.body.weight(.medium)).lineLimit(1)
                                    Text(show.category).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                }
                            }
                        }
                    }
                } header: {
                    Label(header(.series, count: shows.count), systemImage: ContentKind.series.symbol)
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Suche")
        .searchable(text: $query, prompt: "Sender, Filme, Serien")
        .overlay {
            if query.isEmpty {
                ContentUnavailableView(
                    "Alles durchsuchen",
                    systemImage: "magnifyingglass",
                    description: Text("Finde Sender, Filme und Serien nach Name oder Kategorie.")
                )
            } else if isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
        // Kurze Verzögerung, damit bei großen Listen nicht bei jedem Tastendruck gefiltert wird.
        .task(id: query) {
            guard !query.isEmpty else {
                live = []; movies = []; shows = []
                return
            }
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            live = store.search(query, in: store.content.live.items)
            movies = store.search(query, in: store.content.movies.items)
            shows = store.searchShows(query, in: store.content.series.shows)
        }
    }

    @ViewBuilder
    private func channelSection(_ kind: ContentKind, _ results: [Channel]) -> some View {
        if !results.isEmpty {
            Section {
                ForEach(results.prefix(limit)) { channel in
                    Button {
                        playChannel(channel)
                    } label: {
                        ChannelRow(channel: channel)
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Label(header(kind, count: results.count), systemImage: kind.symbol)
            }
        }
    }

    private func header(_ kind: ContentKind, count: Int) -> String {
        count > limit
            ? "\(kind.title) · \(limit) von \(count.formatted())"
            : "\(kind.title) · \(count.formatted())"
    }
}
