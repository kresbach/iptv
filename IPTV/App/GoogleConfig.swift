import Foundation

/// Zugangsdaten für die Google-Drive-Synchronisierung.
///
/// So bekommst du die Client-ID (einmalig, kostenlos), Details im README:
/// 1. https://console.cloud.google.com → Projekt anlegen
/// 2. „APIs & Dienste → Bibliothek“ → **Google Drive API** aktivieren
/// 3. „OAuth-Zustimmungsbildschirm“ einrichten (Typ „Extern“, dich als Testnutzer eintragen)
/// 4. „Anmeldedaten → OAuth-Client-ID erstellen“ → Typ **iOS**, Bundle-ID `com.kresbach.IPTV`
/// 5. Die angezeigte Client-ID hier eintragen.
///
/// Die Client-ID einer iOS-App ist kein Geheimnis und darf im Code stehen.
enum GoogleConfig {
    static let clientID = ""

    /// Umgekehrte Client-ID, z. B. `com.googleusercontent.apps.1234-abc`.
    static var redirectScheme: String {
        clientID.split(separator: ".").reversed().joined(separator: ".")
    }

    static var redirectURI: String { "\(redirectScheme):/oauth2redirect" }

    static var isConfigured: Bool { clientID.hasSuffix(".apps.googleusercontent.com") }

    /// Nur der versteckte App-Ordner in Drive – kein Zugriff auf andere Dateien.
    static let scopes = ["openid", "email", "https://www.googleapis.com/auth/drive.appdata"]
}
