import Foundation

/// Ein einzelner Sender bzw. Stream aus der M3U-Playlist.
struct Channel: Identifiable, Hashable, Codable, Sendable {
    let id: UUID
    let name: String
    let url: URL
    /// Wert aus `group-title` (oder `#EXTGRP`), falls vorhanden.
    let group: String?
    let tvgID: String?
    let logoURL: URL?

    init(
        id: UUID = UUID(),
        name: String,
        url: URL,
        group: String? = nil,
        tvgID: String? = nil,
        logoURL: URL? = nil
    ) {
        self.id = id
        self.name = name
        self.url = url
        self.group = group
        self.tvgID = tvgID
        self.logoURL = logoURL
    }

    static let ungroupedTitle = "Ohne Kategorie"

    /// Kategorie für die Anzeige. Sender ohne Gruppe landen in „Ohne Kategorie“.
    var category: String { group ?? Self.ungroupedTitle }

    /// Live TV, Film oder Serie, abgeleitet aus URL, Gruppe und Name.
    var kind: ContentKind {
        ContentClassifier.kind(url: url, group: group, name: name)
    }

    /// Kürzel für den runden Platzhalter in der Liste, z. B. „DA“ für „Das Erste HD“.
    var initials: String {
        let words = name
            .split(whereSeparator: { !$0.isLetter && !$0.isNumber })
            .prefix(2)
        let letters = words.compactMap(\.first).map { String($0).uppercased() }
        return letters.isEmpty ? "TV" : letters.joined()
    }
}

/// Eine Kategorie samt Anzahl der Sender.
struct ChannelCategory: Identifiable, Hashable, Sendable {
    let name: String
    let count: Int
    var id: String { name }
}
