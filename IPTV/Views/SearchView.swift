import SwiftUI

/// Globale Suche über Live TV, Filme und Serien (Name und Kategorie).
struct SearchView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(\.playChannel) private var playChannel

    @State private var query = ""
    @State private var results: [ContentKind: [Channel]] = [:]

    /// Maximale Treffer pro Bereich, damit die Liste flüssig bleibt.
    private let limit = 200

    private var totalCount: Int { results.values.reduce(0) { $0 + $1.count } }

    var body: some View {
        List {
            ForEach(ContentKind.allCases) { kind in
                if let matches = results[kind], !matches.isEmpty {
                    Section {
                        ForEach(matches.prefix(limit)) { channel in
                            Button {
                                playChannel(channel)
                            } label: {
                                ChannelRow(channel: channel)
                            }
                            .buttonStyle(.plain)
                        }
                    } header: {
                        Label(header(for: kind, count: matches.count), systemImage: kind.symbol)
                    }
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
            } else if totalCount == 0 {
                ContentUnavailableView.search(text: query)
            }
        }
        // Kurze Verzögerung, damit bei großen Listen nicht bei jedem Tastendruck gefiltert wird.
        .task(id: query) {
            guard !query.isEmpty else {
                results = [:]
                return
            }
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }

            var grouped: [ContentKind: [Channel]] = [:]
            for kind in ContentKind.allCases {
                grouped[kind] = store.search(query, in: store.section(kind).items)
            }
            results = grouped
        }
    }

    private func header(for kind: ContentKind, count: Int) -> String {
        count > limit
            ? "\(kind.title) · \(limit) von \(count.formatted())"
            : "\(kind.title) · \(count.formatted())"
    }
}
