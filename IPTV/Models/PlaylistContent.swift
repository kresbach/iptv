import Foundation

/// Rohdaten einer geladenen Playlist; so wird sie auch zwischengespeichert.
struct PlaylistData: Codable, Sendable {
    /// Live-Sender, Filme und (bei M3U) Serien-Episoden.
    var channels: [Channel]
    /// Serien aus der Xtream-API. Bei M3U leer; dort werden Serien aus Episoden gebildet.
    var shows: [SeriesShow] = []
}

/// Die aktive Playlist, aufgeteilt in Live TV, Filme und Serien.
/// Wird einmal im Hintergrund berechnet und dann nur noch gelesen.
struct PlaylistContent: Sendable {
    let data: PlaylistData
    let live: ContentSection
    let movies: ContentSection
    let series: SeriesSection
    private let channelsByKey: [String: Channel]

    init(data: PlaylistData) {
        var live: [Channel] = []
        var movies: [Channel] = []
        var episodes: [Channel] = []
        var byKey: [String: Channel] = [:]

        for channel in data.channels {
            switch channel.kind {
            case .live: live.append(channel)
            case .movie: movies.append(channel)
            case .series: episodes.append(channel)
            }
            byKey[channel.stableKey] = byKey[channel.stableKey] ?? channel
        }

        self.data = data
        self.live = ContentSection(kind: .live, items: live)
        self.movies = ContentSection(kind: .movie, items: movies)
        self.series = SeriesSection(shows: data.shows + SeriesShow.shows(from: episodes))
        self.channelsByKey = byKey
    }

    static let empty = PlaylistContent(data: PlaylistData(channels: []))

    var isEmpty: Bool { data.channels.isEmpty && data.shows.isEmpty }

    func section(_ kind: ContentKind) -> ContentSection {
        kind == .movie ? movies : live
    }

    func count(of kind: ContentKind) -> Int {
        switch kind {
        case .live: live.items.count
        case .movie: movies.items.count
        case .series: series.shows.count
        }
    }

    func channel(forKey key: String) -> Channel? { channelsByKey[key] }
}

/// Filter innerhalb eines Bereichs.
enum CategoryFilter: Hashable, Sendable {
    case all
    case favorites
    case category(String)
}

/// Alle Einträge eines Bereichs (Live TV oder Filme) samt Kategorien.
struct ContentSection: Sendable {
    let kind: ContentKind
    let items: [Channel]
    let categories: [ChannelCategory]
    private let itemsByCategory: [String: [Channel]]

    init(kind: ContentKind, items: [Channel]) {
        let (categories, grouped) = groupByCategory(items, category: \.category)
        self.kind = kind
        self.items = items
        self.categories = categories
        self.itemsByCategory = grouped
    }

    func items(in category: String) -> [Channel] { itemsByCategory[category] ?? [] }
}

/// Alle Serien samt Kategorien.
struct SeriesSection: Sendable {
    let shows: [SeriesShow]
    let categories: [ChannelCategory]
    private let showsByCategory: [String: [SeriesShow]]
    private let showsByKey: [String: SeriesShow]

    init(shows: [SeriesShow]) {
        let (categories, grouped) = groupByCategory(shows, category: \.category)
        self.shows = shows
        self.categories = categories
        self.showsByCategory = grouped
        self.showsByKey = Dictionary(shows.map { ($0.favoriteKey, $0) }, uniquingKeysWith: { first, _ in first })
    }

    func shows(in category: String) -> [SeriesShow] { showsByCategory[category] ?? [] }
    func show(forKey key: String) -> SeriesShow? { showsByKey[key] }
}

/// Gruppiert Einträge nach Kategorie in Playlist-Reihenfolge, „Ohne Kategorie“ ans Ende.
private func groupByCategory<T>(
    _ items: [T],
    category: (T) -> String
) -> ([ChannelCategory], [String: [T]]) {
    var grouped: [String: [T]] = [:]
    var order: [String] = []
    for item in items {
        let key = category(item)
        if grouped[key] == nil { order.append(key) }
        grouped[key, default: []].append(item)
    }
    if let index = order.firstIndex(of: Channel.ungroupedTitle) {
        order.append(order.remove(at: index))
    }
    let categories = order.map { ChannelCategory(name: $0, count: grouped[$0]?.count ?? 0) }
    return (categories, grouped)
}

/// Eine Serie mit ihren Episoden. Bei M3U-Playlists aus einzelnen Einträgen
/// zusammengesetzt, bei Xtream werden die Episoden erst beim Öffnen geladen.
struct SeriesShow: Identifiable, Hashable, Codable, Sendable {
    struct Episode: Identifiable, Hashable, Codable, Sendable {
        let channel: Channel
        let season: Int?
        let number: Int?
        var title: String?

        var id: UUID { channel.id }

        var displayTitle: String {
            if let title, !title.isEmpty, number == nil { return title }
            guard let number else { return channel.name }
            if let title, !title.isEmpty, !title.localizedCaseInsensitiveContains(channel.name) {
                return "\(number). \(title)"
            }
            return "Episode \(number)"
        }
    }

    let title: String
    let category: String
    var posterURL: URL?
    var plot: String?
    var episodes: [Episode]
    /// Xtream: `series_id` zum Nachladen der Episoden.
    var xtreamSeriesID: Int?

    var id: String { favoriteKey }
    var favoriteKey: String { "series|\(category)|\(title)" }
    var needsEpisodeLoading: Bool { episodes.isEmpty && xtreamSeriesID != nil }

    /// Episoden nach Staffel gruppiert; Einträge ohne Staffelangabe kommen als Staffel 0.
    var seasons: [(season: Int, episodes: [Episode])] {
        Self.seasons(of: episodes)
    }

    static func seasons(of episodes: [Episode]) -> [(season: Int, episodes: [Episode])] {
        Dictionary(grouping: episodes) { $0.season ?? 0 }
            .sorted { $0.key < $1.key }
            .map { ($0.key, $0.value) }
    }

    static func == (lhs: SeriesShow, rhs: SeriesShow) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }

    /// Fasst Episoden anhand des Serientitels zusammen, in Playlist-Reihenfolge.
    static func shows(from items: [Channel]) -> [SeriesShow] {
        var order: [String] = []
        var grouped: [String: SeriesShow] = [:]

        for channel in items {
            let info = EpisodeParser.parse(channel.name)
            let title = info?.showTitle ?? channel.name
            let key = "\(channel.category)|\(title.lowercased())"
            let episode = Episode(channel: channel, season: info?.season, number: info?.episode)

            if grouped[key] == nil {
                order.append(key)
                grouped[key] = SeriesShow(title: title, category: channel.category, posterURL: nil, episodes: [])
            }
            grouped[key]?.episodes.append(episode)
            if grouped[key]?.posterURL == nil {
                grouped[key]?.posterURL = channel.logoURL
            }
        }

        return order.compactMap { key in
            guard var show = grouped[key] else { return nil }
            show.episodes.sort {
                ($0.season ?? 0, $0.number ?? 0) < ($1.season ?? 0, $1.number ?? 0)
            }
            return show
        }
    }
}
