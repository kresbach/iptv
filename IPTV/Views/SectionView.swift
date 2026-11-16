import SwiftUI

/// Einstieg in einen Bereich (Live TV, Filme oder Serien): Kategorien als Liste,
/// davor „Alle“ und – falls vorhanden – „Favoriten“. Mit Suchfeld zum Filtern.
/// Kategorien lassen sich sortieren, ausblenden und per PIN sperren.
struct SectionView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @Environment(CategoryPreferences.self) private var prefs
    let kind: ContentKind

    @State private var query = ""
    @State private var showsEditor = false
    @State private var asksPIN = false
    @State private var opensEditorAfterPIN = false

    private var allCategories: [ChannelCategory] {
        kind == .series ? store.content.series.categories : store.section(kind).categories
    }

    private var favoriteCount: Int {
        switch kind {
        case .series: store.content.series.shows.count(where: { userData.isFavorite($0) && prefs.isVisible($0) })
        default: store.section(kind).items.count(where: { userData.isFavorite($0) && prefs.isVisible($0) })
        }
    }

    var body: some View {
        let total = store.content.count(of: kind)
        let categories = prefs.arranged(allCategories, kind: kind)
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        let visible = trimmed.isEmpty ? categories : categories.filter { $0.name.localizedStandardContains(trimmed) }

        List {
            if trimmed.isEmpty {
                Section {
                    NavigationLink(value: CategoryRoute(kind: kind, filter: .all)) {
                        CategoryRow(title: "Alle", count: nil, unit: kind.unit, symbol: kind.symbol)
                    }
                    if favoriteCount > 0 {
                        NavigationLink(value: CategoryRoute(kind: kind, filter: .favorites)) {
                            CategoryRow(title: "Favoriten", count: favoriteCount, unit: kind.unit, symbol: "heart.fill")
                        }
                    }
                    if kind == .live, store.epg != nil {
                        NavigationLink {
                            TVGuideView().appEnvironment()
                        } label: {
                            CategoryRow(title: "TV-Programm", count: nil, unit: "", symbol: "calendar")
                        }
                    }
                }
            }

            Section {
                ForEach(visible) { category in
                    if prefs.isBlocked(kind, category.name) {
                        Button {
                            opensEditorAfterPIN = false
                            asksPIN = true
                        } label: {
                            CategoryRow(title: category.name, count: nil, unit: kind.unit, symbol: "lock.fill")
                        }
                        .buttonStyle(.plain)
                    } else {
                        NavigationLink(value: CategoryRoute(kind: kind, filter: .category(category.name))) {
                            CategoryRow(
                                title: category.name,
                                count: category.count,
                                unit: kind.unit,
                                symbol: CategoryIcon.symbol(for: category.name, fallback: kind.symbol)
                            )
                        }
                    }
                }
            } header: {
                if !categories.isEmpty {
                    Text("\(categories.count) Kategorien")
                }
            }
        }
        .listStyle(.insetGrouped)
        .searchable(text: $query, prompt: "Kategorie suchen")
        .overlay {
            if total == 0 && !store.isLoading {
                ContentUnavailableView(
                    "Keine \(kind.title)",
                    systemImage: kind.symbol,
                    description: Text("Die aktive Playlist enthält keine Einträge in diesem Bereich.")
                )
            } else if visible.isEmpty && !trimmed.isEmpty {
                ContentUnavailableView.search(text: trimmed)
            }
        }
        .navigationTitle(kind.title)
        .navigationSubtitle("\(total.formatted()) \(kind.unit)")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Bearbeiten", systemImage: "slider.horizontal.3") {
                    // Mit aktiver Kindersicherung nur nach PIN-Eingabe.
                    if prefs.hasPIN && !prefs.isUnlocked {
                        opensEditorAfterPIN = true
                        asksPIN = true
                    } else {
                        showsEditor = true
                    }
                }
            }
        }
        .sheet(isPresented: $showsEditor) {
            NavigationStack {
                CategoryEditView(kind: kind, categories: allCategories)
            }
            .appEnvironment()
        }
        .pinPrompt(isPresented: $asksPIN) {
            if opensEditorAfterPIN { showsEditor = true }
        }
        .refreshable { await store.refresh() }
    }
}

/// Zeile einer Kategorie: Symbol, Name und Anzahl.
private struct CategoryRow: View {
    let title: String
    let count: Int?
    let unit: String
    let symbol: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(.white)
                .frame(width: 34, height: 34)
                .background(Theme.gradient, in: .rect(cornerRadius: 9))

            Text(title)
                .font(.body.weight(.medium))
                .lineLimit(2)

            Spacer(minLength: 8)

            if let count {
                Text(count.formatted())
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
        .contentShape(.rect)
        .accessibilityElement(children: .combine)
    }
}

/// Inhalt einer Kategorie je nach Bereich: Senderliste, Film-Poster oder Serien-Poster.
struct CategoryDestinationView: View {
    @Environment(PlaylistStore.self) private var store
    @Environment(UserDataStore.self) private var userData
    @Environment(CategoryPreferences.self) private var prefs
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
        case .all: return prefs.filter(section.items)
        case .favorites: return prefs.filter(section.items.filter { userData.isFavorite($0) })
        case .category(let name): return prefs.isBlocked(route.kind, name) ? [] : section.items(in: name)
        }
    }

    private var shows: [SeriesShow] {
        let series = store.content.series
        switch route.filter {
        case .all: return prefs.filter(series.shows)
        case .favorites: return prefs.filter(series.shows.filter { userData.isFavorite($0) })
        case .category(let name): return prefs.isBlocked(.series, name) ? [] : series.shows(in: name)
        }
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
