import Foundation

/// „Das könnte dir auch gefallen“: Empfehlungen von TMDB (falls eingerichtet), sonst ähnliche
/// Titel aus derselben Kategorie mit ähnlicher Bewertung und ähnlichem Jahr.
/// Es erscheinen nur Titel aus der eigenen Playlist.
enum Similar {
    static let limit = 20

    static func movies(for movie: Channel, in all: [Channel]) async -> [Channel] {
        var wanted: [String] = []
        if TMDB.isConfigured {
            wanted = await TMDBClient.shared.recommendedTitles(title: movie.name, year: movie.year.map(String.init), tv: false)
        }
        let recommended = wanted
        return await Task.detached(priority: .utility) {
            pick(
                own: movie, all: all, wanted: recommended,
                title: \.name, rating: \.rating, year: \.year, category: \.category,
                isSame: { $0.url == movie.url }
            )
        }.value
    }

    static func shows(for show: SeriesShow, in all: [SeriesShow]) async -> [SeriesShow] {
        var wanted: [String] = []
        if TMDB.isConfigured {
            wanted = await TMDBClient.shared.recommendedTitles(title: show.title, year: show.year.map(String.init), tv: true)
        }
        let recommended = wanted
        return await Task.detached(priority: .utility) {
            pick(
                own: show, all: all, wanted: recommended,
                title: \.title, rating: \.rating, year: \.year, category: \.category,
                isSame: { $0.id == show.id }
            )
        }.value
    }

    /// Erst die TMDB-Empfehlungen in ihrer Reihenfolge, dann mit Titeln derselben Kategorie
    /// auffüllen, deren Bewertung und Jahr am nächsten liegen.
    private static func pick<Item>(
        own: Item,
        all: [Item],
        wanted: [String],
        title: (Item) -> String,
        rating: (Item) -> Double?,
        year: (Item) -> Int?,
        category: (Item) -> String,
        isSame: (Item) -> Bool
    ) -> [Item] {
        var seen: Set<String> = [TMDB.titleKey(title(own))]
        var result: [Item] = []

        if !wanted.isEmpty {
            let wantedKeys = Set(wanted.map(TMDB.titleKey))
            var byKey: [String: Item] = [:]
            for item in all {
                let key = TMDB.titleKey(title(item))
                if wantedKeys.contains(key), byKey[key] == nil { byKey[key] = item }
            }
            for name in wanted {
                let key = TMDB.titleKey(name)
                if let item = byKey[key], seen.insert(key).inserted { result.append(item) }
                if result.count >= limit { return result }
            }
        }

        let ownCategory = category(own)
        let ownRating = rating(own) ?? 6
        let ownYear = year(own)
        let candidates = all
            .filter { category($0) == ownCategory && !isSame($0) }
            .prefix(3000)
        let scored = candidates.map { item -> (item: Item, score: Double) in
            let ratingGap = abs((rating(item) ?? 5) - ownRating)
            let yearGap = ownYear.map { target in Double(abs((year(item) ?? target) - target)) / 10 } ?? 0
            return (item, ratingGap + yearGap)
        }
        for entry in scored.sorted(by: { $0.score < $1.score }) {
            guard result.count < limit else { break }
            if seen.insert(TMDB.titleKey(title(entry.item))).inserted { result.append(entry.item) }
        }
        return result
    }
}
