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
        var trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        if !trimmed.contains("://") { trimmed = "http://" + trimmed }
        let url = URL(string: trimmed)
            ?? trimmed.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed).flatMap(URL.init(string:))
        guard let url,
              let scheme = url.scheme?.lowercased(), ["http", "https"].contains(scheme),
              url.host() != nil else { return nil }
        return url
    }
}
