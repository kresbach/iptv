import Foundation

/// Einfache Downloads mit einem User-Agent, den IPTV-Server akzeptieren.
/// Viele Anbieter sperren unbekannte Clients und lassen nur gängige Player (VLC) zu.
enum HTTPClient {
    static let userAgent = "VLC/3.0.21 LibVLC/3.0.21"

    private static let session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 60
        configuration.timeoutIntervalForResource = 600
        configuration.httpAdditionalHeaders = ["User-Agent": userAgent]
        configuration.waitsForConnectivity = false
        configuration.urlCache = URLCache(memoryCapacity: 20_000_000, diskCapacity: 200_000_000)
        configuration.httpMaximumConnectionsPerHost = 6
        return URLSession(configuration: configuration)
    }()

    static func data(from url: URL) async throws -> Data {
        let (data, response) = try await session.data(from: url)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw LoadError.httpStatus(http.statusCode)
        }
        return data
    }

    enum LoadError: LocalizedError {
        case httpStatus(Int)

        var errorDescription: String? {
            switch self {
            case .httpStatus(401), .httpStatus(403):
                "Zugriff verweigert (Fehler 401/403). Bitte prüfe Adresse bzw. Zugangsdaten."
            case .httpStatus(404):
                "Die Adresse wurde auf dem Server nicht gefunden (Fehler 404)."
            case .httpStatus(let code):
                "Der Server hat mit Fehler \(code) geantwortet."
            }
        }
    }

    /// Verständliche Fehlermeldung auf Deutsch.
    static func message(for error: Error) -> String {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .notConnectedToInternet: return "Keine Internetverbindung."
            case .timedOut: return "Der Server antwortet nicht (Zeitüberschreitung)."
            case .cannotFindHost, .dnsLookupFailed: return "Der Server wurde nicht gefunden. Bitte prüfe die Adresse."
            case .cannotConnectToHost: return "Keine Verbindung zum Server möglich. Stimmen Adresse und Port?"
            case .appTransportSecurityRequiresSecureConnection:
                return "Die Verbindung wurde von iOS blockiert (unsicheres http)."
            case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate:
                return "Die sichere Verbindung ist fehlgeschlagen. Versuche die Adresse mit http:// statt https://."
            default: break
            }
        }
        return error.localizedDescription
    }
}
