import Foundation

/// Ein einzelner Eintrag der Playlist: Live-Sender, Film oder Serien-Episode.
struct Channel: Identifiable, Hashable, Codable, Sendable {
    let id: UUID
    let name: String
    let url: URL
    /// Wert aus `group-title` (oder `#EXTGRP`) bzw. die Xtream-Kategorie.
    let group: String?
    /// Kennung für den TV-Guide (`tvg-id` bzw. Xtream `epg_channel_id`).
    let tvgID: String?
    let logoURL: URL?
    let kind: ContentKind

    init(
        id: UUID = UUID(),
        name: String,
        url: URL,
        group: String? = nil,
        tvgID: String? = nil,
        logoURL: URL? = nil,
        kind: ContentKind? = nil
    ) {
        self.id = id
        self.name = name
        self.url = url
        self.group = group
        self.tvgID = tvgID
        self.logoURL = logoURL
        self.kind = kind ?? ContentClassifier.kind(url: url, group: group, name: name)
    }

    /// Ältere Zwischenspeicher enthalten noch kein `kind`, das wird dann neu bestimmt.
    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let url = try container.decode(URL.self, forKey: .url)
        let name = try container.decode(String.self, forKey: .name)
        let group = try container.decodeIfPresent(String.self, forKey: .group)
        self.init(
            id: try container.decodeIfPresent(UUID.self, forKey: .id) ?? UUID(),
            name: name,
            url: url,
            group: group,
            tvgID: try container.decodeIfPresent(String.self, forKey: .tvgID),
            logoURL: try container.decodeIfPresent(URL.self, forKey: .logoURL),
            kind: try container.decodeIfPresent(ContentKind.self, forKey: .kind)
        )
    }

    static let ungroupedTitle = "Ohne Kategorie"

    /// Kategorie für die Anzeige. Einträge ohne Gruppe landen in „Ohne Kategorie“.
    var category: String { group ?? Self.ungroupedTitle }

    /// Bleibt beim Aktualisieren der Playlist gleich (anders als `id`) und dient
    /// als Schlüssel für Favoriten, Verlauf und Fortschritt.
    var stableKey: String { "\(kind.rawValue)|\(category)|\(name)" }

    /// Kürzel für den Platzhalter, z. B. „DE“ für „Das Erste HD“.
    var initials: String {
        let words = name
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber })
            .prefix(2)
        let letters = words.compactMap(\.first).map { String($0).uppercased() }
        return letters.isEmpty ? "TV" : letters.joined()
    }
}

/// Eine Kategorie samt Anzahl der Einträge.
struct ChannelCategory: Identifiable, Hashable, Sendable {
    let name: String
    let count: Int
    var id: String { name }
}
