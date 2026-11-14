import Foundation

/// Zugangsdaten für einen Xtream-Codes-Server.
struct XtreamCredentials: Codable, Hashable, Sendable {
    var server: URL
    var username: String
    var password: String
}

/// Woher eine Playlist stammt.
enum PlaylistSource: Codable, Hashable, Sendable {
    /// M3U-Link (http oder https).
    case m3u(url: URL)
    /// Xtream-Codes-Login (Server, Benutzername, Passwort).
    case xtream(XtreamCredentials)
    /// Aus der Dateien-App importierte M3U-Datei, lokal gespeichert.
    case file(originalName: String)

    var typeTitle: String {
        switch self {
        case .m3u: "M3U-Link"
        case .xtream: "Xtream Codes"
        case .file: "M3U-Datei"
        }
    }

    var symbol: String {
        switch self {
        case .m3u: "link"
        case .xtream: "person.badge.key.fill"
        case .file: "doc.fill"
        }
    }

    /// Kurzbeschreibung ohne Passwort, z. B. für die Einstellungen.
    var displayDetail: String {
        switch self {
        case .m3u(let url): url.host() ?? url.absoluteString
        case .xtream(let credentials): "\(credentials.username) @ \(credentials.server.host() ?? credentials.server.absoluteString)"
        case .file(let name): name
        }
    }
}

struct Playlist: Identifiable, Codable, Hashable, Sendable {
    let id: UUID
    var name: String
    var source: PlaylistSource
    /// Vom Nutzer eingetragene EPG-Adresse (XMLTV), hat Vorrang.
    var customEPGURL: URL?
    /// Automatisch erkannte EPG-Adresse (`url-tvg` in der M3U bzw. Xtream `xmltv.php`).
    var detectedEPGURL: URL?
    var lastUpdated: Date?

    init(id: UUID = UUID(), name: String, source: PlaylistSource) {
        self.id = id
        self.name = name
        self.source = source
    }

    var epgURL: URL? { customEPGURL ?? detectedEPGURL }
}

extension URL {
    /// Macht aus einer Nutzereingabe eine URL. Fehlt das Schema, wird `http://` ergänzt,
    /// Leerzeichen am Rand werden entfernt.
    static func userInput(_ text: String) -> URL? {
        // Eingefügte Links enthalten oft Zeilenumbrüche/Leerzeichen (Umbruch in E-Mails)
        // oder HTML-kodierte „&amp;“ – beides bereinigen.
        var trimmed = text
            .components(separatedBy: .whitespacesAndNewlines).joined()
            .replacingOccurrences(of: "&amp;", with: "&")
        guard !trimmed.isEmpty else { return nil }
        if !trimmed.contains("://") { trimmed = "http://" + trimmed }
        let url = URL(string: trimmed)
            ?? trimmed.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed).flatMap(URL.init(string:))
        guard let url,
              let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              url.host() != nil else { return nil }
        return url
    }

    /// Erkennt Xtream-Panel-Links wie `…/get.php?username=…&password=…` und liefert
    /// die Zugangsdaten für die (zuverlässigere) Xtream-Schnittstelle.
    var xtreamCredentials: XtreamCredentials? {
        let file = lastPathComponent.lowercased()
        guard file == "get.php" || file == "player_api.php",
              let components = URLComponents(url: self, resolvingAgainstBaseURL: false),
              let items = components.queryItems,
              let user = items.first(where: { $0.name.lowercased() == "username" })?.value, !user.isEmpty,
              let pass = items.first(where: { $0.name.lowercased() == "password" })?.value, !pass.isEmpty,
              let server = XtreamClient.normalizedServer(absoluteString)
        else { return nil }
        return XtreamCredentials(server: server, username: user, password: pass)
    }

    /// Ergänzt bei `get.php`-Links fehlende Parameter, damit das Panel eine vollständige
    /// M3U-Liste mit Kategorien liefert (`type=m3u_plus`, `output=m3u8`).
    var completedPanelPlaylistURL: URL {
        guard lastPathComponent.lowercased() == "get.php",
              var components = URLComponents(url: self, resolvingAgainstBaseURL: false) else { return self }
        var items = components.queryItems ?? []
        if !items.contains(where: { $0.name.lowercased() == "type" }) {
            items.append(URLQueryItem(name: "type", value: "m3u_plus"))
        }
        if !items.contains(where: { $0.name.lowercased() == "output" }) {
            // HLS können Apple-Player und VLC abspielen.
            items.append(URLQueryItem(name: "output", value: "m3u8"))
        }
        components.queryItems = items
        return components.url ?? self
    }
}
