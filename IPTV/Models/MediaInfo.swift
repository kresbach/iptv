import Foundation

/// Zusatzinformationen zu einem Film oder einer Serie (Handlung, Besetzung, Trailer …).
struct MediaInfo: Hashable, Sendable {
    var plot: String?
    var cast: String?
    var director: String?
    var genre: String?
    var releaseDate: String?
    var rating: Double?
    /// Laufzeit in Minuten.
    var durationMinutes: Int?
    var trailerURL: URL?
    var backdropURL: URL?
    var posterURL: URL?

    /// Erscheinungsjahr, z. B. „2023“ aus „2023-05-12“.
    var year: String? {
        guard let releaseDate, releaseDate.count >= 4 else { return nil }
        let prefix = String(releaseDate.prefix(4))
        return Int(prefix) != nil ? prefix : nil
    }

    var durationText: String? {
        guard let durationMinutes, durationMinutes > 0 else { return nil }
        let hours = durationMinutes / 60
        let minutes = durationMinutes % 60
        if hours == 0 { return "\(minutes) Min." }
        return minutes == 0 ? "\(hours) Std." : "\(hours) Std. \(minutes) Min."
    }

    /// Xtream liefert meist nur die YouTube-ID, manchmal einen vollständigen Link.
    static func trailerURL(fromYouTube value: String?) -> URL? {
        guard let value = value?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        if value.lowercased().hasPrefix("http") { return URL(string: value) }
        return URL(string: "https://www.youtube.com/watch?v=\(value)")
    }

    /// YouTube-Suche nach „<Titel> Trailer“, falls kein direkter Link vorhanden ist.
    static func trailerSearchURL(for title: String, year: String? = nil) -> URL? {
        var components = URLComponents(string: "https://www.youtube.com/results")
        let query = [cleanTitle(title), year, "Trailer"].compactMap { $0 }.joined(separator: " ")
        components?.queryItems = [URLQueryItem(name: "search_query", value: query)]
        return components?.url
    }

    /// Entfernt Anbieter-Präfixe wie „DE - “, „[EN] “ oder „4K | “ aus Titeln.
    static func cleanTitle(_ title: String) -> String {
        var result = title.trimmingCharacters(in: .whitespaces)
        if result.hasPrefix("["), let end = result.firstIndex(of: "]") {
            result = String(result[result.index(after: end)...])
        }
        for separator in [" - ", " | ", ": ", "|"] {
            if let range = result.range(of: separator) {
                let prefix = result[..<range.lowerBound].trimmingCharacters(in: .whitespaces)
                if (2...4).contains(prefix.count), prefix == prefix.uppercased() {
                    result = String(result[range.upperBound...])
                    break
                }
            }
        }
        return result.trimmingCharacters(in: .whitespaces)
    }

    // MARK: - Fassungen desselben Films

    /// Vergleichsschlüssel für einen Filmtitel: ohne Anbieter-Präfix, Klammerzusätze und
    /// Qualitätsangaben (4K, HD, 1080p …), dazu das Erscheinungsjahr, falls angegeben.
    static func versionKey(for title: String) -> (name: String, year: String?) {
        var text = cleanTitle(title).lowercased()
        var year: String?
        if let range = text.range(of: #"\b(19|20)\d{2}\b"#, options: .regularExpression) {
            let found = String(text[range])
            // Titel wie „1917“ oder „2012“ nicht als Jahr werten.
            if text.trimmingCharacters(in: .whitespaces) != found {
                year = found
                text.removeSubrange(range)
            }
        }
        text = text.replacingOccurrences(of: #"[\(\[\{][^\)\]\}]*[\)\]\}]"#, with: " ", options: .regularExpression)
        text = text.replacingOccurrences(
            of: #"\b(4k|8k|uhd|fhd|hd|sd|hq|lq|2160p|1080p|720p|576p|480p|hevc|h265|h264|x265|x264|hdr|hdr10|remux|bluray|webrip)\b"#,
            with: " ",
            options: .regularExpression
        )
        let words = text.split(whereSeparator: { !$0.isLetter && !$0.isNumber })
        return (words.joined(separator: " "), year)
    }

    /// Andere Einträge der Playlist, die denselben Film enthalten (z. B. SD, HD, 4K).
    static func versions(of movie: Channel, in movies: [Channel]) -> [Channel] {
        let target = versionKey(for: movie.name)
        guard !target.name.isEmpty else { return [] }
        // Schnelle Vorauswahl über das längste Wort, damit nicht jeder Titel aufbereitet wird.
        let anchor = target.name
            .components(separatedBy: " ")
            .max { $0.count < $1.count } ?? target.name

        return movies.filter { candidate in
            guard candidate.id != movie.id, candidate.url != movie.url,
                  candidate.name.localizedCaseInsensitiveContains(anchor) else { return false }
            let key = versionKey(for: candidate.name)
            guard key.name == target.name else { return false }
            // Unterschiedliche Jahre = Neuverfilmung, keine andere Fassung.
            if let a = key.year, let b = target.year, a != b { return false }
            return true
        }
    }

    /// „01:52:03“ oder „112 min“ → Minuten.
    static func minutes(from value: String?) -> Int? {
        guard let value = value?.trimmingCharacters(in: .whitespaces), !value.isEmpty else { return nil }
        let parts = value.split(separator: ":").compactMap { Int($0) }
        if parts.count == 3 { return parts[0] * 60 + parts[1] }
        if parts.count == 2 { return parts[0] }
        let digits = value.prefix { $0.isNumber }
        return Int(digits)
    }
}
