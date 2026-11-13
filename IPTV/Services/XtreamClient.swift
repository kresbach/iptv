import Foundation

/// Zugriff auf die Xtream-Codes-API (`player_api.php`).
struct XtreamClient: Sendable {
    let credentials: XtreamCredentials

    enum XtreamError: LocalizedError {
        case invalidLogin
        case accountInactive(String)

        var errorDescription: String? {
            switch self {
            case .invalidLogin: "Anmeldung fehlgeschlagen. Bitte prüfe Benutzername und Passwort."
            case .accountInactive(let status): "Das Konto ist nicht aktiv (Status: \(status))."
            }
        }
    }

    /// Bereinigt die Server-Eingabe, z. B. „host:8080/player_api.php“ → „http://host:8080“.
    static func normalizedServer(_ input: String) -> URL? {
        guard let url = URL.userInput(input),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        components.path = components.path
            .replacingOccurrences(of: "player_api.php", with: "")
            .replacingOccurrences(of: "get.php", with: "")
        while components.path.hasSuffix("/") { components.path.removeLast() }
        components.query = nil
        return components.url
    }

    struct AccountStatus: Sendable {
        let isAuthenticated: Bool
        let status: String?
        let activeConnections: Int?
        let maxConnections: Int?
        let expires: Date?
        let allowedFormats: [String]
    }

    /// Kontostatus für die Diagnose (aktive/erlaubte Verbindungen, Ablaufdatum).
    func accountStatus() async throws -> AccountStatus {
        let account: Account = try await request(action: nil)
        let info = account.userInfo
        return AccountStatus(
            isAuthenticated: info?.auth?.int == 1,
            status: info?.status?.string,
            activeConnections: info?.activeCons?.int,
            maxConnections: info?.maxConnections?.int,
            expires: info?.expDate?.int.map { Date(timeIntervalSince1970: TimeInterval($0)) },
            allowedFormats: info?.allowedOutputFormats ?? []
        )
    }

    var xmltvURL: URL? {
        endpoint("xmltv.php", query: [])
    }

    /// Lädt Live-Sender, Filme und Serien (ohne Episoden).
    func loadContent() async throws -> PlaylistData {
        let account: Account = try await request(action: nil)
        guard let info = account.userInfo, info.auth?.int == 1 else { throw XtreamError.invalidLogin }
        if let status = info.status?.string, status.lowercased() != "active" {
            throw XtreamError.accountInactive(status)
        }
        let formats = info.allowedOutputFormats ?? []
        // HLS (m3u8) können Apple-Player und VLC abspielen; .ts nur VLC.
        let liveExtension = formats.isEmpty || formats.contains("m3u8") ? "m3u8" : "ts"

        async let liveCategoriesTask: [Category] = requestList(action: "get_live_categories")
        async let vodCategoriesTask: [Category] = requestList(action: "get_vod_categories")
        async let seriesCategoriesTask: [Category] = requestList(action: "get_series_categories")
        async let liveStreamsTask: [LiveStream] = requestList(action: "get_live_streams")
        async let vodStreamsTask: [VODStream] = requestList(action: "get_vod_streams")
        async let seriesTask: [Series] = requestList(action: "get_series")

        let liveNames = Self.names(try await liveCategoriesTask)
        let vodNames = Self.names(try await vodCategoriesTask)
        let seriesNames = Self.names(try await seriesCategoriesTask)

        var channels: [Channel] = []
        for stream in try await liveStreamsTask {
            guard let id = stream.streamId?.int,
                  let url = streamURL(type: "live", id: id, extension: liveExtension) else { continue }
            channels.append(Channel(
                name: stream.name?.string ?? "Sender \(id)",
                url: url,
                group: stream.categoryId?.string.flatMap { liveNames[$0] },
                tvgID: stream.epgChannelId?.string.flatMap { $0.isEmpty ? nil : $0 },
                logoURL: stream.streamIcon?.string.flatMap(URL.init(string:)),
                kind: .live
            ))
        }
        for stream in try await vodStreamsTask {
            guard let id = stream.streamId?.int,
                  let url = streamURL(type: "movie", id: id, extension: stream.containerExtension?.string ?? "mp4")
            else { continue }
            channels.append(Channel(
                name: stream.name?.string ?? "Film \(id)",
                url: url,
                group: stream.categoryId?.string.flatMap { vodNames[$0] },
                logoURL: stream.streamIcon?.string.flatMap(URL.init(string:)),
                kind: .movie
            ))
        }

        let seriesList = try await seriesTask
        let shows = seriesList.compactMap { series -> SeriesShow? in
            guard let id = series.seriesId?.int, let name = series.name?.string else { return nil }
            return SeriesShow(
                title: name,
                category: series.categoryId?.string.flatMap { seriesNames[$0] } ?? Channel.ungroupedTitle,
                posterURL: series.cover?.string.flatMap(URL.init(string:)),
                plot: series.plot?.string,
                episodes: [],
                xtreamSeriesID: id
            )
        }

        return PlaylistData(channels: channels, shows: shows)
    }

    /// Lädt Staffeln und Episoden einer Serie.
    func episodes(for show: SeriesShow) async throws -> [SeriesShow.Episode] {
        guard let seriesID = show.xtreamSeriesID else { return show.episodes }
        let info: SeriesInfo = try await request(
            action: "get_series_info",
            extra: [URLQueryItem(name: "series_id", value: String(seriesID))]
        )

        var result: [SeriesShow.Episode] = []
        for (seasonKey, episodes) in info.episodes ?? [:] {
            for episode in episodes {
                guard let id = episode.id?.string,
                      let url = streamURL(type: "series", id: id, extension: episode.containerExtension?.string ?? "mp4")
                else { continue }
                let season = episode.season?.int ?? Int(seasonKey)
                let number = episode.episodeNum?.int
                let title = episode.title?.string
                let channel = Channel(
                    name: "\(show.title) S\(String(format: "%02d", season ?? 0)) E\(String(format: "%02d", number ?? 0))",
                    url: url,
                    group: show.category,
                    logoURL: episode.info?.movieImage?.string.flatMap(URL.init(string:)) ?? show.posterURL,
                    kind: .series
                )
                result.append(.init(channel: channel, season: season, number: number, title: title))
            }
        }
        return result.sorted { ($0.season ?? 0, $0.number ?? 0) < ($1.season ?? 0, $1.number ?? 0) }
    }

