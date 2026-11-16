import Foundation
import Observation

/// Ein Untertitel-Treffer von OpenSubtitles.
struct SubtitleResult: Identifiable, Hashable, Sendable {
    let fileID: Int
    let language: String
    let release: String
    let downloadCount: Int
    let isHearingImpaired: Bool
    let isTrusted: Bool

    var id: Int { fileID }

    var languageName: String {
        Locale.current.localizedString(forLanguageCode: language) ?? language.uppercased()
    }
}

/// Sucht und lädt Untertitel über die OpenSubtitles-API (api.opensubtitles.com).
/// API-Key und optionale Zugangsdaten liegen im Schlüsselbund.
@MainActor
@Observable
final class SubtitleService {
    static let shared = SubtitleService()

    enum SubtitleError: LocalizedError {
        case missingKey
        case quotaExceeded(String?)
        case unauthorized
        case server(Int)
        case noFile

        var errorDescription: String? {
            switch self {
            case .missingKey:
                "Es ist kein OpenSubtitles-API-Key hinterlegt. Trage ihn unter Einstellungen → Untertitel ein."
            case .quotaExceeded(let reset):
                "Das Tageslimit für Untertitel-Downloads ist erreicht." + (reset.map { " Zurückgesetzt in: \($0)." } ?? "")
            case .unauthorized:
                "OpenSubtitles hat die Anfrage abgelehnt. Bitte prüfe API-Key, Benutzername und Passwort."
            case .server(let code):
                "OpenSubtitles meldet Fehler \(code)."
            case .noFile:
                "Der Untertitel konnte nicht geladen werden."
            }
        }
    }

    private static let keyAccount = "openSubtitlesAPIKey"
    private static let userAccount = "openSubtitlesUsername"
    private static let passwordAccount = "openSubtitlesPassword"
    private static let languagesKey = "subtitleLanguages"
    private static let savedKey = "savedSubtitles"
    /// OpenSubtitles verlangt einen eigenen User-Agent mit App-Name und Version.
    private static let userAgent = "IPTVApp v1.0"

    /// Verbleibende Downloads heute, sofern bekannt.
    private(set) var remainingDownloads: Int?

    @ObservationIgnored private var token: String?
    @ObservationIgnored private var host = "api.opensubtitles.com"

    // MARK: - Einstellungen

    var apiKey: String {
        get { Keychain.read(Self.keyAccount) ?? "" }
        set { store(newValue, account: Self.keyAccount) }
    }

    var username: String {
        get { Keychain.read(Self.userAccount) ?? "" }
        set { store(newValue, account: Self.userAccount); token = nil }
    }

    var password: String {
        get { Keychain.read(Self.passwordAccount) ?? "" }
        set { store(newValue, account: Self.passwordAccount); token = nil }
    }

    /// Bevorzugte Sprachen als Kürzel, z. B. „de,en“.
    var languages: String {
        get { UserDefaults.standard.string(forKey: Self.languagesKey) ?? "de,en" }
        set { UserDefaults.standard.set(newValue, forKey: Self.languagesKey) }
    }

    var isConfigured: Bool { !apiKey.isEmpty }

    private func store(_ value: String, account: String) {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { Keychain.delete(account) } else { Keychain.write(trimmed, for: account) }
    }

    // MARK: - Suche

