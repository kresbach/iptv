import Foundation

/// Entfernt Zugangsdaten aus Stream-Links, bevor sie in Google Drive landen, und setzt
/// sie beim Herunterladen mit den lokal gespeicherten Daten wieder ein.
///
/// Xtream-Links enthalten Benutzername und Passwort im Pfad (`/movie/USER/PASS/123.mkv`),
/// M3U-Links oft als Parameter (`?username=…&password=…`).
struct CredentialRedactor {
    private struct Credentials {
        let host: String?
        let username: String
        let password: String
    }

    private static let userPlaceholder = "__IPTV_USER__"
    private static let passwordPlaceholder = "__IPTV_PASS__"

    private let credentials: [Credentials]

    init(playlists: [Playlist]) {
        credentials = playlists.compactMap { playlist in
            switch playlist.source {
            case .xtream(let xtream):
                return Credentials(host: xtream.server.host(), username: xtream.username, password: xtream.password)
            case .m3u(let url):
                let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
                guard let user = items.first(where: { $0.name == "username" })?.value,
                      let pass = items.first(where: { $0.name == "password" })?.value,
                      !user.isEmpty, !pass.isEmpty else { return nil }
                return Credentials(host: url.host(), username: user, password: pass)
            case .file:
                return nil
            }
        }
    }

    /// Ersetzt bekannte Zugangsdaten im Link durch Platzhalter.
    func redact(_ channel: Channel) -> Channel {
        guard var components = URLComponents(url: channel.url, resolvingAgainstBaseURL: false) else { return channel }
        let host = channel.url.host()
        var changed = false

        for credential in credentials where credential.host == nil || credential.host == host {
            // Auf dem kodierten Pfad arbeiten: ein ungültig kodierter Pfad würde URLComponents abstürzen lassen.
            let segments = components.percentEncodedPath.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
            let replaced = segments.map { segment -> String in
                let decoded = segment.removingPercentEncoding ?? segment
                if decoded == credential.username { return Self.userPlaceholder }
                if decoded == credential.password { return Self.passwordPlaceholder }
                return segment
            }
            if replaced != segments {
                components.percentEncodedPath = replaced.joined(separator: "/")
                changed = true
            }
            if let items = components.queryItems {
                components.queryItems = items.map { item in
                    if item.name == "username", item.value == credential.username {
                        return URLQueryItem(name: item.name, value: Self.userPlaceholder)
                    }
                    if item.name == "password", item.value == credential.password {
                        return URLQueryItem(name: item.name, value: Self.passwordPlaceholder)
                    }
                    return item
                }
                changed = changed || components.queryItems != items
            }
        }
        guard changed, let url = components.url else { return channel }
        return channel.with(url: url)
    }

    /// Setzt die Zugangsdaten der passenden lokalen Playlist (gleicher Server) wieder ein.
    func restore(_ channel: Channel) -> Channel {
        let text = channel.url.absoluteString
        guard text.contains(Self.userPlaceholder) || text.contains(Self.passwordPlaceholder),
              let credential = credentials.first(where: { $0.host == channel.url.host() }) ?? credentials.first
        else { return channel }

        let user = credential.username.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? credential.username
        let pass = credential.password.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? credential.password
        let restored = text
            .replacingOccurrences(of: Self.userPlaceholder, with: user)
            .replacingOccurrences(of: Self.passwordPlaceholder, with: pass)
        return URL(string: restored).map { channel.with(url: $0) } ?? channel
    }
}
