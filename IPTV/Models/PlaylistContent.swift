import Foundation

/// Die geladene Playlist, aufgeteilt in Live TV, Filme und Serien.
/// Wird einmal im Hintergrund berechnet und dann nur noch gelesen.
struct PlaylistContent: Sendable {
    let channels: [Channel]
    private let sections: [ContentKind: ContentSection]

    init(channels: [Channel]) {
        var buckets: [ContentKind: [Channel]] = [:]
        for channel in channels {
            buckets[channel.kind, default: []].append(channel)
        }
        self.channels = channels
        self.sections = Dictionary(uniqueKeysWithValues: ContentKind.allCases.map {
            ($0, ContentSection(kind: $0, items: buckets[$0] ?? []))
        })
    }

    static let empty = PlaylistContent(channels: [])

    func section(_ kind: ContentKind) -> ContentSection {
        sections[kind] ?? ContentSection(kind: kind, items: [])
    }
}

/// Alle Einträge eines Bereichs samt Kategorien (aus `group-title`).
struct ContentSection: Sendable {
    let kind: ContentKind
    let items: [Channel]
    let categories: [ChannelCategory]
    private let itemsByCategory: [String: [Channel]]

    init(kind: ContentKind, items: [Channel]) {
        var grouped: [String: [Channel]] = [:]
        var order: [String] = []
        for item in items {
            let key = item.category
            if grouped[key] == nil { order.append(key) }
            grouped[key, default: []].append(item)
        }
        // Reihenfolge wie in der Playlist, „Ohne Kategorie“ ans Ende.
        if let index = order.firstIndex(of: Channel.ungroupedTitle) {
            order.append(order.remove(at: index))
        }

        self.kind = kind
        self.items = items
        self.itemsByCategory = grouped
        self.categories = order.map { ChannelCategory(name: $0, count: grouped[$0]?.count ?? 0) }
    }

    var isEmpty: Bool { items.isEmpty }

    /// `nil` liefert alle Einträge des Bereichs.
    func items(in category: String?) -> [Channel] {
        guard let category else { return items }
        return itemsByCategory[category] ?? []
    }
}

/// Eine Serie mit ihren Episoden, zusammengesetzt aus einzelnen Playlist-Einträgen.
struct SeriesShow: Identifiable, Hashable, Sendable {
    struct Episode: Identifiable, Hashable, Sendable {
        let channel: Channel
        let season: Int?
        let number: Int?
        var id: UUID { channel.id }

        var title: String {
            guard let number else { return channel.name }
            return "Episode \(number)"
        }
    }

    let title: String
    let category: String
    let episodes: [Episode]

    var id: String { "\(category)|\(title)" }
    var posterURL: URL? { episodes.lazy.compactMap(\.channel.logoURL).first }

    /// Episoden nach Staffel gruppiert; Einträge ohne Staffelangabe kommen als Staffel 0.
    var seasons: [(season: Int, episodes: [Episode])] {
        Dictionary(grouping: episodes) { $0.season ?? 0 }
            .sorted { $0.key < $1.key }
            .map { ($0.key, $0.value) }
    }

    /// Fasst Episoden anhand des Serientitels zusammen, in Playlist-Reihenfolge.
    static func shows(from items: [Channel]) -> [SeriesShow] {
        var order: [String] = []
        var grouped: [String: (title: String, category: String, episodes: [Episode])] = [:]

        for channel in items {
            let info = EpisodeParser.parse(channel.name)
            let title = info?.showTitle ?? channel.name
            let key = "\(channel.category)|\(title.lowercased())"
            let episode = Episode(channel: channel, season: info?.season, number: info?.episode)

            if grouped[key] == nil {
                order.append(key)
                grouped[key] = (title, channel.category, [])
            }
            grouped[key]?.episodes.append(episode)
        }

        return order.compactMap { key in
            guard let entry = grouped[key] else { return nil }
            let sorted = entry.episodes.sorted {
                ($0.season ?? 0, $0.number ?? 0) < ($1.season ?? 0, $1.number ?? 0)
            }
            return SeriesShow(title: entry.title, category: entry.category, episodes: sorted)
        }
    }
}
