import Foundation
import Observation

/// „Trends“ auf der Startseite: was bei Netflix, Prime Video, Disney+ & Co. gerade viel geschaut wird.
///
/// Quelle sind die Beliebtheitslisten von TMDB (je Dienst für Deutschland). Gezeigt werden nur
/// Titel, die es auch in der eigenen Playlist gibt. Ohne TMDB-Schlüssel oder bei wenigen Treffern
/// werden die Kategorien des Anbieters genutzt (viele heißen „NETFLIX“, „AMAZON PRIME“ …).
@MainActor
@Observable
final class TrendsStore {
    static let shared = TrendsStore()

    struct Provider: Sendable {
        let id: String
        let name: String
        /// Anbieter-Nummer bei TMDB („watch provider“, Region DE).
        let tmdbID: Int
        /// Erkennung in Kategorienamen des IPTV-Anbieters (regulärer Ausdruck, kleingeschrieben).
        let pattern: String
    }

    nonisolated static let providers: [Provider] = [
        Provider(id: "netflix", name: "Netflix", tmdbID: 8, pattern: "netflix"),
        Provider(id: "prime", name: "Prime Video", tmdbID: 9, pattern: #"amazon|prime\s*video|\bprime\b"#),
        Provider(id: "disney", name: "Disney+", tmdbID: 337, pattern: "disney"),
        Provider(id: "apple", name: "Apple TV+", tmdbID: 350, pattern: #"apple\s*tv"#),
        Provider(id: "paramount", name: "Paramount+", tmdbID: 531, pattern: "paramount"),
        Provider(id: "sky", name: "Sky / WOW", tmdbID: 30, pattern: #"\bsky\b|\bwow\b"#),
        Provider(id: "rtl", name: "RTL+", tmdbID: 298, pattern: #"rtl\s*\+|rtl\s*plus|tv\s*now"#),
        Provider(id: "joyn", name: "Joyn", tmdbID: 304, pattern: "joyn"),
        Provider(id: "magenta", name: "MagentaTV", tmdbID: 178, pattern: "magenta"),
    ]

    /// Ein Eintrag einer Trend-Reihe: Film oder Serie aus der eigenen Playlist.
    enum Item: Identifiable, Sendable {
        case movie(Channel, poster: URL?)
        case show(SeriesShow, poster: URL?)

        var id: String {
            switch self {
            case .movie(let channel, _): channel.stableKey
            case .show(let show, _): show.favoriteKey
            }
        }
    }

    struct Row: Identifiable, Sendable {
        let id: String
        let title: String
        let items: [Item]
        let fromTMDB: Bool
    }

    struct Cache: Codable, Sendable {
        var fetchedAt: Date = .distantPast
        var lists: [String: [TMDB.Entry]] = [:]
    }

    private(set) var rows: [Row] = []
    private(set) var isLoading = false
    private(set) var lastError: String?
    private(set) var fetchedAt: Date?

    @ObservationIgnored private var cache = Cache()
    @ObservationIgnored private var didLoadCache = false
    @ObservationIgnored private var builtFor: String?

    private static let fileName = "trends.json"
    private static let maxAge: TimeInterval = 12 * 3600
    nonisolated private static let rowLimit = 30

    /// Reihen für die aktuelle Playlist berechnen und die Listen bei Bedarf neu laden.
    /// `identity` ändert sich, wenn sich die Playlist ändert.
    func update(identity: String) async {
        if !didLoadCache {
            didLoadCache = true
            cache = await Storage.read(Cache.self, from: Self.fileName) ?? Cache()
            fetchedAt = cache.fetchedAt == .distantPast ? nil : cache.fetchedAt
        }
        if builtFor != identity {
            builtFor = identity
            await rebuild()
        }
        // Erst nach dem Start nachladen, damit die Startseite schnell steht.
        try? await Task.sleep(for: .seconds(10))
        guard !Task.isCancelled else { return }
        await refresh(force: false)
    }

    /// Beliebtheitslisten bei TMDB neu laden (höchstens alle 12 Stunden, außer `force`).
    func refresh(force: Bool) async {
        guard TMDB.isConfigured, !isLoading else { return }
        if !force, Date.now.timeIntervalSince(cache.fetchedAt) < Self.maxAge { return }
        isLoading = true
        lastError = nil
        defer { isLoading = false }

        do {
            var lists: [String: [TMDB.Entry]] = [:]
            var trending: [TMDB.Entry] = []
            for page in 1...2 {
                let data = try await TMDB.get("trending/all/week", query: [URLQueryItem(name: "page", value: String(page))])
                trending += TMDB.parseEntries(data, tv: nil)
            }
            lists["trending"] = trending

            for provider in Self.providers {
                let query = [
                    URLQueryItem(name: "watch_region", value: "DE"),
                    URLQueryItem(name: "with_watch_providers", value: String(provider.tmdbID)),
                    URLQueryItem(name: "with_watch_monetization_types", value: "flatrate"),
                    URLQueryItem(name: "sort_by", value: "popularity.desc"),
                ]
                let movieData = try await TMDB.get("discover/movie", query: query)
                let tvData = try await TMDB.get("discover/tv", query: query)
                let entries = TMDB.parseEntries(movieData, tv: false) + TMDB.parseEntries(tvData, tv: true)
                lists[provider.id] = entries.sorted { $0.popularity > $1.popularity }
            }

            cache = Cache(fetchedAt: .now, lists: lists)
            fetchedAt = cache.fetchedAt
            await Storage.write(cache, to: Self.fileName)
            await rebuild()
        } catch {
            lastError = error.localizedDescription
        }
    }

    private func rebuild() async {
        let content = PlaylistStore.shared.content
        let cache = cache
        rows = await Task.detached(priority: .utility) {
            Self.build(content: content, cache: cache)
        }.value
    }

    // MARK: - Reihen bauen (ohne Netzwerk)

    nonisolated static func build(content: PlaylistContent, cache: Cache) -> [Row] {
        let movies = content.movies
        let series = content.series
        guard !movies.items.isEmpty || !series.shows.isEmpty else { return [] }

        // Titel-Index nur aufbauen, wenn es TMDB-Listen gibt (teuer bei großen Playlists).
        var moviesByTitle: [String: Channel] = [:]
        var showsByTitle: [String: SeriesShow] = [:]
        if !cache.lists.isEmpty {
            for movie in movies.items {
                let key = TMDB.titleKey(movie.name)
                if !key.isEmpty, moviesByTitle[key] == nil { moviesByTitle[key] = movie }
            }
            for show in series.shows {
                let key = TMDB.titleKey(show.title)
                if !key.isEmpty, showsByTitle[key] == nil { showsByTitle[key] = show }
            }
        }

        func match(_ entries: [TMDB.Entry]) -> [Item] {
            var seen = Set<String>()
            var result: [Item] = []
            for entry in entries {
                let keys = [entry.title, entry.original].compactMap { $0 }.map(TMDB.titleKey).filter { !$0.isEmpty }
                let item: Item?
                if entry.isTV {
                    item = keys.lazy.compactMap { showsByTitle[$0] }.first.map { Item.show($0, poster: $0.posterURL ?? entry.posterURL) }
                } else {
                    item = keys.lazy.compactMap { moviesByTitle[$0] }.first.map { Item.movie($0, poster: $0.logoURL ?? entry.posterURL) }
                }
                guard let item, seen.insert(item.id).inserted else { continue }
                result.append(item)
                if result.count >= rowLimit { break }
            }
            return result
        }

        func fromCategories(_ provider: Provider) -> [Item] {
            func fits(_ name: String) -> Bool {
                name.lowercased().range(of: provider.pattern, options: .regularExpression) != nil
            }
            let movieItems = movies.categories.filter { fits($0.name) }
                .flatMap { movies.items(in: $0.name) }
                .sorted { ($0.added ?? .distantPast) > ($1.added ?? .distantPast) }
            let showItems = series.categories.filter { fits($0.name) }
                .flatMap { series.shows(in: $0.name) }
                .sorted { ($0.added ?? .distantPast) > ($1.added ?? .distantPast) }
            // Filme und Serien abwechselnd.
            var result: [Item] = []
            var movieIterator = movieItems.makeIterator()
            var showIterator = showItems.makeIterator()
            while result.count < rowLimit {
                let movie = movieIterator.next()
                let show = showIterator.next()
                if movie == nil && show == nil { break }
                if let movie { result.append(.movie(movie, poster: movie.logoURL)) }
                if let show, result.count < rowLimit { result.append(.show(show, poster: show.posterURL)) }
            }
            return result
        }

        var rows: [Row] = []
        if let trending = cache.lists["trending"] {
            let items = match(trending)
            if !items.isEmpty {
                rows.append(Row(id: "trending", title: "Trends in Deutschland", items: items, fromTMDB: true))
            }
        }
        for provider in providers {
            let tmdb = cache.lists[provider.id].map(match) ?? []
            var items = tmdb
            if tmdb.count < 6 {
                // Zu wenige Treffer: mit den Kategorien des Anbieters auffüllen.
                var seen = Set(tmdb.map(\.id))
                items += fromCategories(provider).filter { seen.insert($0.id).inserted }
            }
            items = Array(items.prefix(rowLimit))
            if !items.isEmpty {
                rows.append(Row(id: provider.id, title: provider.name, items: items, fromTMDB: !tmdb.isEmpty))
            }
        }
        return rows
    }
}
