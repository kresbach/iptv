import SwiftUI
import UIKit

/// Zentrale Navigation: gewählter Bereich und App-weite Blätter. So können Menüleiste
/// und Tastaturkürzel (Mac, iPad mit Tastatur) die Oberfläche steuern.
@MainActor
@Observable
final class AppRouter {
    static let shared = AppRouter()

    var selection: AppTab = .home
    var showsSettings = false
    var showsAddPlaylist = false

    /// Läuft die iPad-App auf einem Mac?
    static let isMac = ProcessInfo.processInfo.isiOSAppOnMac

    /// Mehrere Fenster sind auf dem Mac und dem iPad möglich, nicht auf dem iPhone.
    static var supportsWindows: Bool { UIApplication.shared.supportsMultipleScenes }
}

enum AppTab: Hashable {
    case home
    case section(ContentKind)
    case downloads
    case search
}

/// Inhalt eines eigenen Player-Fensters. Wird von iOS gespeichert, um das Fenster
/// wiederherzustellen, daher `Codable`.
struct PlayerWindowRequest: Codable, Hashable {
    let channel: Channel
    let queue: [Channel]

    static let windowID = "player"

    /// Höchstens 400 Nachbarn mitgeben, damit das Fenster schnell öffnet.
    init(channel: Channel, queue: [Channel]) {
        self.channel = channel
        guard queue.count > 400, let index = queue.firstIndex(where: { $0.id == channel.id }) else {
            self.queue = queue
            return
        }
        let start = max(index - 200, 0)
        self.queue = Array(queue[start..<min(start + 400, queue.count)])
    }
}

/// Menüpunkt „In neuem Fenster öffnen“ (nur wo mehrere Fenster möglich sind).
struct OpenInWindowButton: View {
    @Environment(\.openWindow) private var openWindow
    let channel: Channel
    var queue: [Channel] = []

    var body: some View {
        if AppRouter.supportsWindows {
            Button("In neuem Fenster öffnen", systemImage: "macwindow.badge.plus") {
                openWindow(id: PlayerWindowRequest.windowID, value: PlayerWindowRequest(channel: channel, queue: queue))
            }
        }
    }
}

/// App-weite Menüeinträge und Tastaturkürzel.
struct AppCommands: Commands {
    var body: some Commands {
        CommandGroup(replacing: .appSettings) {
            Button("Einstellungen …") { AppRouter.shared.showsSettings = true }
                .keyboardShortcut(",")
        }
        CommandGroup(after: .newItem) {
            // ⌘N ist für „Neues Fenster“ vom System belegt.
            Button("Playlist hinzufügen …") { AppRouter.shared.showsAddPlaylist = true }
                .keyboardShortcut("n", modifiers: [.command, .shift])
        }
        CommandMenu("Navigation") {
            Button("Start") { AppRouter.shared.selection = .home }
                .keyboardShortcut("1")
            Button("Live TV") { AppRouter.shared.selection = .section(.live) }
                .keyboardShortcut("2")
            Button("Filme") { AppRouter.shared.selection = .section(.movie) }
                .keyboardShortcut("3")
            Button("Serien") { AppRouter.shared.selection = .section(.series) }
                .keyboardShortcut("4")
            Button("Downloads") { AppRouter.shared.selection = .downloads }
                .keyboardShortcut("5")
            Divider()
            Button("Suchen") { AppRouter.shared.selection = .search }
                .keyboardShortcut("f")
            Button("Playlist aktualisieren") { Task { await PlaylistStore.shared.refresh() } }
                .keyboardShortcut("r")
        }
    }
}
