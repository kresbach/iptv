import SwiftUI

enum Theme {
    static let orange = Color(red: 1.0, green: 0.478, blue: 0.0)
    static let orangeLight = Color(red: 1.0, green: 0.66, blue: 0.1)
    static let orangeDeep = Color(red: 0.96, green: 0.32, blue: 0.0)

    static let gradient = LinearGradient(
        colors: [orangeLight, orangeDeep],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )

    /// Dezenter Hintergrundverlauf für Übersichtsseiten.
    static let backgroundGlow = LinearGradient(
        colors: [orange.opacity(0.22), .clear],
        startPoint: .top,
        endPoint: .center
    )
}