    /// Sucht Untertitel passend zum Film bzw. zur Episode.
    func search(for channel: Channel) async throws -> [SubtitleResult] {
        guard isConfigured else { throw SubtitleError.missingKey }

        var items: [URLQueryItem] = []
        if channel.kind == .series, let episode = EpisodeParser.parse(channel.name) {
            items.append(URLQueryItem(name: "query", value: MediaInfo.versionKey(for: episode.showTitle).name))
            items.append(URLQueryItem(name: "season_number", value: String(episode.season)))
            items.append(URLQueryItem(name: "episode_number", value: String(episode.episode)))
        } else {
            let key = MediaInfo.versionKey(for: channel.name)
            items.append(URLQueryItem(name: "query", value: key.name))
            if let year = key.year { items.append(URLQueryItem(name: "year", value: year)) }
        }
        let codes = languages.lowercased()
            .split(whereSeparator: { !$0.isLetter && $0 != "-" })
            .map(String.init).sorted()
        if !codes.isEmpty { items.append(URLQueryItem(name: "languages", value: codes.joined(separator: ","))) }
        items.append(URLQueryItem(name: "order_by", value: "download_count"))

        var components = URLComponents()
        components.scheme = "https"
        components.host = host
        components.path = "/api/v1/subtitles"
        // Die API erwartet alphabetisch sortierte Parameter (sonst Weiterleitung).
        components.queryItems = items.sorted { $0.name < $1.name }
        guard let url = components.url else { throw SubtitleError.noFile }

        let data = try await send(URLRequest(url: url))
        let response = try Self.decoder.decode(SearchResponse.self, from: data)

        var seen = Set<Int>()
        let results = response.data.compactMap { item -> SubtitleResult? in
            let attributes = item.attributes
            guard let file = attributes.files?.first, seen.insert(file.fileId).inserted else { return nil }
            return SubtitleResult(
                fileID: file.fileId,
                language: (attributes.language ?? "?").lowercased(),
                release: attributes.release ?? file.fileName ?? "Untertitel",
                downloadCount: attributes.downloadCount ?? 0,
                isHearingImpaired: attributes.hearingImpaired ?? false,
                isTrusted: attributes.fromTrusted ?? false
            )
        }
        // Bevorzugte Sprachen in der eingestellten Reihenfolge, darin die beliebtesten zuerst.
        let preferred = languages.lowercased().split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }
        return results.sorted { a, b in
            let ia = preferred.firstIndex(of: a.language) ?? Int.max
            let ib = preferred.firstIndex(of: b.language) ?? Int.max
            return ia != ib ? ia < ib : a.downloadCount > b.downloadCount
        }
    }

    // MARK: - Download

    /// Lädt den Untertitel (oder nimmt die bereits gespeicherte Datei) und merkt ihn für den Film.
    func download(_ result: SubtitleResult, for channel: Channel) async throws -> URL {
        let file = Self.cacheURL(fileID: result.fileID)
        if !FileManager.default.fileExists(atPath: file.path) {
            guard isConfigured else { throw SubtitleError.missingKey }
            try await loginIfNeeded()

            var request = URLRequest(url: URL(string: "https://\(host)/api/v1/download")!)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: ["file_id": result.fileID, "sub_format": "srt"])

            let info = try Self.decoder.decode(DownloadResponse.self, from: try await send(request))
            remainingDownloads = info.remaining
            guard let link = info.link.flatMap(URL.init(string:)) else {
                throw SubtitleError.quotaExceeded(info.resetTime)
            }
            let (subtitle, response) = try await URLSession.shared.data(from: link)
            guard (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? true, !subtitle.isEmpty else {
                throw SubtitleError.noFile
            }
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try subtitle.write(to: file, options: .atomic)
        }
        var saved = UserDefaults.standard.dictionary(forKey: Self.savedKey) as? [String: Int] ?? [:]
        saved[channel.stableKey] = result.fileID
        UserDefaults.standard.set(saved, forKey: Self.savedKey)
        return file
    }

    /// Zuletzt für diesen Film gewählter Untertitel, falls noch auf dem Gerät.
    func savedSubtitle(for channel: Channel) -> URL? {
        guard let saved = UserDefaults.standard.dictionary(forKey: Self.savedKey) as? [String: Int],
              let fileID = saved[channel.stableKey] else { return nil }
        let file = Self.cacheURL(fileID: fileID)
        return FileManager.default.fileExists(atPath: file.path) ? file : nil
    }

    func forgetSubtitle(for channel: Channel) {
        var saved = UserDefaults.standard.dictionary(forKey: Self.savedKey) as? [String: Int] ?? [:]
        saved[channel.stableKey] = nil
        UserDefaults.standard.set(saved, forKey: Self.savedKey)
    }

    private static func cacheURL(fileID: Int) -> URL {
        Storage.directory.appending(path: "Subtitles", directoryHint: .isDirectory).appending(path: "\(fileID).srt")
    }

    // MARK: - Anmeldung und Anfragen

    /// Mit Konto gibt es mehr Downloads pro Tag; ohne Zugangsdaten wird anonym geladen.
    private func loginIfNeeded() async throws {
        guard token == nil, !username.isEmpty, !password.isEmpty else { return }
        var request = URLRequest(url: URL(string: "https://api.opensubtitles.com/api/v1/login")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["username": username, "password": password])

        let login = try Self.decoder.decode(LoginResponse.self, from: try await send(request))
        token = login.token
        // VIP-Konten bekommen einen eigenen Server genannt.
        if let base = login.baseUrl, base.hasSuffix("opensubtitles.com"), !base.contains("/") { host = base }
    }

    private func send(_ original: URLRequest) async throws -> Data {
        var request = original
        request.setValue(apiKey, forHTTPHeaderField: "Api-Key")
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        request.timeoutInterval = 20

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { return data }
        switch http.statusCode {
        case 200..<300:
            return data
        case 401, 403:
            token = nil
            throw SubtitleError.unauthorized
        case 406, 429:
            let info = try? Self.decoder.decode(DownloadResponse.self, from: data)
            throw SubtitleError.quotaExceeded(info?.resetTime)
        default:
            throw SubtitleError.server(http.statusCode)
        }
    }

    private static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }

    // MARK: - API-Modelle

    private struct SearchResponse: Decodable {
        let data: [Item]
    }

    private struct Item: Decodable {
        let attributes: Attributes
    }

    private struct Attributes: Decodable {
        let language: String?
        let downloadCount: Int?
        let hearingImpaired: Bool?
        let fromTrusted: Bool?
        let release: String?
        let files: [File]?
    }

    private struct File: Decodable {
        let fileId: Int
        let fileName: String?
    }

    private struct DownloadResponse: Decodable {
        let link: String?
        let remaining: Int?
        let resetTime: String?
    }

    private struct LoginResponse: Decodable {
        let token: String?
        let baseUrl: String?
    }
}
