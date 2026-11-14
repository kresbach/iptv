import Foundation

/// Die drei Bereiche der App, wie man sie von IPTV-Playern kennt.
enum ContentKind: String, CaseIterable, Identifiable, Hashable, Codable, Sendable {
    case live
    case movie
    case series

    var id: String { rawValue }

    var title: String {
        switch self {
        case .live: "Live TV"
        case .movie: "Filme"
        case .series: "Serien"
        }
    }

    var symbol: String {
        switch self {
        case .live: "play.tv.fill"
        case .movie: "film.fill"
        case .series: "rectangle.stack.fill"
        }
    }

    /// Bezeichnung der Einträge, z. B. „120 Sender“.
    var unit: String {
        switch self {
        case .live: "Sender"
        case .movie: "Filme"
        case .series: "Serien"
        }
    }
}

/// Ordnet einen Playlist-Eintrag Live TV, Filmen oder Serien zu.
///
/// Xtream-Codes-Anbieter kennzeichnen Inhalte über den Pfad
/// (`/movie/…`, `/series/…`). Bei anderen Listen helfen Dateiendung,
/// Gruppenname und Episodenkennungen wie „S01 E02“.
enum ContentClassifier {
    private static let vodExtensions: Set<String> = [
        "mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg",
    ]

    static func kind(url: URL, group: String?, name: String) -> ContentKind {
        let path = url.path().lowercased()
        let group = group?.lowercased() ?? ""
        let looksLikeEpisode = EpisodeParser.parse(name) != nil

        if path.contains("/series/") { return .series }
        if path.contains("/movie/") || path.contains("/movies/") || path.contains("/vod/") {
            return looksLikeEpisode ? .series : .movie
        }

        let isVODFile = vodExtensions.contains(url.pathExtension.lowercased())
        if isVODFile {
            return looksLikeEpisode || group.contains("serie") ? .series : .movie
        }
        if group.hasPrefix("vod") {
            return looksLikeEpisode ? .series : .movie
        }
        // Gruppen wie „Serien HD“ enthalten oft Live-Sender – nur mit Episodenkennung als Serie werten.
        if looksLikeEpisode && group.contains("serie") { return .series }
        return .live
    }
}

/// Erkennt Staffel und Episode in Titeln wie „Dark S01 E03“ oder „Dark - s1e3“.
enum EpisodeParser {
    struct Info: Hashable, Sendable {
        let showTitle: String
        let season: Int
        let episode: Int
    }

    static func parse(_ name: String) -> Info? {
        let chars = Array(name)
        var index = 0

        while index < chars.count {
            defer { index += 1 }
            guard chars[index] == "S" || chars[index] == "s",
                  index == 0 || !chars[index - 1].isLetter else { continue }

            var cursor = index + 1
            guard let season = readNumber(chars, &cursor, maxDigits: 3) else { continue }

            while cursor < chars.count, " ._-".contains(chars[cursor]) { cursor += 1 }
            guard cursor < chars.count, chars[cursor] == "E" || chars[cursor] == "e" else { continue }
            cursor += 1
            guard let episode = readNumber(chars, &cursor, maxDigits: 4) else { continue }

            let title = String(chars[..<index])
                .trimmingCharacters(in: CharacterSet.whitespaces.union(CharacterSet(charactersIn: "-:|._")))
            return Info(showTitle: title.isEmpty ? name : title, season: season, episode: episode)
        }
        return nil
    }

    private static func readNumber(_ chars: [Character], _ cursor: inout Int, maxDigits: Int) -> Int? {
        var value = 0
        var digits = 0
        while cursor < chars.count, digits < maxDigits, let digit = chars[cursor].wholeNumberValue {
            value = value * 10 + digit
            digits += 1
            cursor += 1
        }
        return digits > 0 ? value : nil
    }
}
