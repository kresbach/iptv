import AuthenticationServices
import CryptoKit
import Foundation
import SwiftUI

/// Google-Anmeldung per OAuth 2.0 mit PKCE (ohne Google-SDK).
/// Der Refresh-Token liegt im Schlüsselbund, der Access-Token nur im Speicher.
@MainActor
final class GoogleAuth {
    enum AuthError: LocalizedError {
        case notConfigured
        case notSignedIn
        case invalidResponse(String)

        var errorDescription: String? {
            switch self {
            case .notConfigured: "Die Google-Client-ID fehlt. Trage sie in GoogleConfig.swift ein (siehe README)."
            case .notSignedIn: "Bitte melde dich zuerst bei Google an."
            case .invalidResponse(let detail): "Google-Anmeldung fehlgeschlagen: \(detail)"
            }
        }
    }

    private static let refreshTokenKey = "googleRefreshToken"
    private static let emailKey = "googleAccountEmail"

    private var accessToken: String?
    private var accessTokenExpiry: Date?

    var isSignedIn: Bool { Keychain.read(Self.refreshTokenKey) != nil }
    var email: String? { UserDefaults.standard.string(forKey: Self.emailKey) }

    /// Öffnet die Google-Anmeldeseite und speichert die Tokens.
    func signIn(using session: WebAuthenticationSession) async throws {
        guard GoogleConfig.isConfigured else { throw AuthError.notConfigured }

        let verifier = Self.randomVerifier()
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64URLEncoded()

        var components = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: GoogleConfig.clientID),
            URLQueryItem(name: "redirect_uri", value: GoogleConfig.redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: GoogleConfig.scopes.joined(separator: " ")),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "consent"),
        ]
        guard let authURL = components.url else { throw AuthError.invalidResponse("Ungültige Adresse") }

        let callback = try await session.authenticate(
            using: authURL,
            callbackURLScheme: GoogleConfig.redirectScheme,
            preferredBrowserSession: .ephemeral
        )
        guard let code = URLComponents(url: callback, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "code" })?.value else {
            throw AuthError.invalidResponse("Kein Autorisierungscode erhalten")
        }

        let tokens = try await Self.tokenRequest([
            "client_id": GoogleConfig.clientID,
            "code": code,
            "code_verifier": verifier,
            "grant_type": "authorization_code",
            "redirect_uri": GoogleConfig.redirectURI,
        ])
        guard let refreshToken = tokens.refreshToken else {
            throw AuthError.invalidResponse("Kein Refresh-Token erhalten")
        }
        Keychain.write(refreshToken, for: Self.refreshTokenKey)
        store(tokens)
        if let email = tokens.idToken.flatMap(Self.email(fromIDToken:)) {
            UserDefaults.standard.set(email, forKey: Self.emailKey)
        }
    }

    func signOut() {
        Keychain.delete(Self.refreshTokenKey)
        UserDefaults.standard.removeObject(forKey: Self.emailKey)
        accessToken = nil
        accessTokenExpiry = nil
    }

    /// Gültiger Access-Token, bei Bedarf über den Refresh-Token erneuert.
    func validAccessToken() async throws -> String {
        if let accessToken, let accessTokenExpiry, accessTokenExpiry > .now.addingTimeInterval(60) {
            return accessToken
        }
        guard let refreshToken = Keychain.read(Self.refreshTokenKey) else { throw AuthError.notSignedIn }
        let tokens = try await Self.tokenRequest([
            "client_id": GoogleConfig.clientID,
            "refresh_token": refreshToken,
            "grant_type": "refresh_token",
        ])
        store(tokens)
        return tokens.accessToken
    }

    // MARK: - Intern

    private struct TokenResponse: Decodable, Sendable {
        let accessToken: String
        let expiresIn: Int?
        let refreshToken: String?
        let idToken: String?
    }

    private func store(_ tokens: TokenResponse) {
        accessToken = tokens.accessToken
        accessTokenExpiry = .now.addingTimeInterval(TimeInterval(tokens.expiresIn ?? 3600))
    }

    private static func tokenRequest(_ parameters: [String: String]) async throws -> TokenResponse {
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var form = URLComponents()
        form.queryItems = parameters.map { URLQueryItem(name: $0.key, value: $0.value) }
        request.httpBody = form.percentEncodedQuery?.data(using: .utf8)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            let detail = String(decoding: data.prefix(300), as: UTF8.self)
            if detail.contains("invalid_grant") {
                Keychain.delete(refreshTokenKey)
                throw AuthError.notSignedIn
            }
            throw AuthError.invalidResponse(detail)
        }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(TokenResponse.self, from: data)
    }

    private static func randomVerifier() -> String {
        var bytes = [UInt8](repeating: 0, count: 48)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes).base64URLEncoded()
    }

    /// Liest die E-Mail-Adresse aus dem ID-Token (JWT), nur zur Anzeige.
    private static func email(fromIDToken token: String) -> String? {
        let parts = token.split(separator: ".")
        guard parts.count > 1 else { return nil }
        var payload = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while payload.count % 4 != 0 { payload += "=" }
        guard let data = Data(base64Encoded: payload),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return json["email"] as? String
    }
}

private extension Data {
    func base64URLEncoded() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
