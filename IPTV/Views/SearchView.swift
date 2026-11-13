import SwiftUI

/// Globale Suche über alle Sender (Name und Kategorie).
struct SearchView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(\.playChannel) private var playChannel

    @State private var query = ""
    @State private var results: [Channel] = []

    private let resultLimit = 500

    var body: some View {
        List {
            if !results.isEmpty {
                Section {
                    ForEach(results.prefix(resultLimit)) { channel in
                        Button {
                            playChannel(channel)
                        } label: {
                            ChannelRow(channel: channel)
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text(results.count > resultLimit
                         ? "\(resultLimit) von \(results.count) Treffern"
                         : "\(results.count) Treffer")
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("Suche")
        .searchable(text: $query, prompt: "Sender oder Kategorie")
        .overlay {
            if query.isEmpty {
                ContentUnavailableView(
                    "Sender suchen",
                    systemImage: "magnifyingglass",
                    description: Text("Durchsuche \(store.channels.count) Sender nach Name oder Kategorie.")
                )
            } else if results.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
        // Kurze Verzögerung, damit bei großen Listen nicht bei jedem Tastendruck gefiltert wird.
        .task(id: query) {
            guard !query.isEmpty else {
                results = []
                return
            }
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            results = store.search(query)
        }
    }
}
