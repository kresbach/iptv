import Foundation

/// Findet heraus, warum ein Stream nicht startet: fragt den Stream-Link an
/// (nur die Antwort-Header, keine Daten) und bei Xtream den Kontostatus.
enum StreamDiagnostics {

    struct Finding: Identifiable, Sendable {
        enum Level: Sendable { case ok, warning, problem }
        let id = UUID()
        let level: Level
        let text: String
    }

    static func run(for channel: Channel, source: PlaylistSource?) async -> [Finding] {
        var findings: [Finding] = []

        if case .xtream(let credentials)? = source {
            findings += await accountFindings(XtreamClient(credentials: credentials))
        }
        findings += await streamFindings(channel.url)
        return findings
    }

    private static func accountFindings(_ client: XtreamClient) async -> [Finding] {
        do {
            let account = try await client.accountStatus()
            var result: [Finding] = []
            guard account.isAuthenticated else {
                return [Finding(level: .problem, text: "Anmeldung abgelehnt: Benutzername oder Passwort stimmen nicht (mehr).")]
            }
            let status = account.status ?? "unbekannt"
            result.append(Finding(
                level: status.lowercased() == "active" ? .ok : .problem,
                text: "Kontostatus: \(status)"
            ))
            if let expires = account.expires {
                result.append(Finding(
                    level: expires < .now ? .problem : .ok,
                    text: "Gültig bis: \(expires.formatted(date: .abbreviated, time: .shortened))"
                ))
            }
            if let active = account.activeConnections, let max = account.maxConnections {
                result.append(Finding(
                    level: active >= max ? .problem : .ok,
                    text: active >= max
                        ? "Verbindungen: \(active) von \(max) belegt – das Limit ist erreicht. Nach dem Schließen eines Streams zählt der Server die Verbindung oft noch bis zu einer Minute mit. Bleibt es dauerhaft belegt, nutzt ein anderes Gerät den Zugang."
                        : "Verbindungen: \(active) von \(max) belegt"
                ))
            }
            if !account.allowedFormats.isEmpty {
                result.append(Finding(level: .ok, text: "Erlaubte Formate: \(account.allowedFormats.joined(separator: ", "))"))
            }
            return result
        } catch {
            return [Finding(level: .problem, text: "Kontostatus nicht abrufbar: \(HTTPClient.message(for: error))")]
        }
    }

    private static func streamFindings(_ url: URL) async -> [Finding] {
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue(HTTPClient.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("bytes=0-1023", forHTTPHeaderField: "Range")

        do {
            // `bytes(for:)` kehrt nach den Headern zurück; danach wird sofort abgebrochen,
            // damit keine Verbindung offen bleibt.
            let (bytes, response) = try await URLSession.shared.bytes(for: request)
            bytes.task.cancel()

            guard let http = response as? HTTPURLResponse else {
                return [Finding(level: .warning, text: "Unerwartete Antwort vom Server.")]
            }
            var result: [Finding] = []
            let type = http.value(forHTTPHeaderField: "Content-Type") ?? "unbekannt"
            let host = http.url?.host() ?? url.host() ?? "?"

            switch http.statusCode {
            case 200..<300:
                let looksLikeHTML = type.contains("text/html")
                result.append(Finding(
                    level: looksLikeHTML ? .problem : .ok,
                    text: looksLikeHTML
                        ? "Der Server liefert eine Webseite statt eines Videos (\(type)). Meist bedeutet das: Zugang gesperrt oder Link abgelaufen."
                        : "Stream erreichbar (HTTP \(http.statusCode), \(type))."
                ))
            case 401, 403:
                result.append(Finding(level: .problem, text: "Der Server verweigert den Zugriff (HTTP \(http.statusCode)). Häufige Gründe: Verbindungslimit erreicht, vorübergehende Sperre nach zu vielen Versuchen oder Konto abgelaufen."))
            case 404:
                result.append(Finding(level: .problem, text: "Den Stream gibt es auf dem Server nicht (HTTP 404). Aktualisiere die Playlist."))
            case 458, 429:
                result.append(Finding(level: .problem, text: "Zu viele Anfragen (HTTP \(http.statusCode)). Der Anbieter hat vorübergehend gesperrt – warte einige Minuten."))
            default:
                result.append(Finding(level: .problem, text: "Der Server antwortet mit Fehler \(http.statusCode)."))
            }
            if host != url.host() {
                result.append(Finding(level: .ok, text: "Weitergeleitet zu: \(host)"))
            }
            return result
        } catch {
            return [Finding(level: .problem, text: "Stream nicht erreichbar: \(HTTPClient.message(for: error))")]
        }
    }
}