    // MARK: - Intern

    private func endpoint(_ path: String, query: [URLQueryItem]) -> URL? {
        var components = URLComponents(url: credentials.server.appending(path: path), resolvingAgainstBaseURL: false)
        components?.queryItems = [
            URLQueryItem(name: "username", value: credentials.username),
            URLQueryItem(name: "password", value: credentials.password),
        ] + query
        return components?.url
    }

    private func streamURL(type: String, id: some CustomStringConvertible, extension ext: String) -> URL? {
        let user = credentials.username.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? credentials.username
        let pass = credentials.password.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? credentials.password
        let base = credentials.server.absoluteString
        return URL(string: "\(base)/\(type)/\(user)/\(pass)/\(id).\(ext)")
    }

    private func request<T: Decodable>(action: String?, extra: [URLQueryItem] = []) async throws -> T {
        let data = try await rawData(action: action, extra: extra)
        return try Self.decoder.decode(T.self, from: data)
    }

    /// Wie `request`, liefert aber eine leere Liste, wenn der Server bei
    /// leeren Ergebnissen `null` oder `{}` statt `[]` schickt.
    private func requestList<T: Decodable>(action: String) async throws -> [T] {
        let data = try await rawData(action: action, extra: [])
        do {
            return try Self.decoder.decode([T].self, from: data)
        } catch {
            let text = String(decoding: data.prefix(16), as: UTF8.self)
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if text.isEmpty || text == "null" || text.hasPrefix("{") { return [] }
            throw error
        }
    }

    private func rawData(action: String?, extra: [URLQueryItem]) async throws -> Data {
        var query = extra
        if let action { query.insert(URLQueryItem(name: "action", value: action), at: 0) }
        guard let url = endpoint("player_api.php", query: query) else { throw URLError(.badURL) }
        return try await HTTPClient.data(from: url)
    }

    private static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }

    private static func names(_ categories: [Category]) -> [String: String] {
        var result: [String: String] = [:]
        for category in categories {
            if let id = category.categoryId?.string, let name = category.categoryName?.string {
                result[id] = name
            }
        }
        return result
    }

    // MARK: - API-Modelle (Xtream liefert Zahlen mal als Text, mal als Zahl)

    private struct Account: Decodable {
        let userInfo: UserInfo?
    }

    private struct UserInfo: Decodable {
        let auth: Flexible?
        let status: Flexible?
        let allowedOutputFormats: [String]?
        let activeCons: Flexible?
        let maxConnections: Flexible?
        let expDate: Flexible?
    }

    private struct Category: Decodable {
        let categoryId: Flexible?
        let categoryName: Flexible?
    }

    private struct LiveStream: Decodable {
        let name: Flexible?
        let streamId: Flexible?
        let streamIcon: Flexible?
        let epgChannelId: Flexible?
        let categoryId: Flexible?
    }

    private struct VODStream: Decodable {
        let name: Flexible?
        let streamId: Flexible?
        let streamIcon: Flexible?
        let categoryId: Flexible?
        let containerExtension: Flexible?
    }

    private struct Series: Decodable {
        let name: Flexible?
        let seriesId: Flexible?
        let cover: Flexible?
        let plot: Flexible?
        let categoryId: Flexible?
    }

    private struct SeriesInfo: Decodable {
        let episodes: [String: [Episode]]?

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            // Ohne Episoden liefern manche Server ein leeres Array statt eines Objekts.
            episodes = try? container.decodeIfPresent([String: [Episode]].self, forKey: .episodes)
        }

        private enum CodingKeys: String, CodingKey { case episodes }
    }

    private struct Episode: Decodable {
        let id: Flexible?
        let episodeNum: Flexible?
        let title: Flexible?
        let containerExtension: Flexible?
        let season: Flexible?
        let info: EpisodeInfo?

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            id = try? container.decodeIfPresent(Flexible.self, forKey: .id)
            episodeNum = try? container.decodeIfPresent(Flexible.self, forKey: .episodeNum)
            title = try? container.decodeIfPresent(Flexible.self, forKey: .title)
            containerExtension = try? container.decodeIfPresent(Flexible.self, forKey: .containerExtension)
            season = try? container.decodeIfPresent(Flexible.self, forKey: .season)
            info = try? container.decodeIfPresent(EpisodeInfo.self, forKey: .info)
        }

        private enum CodingKeys: String, CodingKey {
            case id, episodeNum, title, containerExtension, season, info
        }
    }

    private struct EpisodeInfo: Decodable {
        let movieImage: Flexible?
    }
}

/// Wert, der als Text, Zahl oder Bool kommen kann.
private struct Flexible: Decodable {
    let string: String?

    var int: Int? {
        guard let string else { return nil }
        return Int(string) ?? Double(string).map { Int($0) }
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let value = try? container.decode(String.self) {
            string = value
        } else if let value = try? container.decode(Int.self) {
            string = String(value)
        } else if let value = try? container.decode(Double.self) {
            string = String(value)
        } else if let value = try? container.decode(Bool.self) {
            string = value ? "1" : "0"
        } else {
            string = nil
        }
    }
}
