import UIKit

/// Nur für Hintergrund-Downloads: iOS weckt die App, wenn Downloads fertig sind.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        guard identifier == DownloadManager.sessionIdentifier else {
            completionHandler()
            return
        }
        // Manager anlegen (verbindet sich wieder mit der Session) und Abschluss merken.
        DownloadManager.shared.backgroundCompletionHandler = completionHandler
    }
}
