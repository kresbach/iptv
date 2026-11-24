import Foundation

/// Zugriff auf TMDB (themoviedb.org) für Trailer, Empfehlungen und Trends.
/// Funktioniert mit einem kostenlosen API-Schlüssel (v3) oder Lesezugriffs-Token (v4, beginnt mit „eyJ“).
enum TMDB {
    private static let keyAccount = "tmdbKey"

    static var key: String {
        (Keychain.read(keyAccount) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func setKey(_ value: String) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            Keychain.delete(keyAccount)
        } else {
            Keychain.write(trimmed, for: keyAccount)
        }
    }

    static var isConfigured: Bool { !key.isEmpty }

    enum TMDBError: LocalizedError {
        case invalidKey
        case http(Int)

        var errorDescription: String? {
            switch self {
            case .invalidKey: "TMDB lehnt den Schlüssel ab. Bitte unter Einstellungen → TMDB prüfen."
            case .http(let code): "TMDB meldet Fehler \(code)."
            }
        }
    }

    /// Ein Titel aus einer TMDB-Liste.
    struct Entry: Codable, Hashable, Sendable {
        var title: String
        var original: String?
        var year: String?
        var posterURL: URL?
        var isTV: Bool
        var popularity: Double
    }

    /// GET-Anfrage an `https://api.themoviedb.org/3/<path>`.
    static func get(_ path: String, query: [URLQueryItem] = [], language: String = "de-DE") async throws -> Data {
        let key = self.key
        guard !key.isEmpty else { throw TMDBError.invalidKey }
        guard var components = URLComponents(string: "https://api.themoviedb.org/3/\(path)") else { throw URLError(.badURL) }
        let bearer = key.hasPrefix("eyJ")
        var items = query + [URLQueryItem(name: "language", value: language)]
        if !bearer { items.append(URLQueryItem(name: "api_key", value: key)) }
        components.queryItems = items
        guard let url = components.url else { throw URLError(.badURL) }

        var request = URLRequest(url: url, timeoutInterval: 20)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if bearer { request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse {
            if http.statusCode == 401 { throw TMDBError.invalidKey }
            guard (200..<300).contains(http.statusCode) else { throw TMDBError.http(http.statusCode) }
        }
        return data
    }

    /// Liest `results` einer TMDB-Antwort. `tv == nil`: gemischte Liste (trending/all).
    static func parseEntries(_ data: Data, tv: Bool?) -> [Entry] {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let results = object["results"] as? [[String: Any]] else { return [] }
        return results.compactMap { item in
            let isTV: Bool
            if let tv {
                isTV = tv
            } else {
                switch item["media_type"] as? String {
                case "tv": isTV = true
                case "movie": isTV = false
                default: return nil
                }
            }
            guard let title = (isTV ? item["name"] : item["title"]) as? String, !title.isEmpty else { return nil }
            let date = (isTV ? item["first_air_date"] : item["release_date"]) as? String
            return Entry(
                title: title,
                original: (isTV ? item["original_name"] : item["original_title"]) as? String,
                year: date.flatMap { $0.count >= 4 ? String($0.prefix(4)) : nil },
                posterURL: (item["poster_path"] as? String).flatMap { URL(string: "https://image.tmdb.org/t/p/w342\($0)") },
                isTV: isTV,
                popularity: (item["popularity"] as? NSNumber)?.doubleValue ?? 0
            )
        }
    }

    /// Vergleichsschlüssel für Titel (ohne Präfixe, Qualitätsangaben, Jahr; kleingeschrieben).
    static func titleKey(_ title: String) -> String {
        MediaInfo.versionKey(for: title).name
    }
}

/// TMDB-Abfragen mit Zwischenspeicher je Titel.
actor TMDBClient {
    static let shared = TMDBClient()

    private var ids: [String: Int?] = [:]
    private var trailers: [String: URL?] = [:]
    private var recommendations: [String: [String]] = [:]

    /// TMDB-Kennung eines Titels über die Suche.
    func id(title: String, year: String?, tv: Bool) async -> Int? {
        let key = MediaInfo.versionKey(for: title)
        let searchYear = year ?? key.year
        let cacheKey = "\(tv)|\(key.name)|\(searchYear ?? "")"
        if let cached = ids[cacheKey] { return cached }
        guard !key.name.isEmpty else { return nil }

        var query = [URLQueryItem(name: "query", value: key.name)]
        if let searchYear {
            query.append(URLQueryItem(name: tv ? "first_air_date_year" : "year", value: searchYear))
        }
        var found: Int?
        if let data = try? await TMDB.get("search/\(tv ? "tv" : "movie")", query: query),
           let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let first = (object["results"] as? [[String: Any]])?.first {
            found = (first["id"] as? NSNumber)?.intValue
        }
        ids[cacheKey] = found
        return found
    }

    /// Trailer als YouTube-Link (deutsch, sonst englisch).
    func trailer(title: String, year: String?, tv: Bool) async -> URL? {
        let cacheKey = "\(tv)|\(title)"
        if let cached = trailers[cacheKey] { return cached }
        var result: URL?
        if let id = await id(title: title, year: year, tv: tv) {
            for language in ["de-DE", "en-US"] {
                guard let data = try? await TMDB.get("\(tv ? "tv" : "movie")/\(id)/videos", language: language),
                      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let videos = object["results"] as? [[String: Any]] else { continue }
                let youtube = videos
                    .filter { ($0["site"] as? String) == "YouTube" }
                    .sorted { (($0["type"] as? String) == "Trailer" ? 0 : 1) < (($1["type"] as? String) == "Trailer" ? 0 : 1) }
                if let key = youtube.first?["key"] as? String, let url = MediaInfo.trailerURL(fromYouTube: key) {
                    result = url
                    break
                }
            }
        }
        trailers[cacheKey] = result
        return result
    }

    /// Titel, die TMDB zu diesem Titel empfiehlt (deutsche und Originaltitel).
    func recommendedTitles(title: String, year: String?, tv: Bool) async -> [String] {
        let cacheKey = "\(tv)|\(title)"
        if let cached = recommendations[cacheKey] { return cached }
        var titles: [String] = []
        if let id = await id(title: title, year: year, tv: tv),
           let data = try? await TMDB.get("\(tv ? "tv" : "movie")/\(id)/recommendations") {
            titles = TMDB.parseEntries(data, tv: tv).flatMap { [$0.title, $0.original].compactMap { $0 } }
        }
        recommendations[cacheKey] = titles
        return titles
    }
}
