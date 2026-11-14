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
