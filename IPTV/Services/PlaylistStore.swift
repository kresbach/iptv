import Foundation
import Observation

/// Hält die aktuelle Playlist, lädt sie aus dem Netz und speichert sie lokal,
/// damit die Senderliste beim nächsten Start sofort (auch offline) da ist.
@MainActor
@Observable
final class PlaylistStore {

    enum Phase: Equatable {
        case restoring
        case empty
        case ready
    }

    private(set) var phase: Phase = .restoring
    private(set) var sourceURL: URL?
    private(set) var lastUpdated: Date?
    private(set) var channels: [Channel] = []
    private(set) var categories: [ChannelCategory] = []
    private(set) var isLoading = false
    var errorMessage: String?

    @ObservationIgnored private var channelsByCategory: [String: [Channel]] = [:]

    // MARK: - Abfragen

    func channels(in category: String) -> [Channel] {
        channelsByCategory[category] ?? []
    }

    func search(_ query: String, in source: [Channel]? = nil) -> [Channel] {
        let source = source ?? channels
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return source }
        return source.filter {
            $0.name.localizedStandardContains(query)
                || ($0.group?.localizedStandardContains(query) ?? false)
        }
    }

    // MARK: - Laden

    /// Lädt beim App-Start die zuletzt gespeicherte Playlist von der Festplatte.
    func restore() async {
        guard phase == .restoring else { return }
        let fileURL = Self.storageURL
        let snapshot = await Task.detached(priority: .userInitiated) {
            try? JSONDecoder().decode(Snapshot.self, from: Data(contentsOf: fileURL))
        }.value

        if let snapshot, !snapshot.channels.isEmpty {
            apply(channels: snapshot.channels)
            sourceURL = snapshot.sourceURL
            lastUpdated = snapshot.lastUpdated
            phase = .ready
        } else {
            phase = .empty
        }
    }

    /// Prüft die Eingabe, lädt die Playlist und ersetzt bei Erfolg die bisherige.
    /// - Returns: `true`, wenn das Laden geklappt hat.
    @discardableResult
    func load(from input: String) async -> Bool {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(),
              ["http", "https"].contains(scheme),
              url.host() != nil else {
            errorMessage = "Bitte gib eine gültige Adresse ein, die mit http:// oder https:// beginnt."
            return false
        }
        return await load(from: url)
    }

    @discardableResult
    func refresh() async -> Bool {
        guard let sourceURL else { return false }
        return await load(from: sourceURL)
    }

    func removePlaylist() {
        sourceURL = nil
        lastUpdated = nil
        apply(channels: [])
        phase = .empty
        try? FileManager.default.removeItem(at: Self.storageURL)
    }

    private func load(from url: URL) async -> Bool {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            var request = URLRequest(url: url, timeoutInterval: 60)
            request.setValue("IPTV-iOS/1.0", forHTTPHeaderField: "User-Agent")
            let (data, response) = try await URLSession.shared.data(for: request)

            if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
                throw LoadError.httpStatus(http.statusCode)
            }

            // Parsen kann bei großen Listen (> 50.000 Einträge) etwas dauern,
            // daher im Hintergrund statt auf dem Main Thread.
            let parsed = try await Task.detached(priority: .userInitiated) {
                try M3UParser.parse(String(decoding: data, as: UTF8.self))
            }.value

            apply(channels: parsed)
            sourceURL = url
            lastUpdated = .now
            phase = .ready
            await persist()
            return true
        } catch {
            errorMessage = Self.message(for: error)
            return false
        }
    }

    // MARK: - Intern

    private func apply(channels newChannels: [Channel]) {
        var grouped: [String: [Channel]] = [:]
        var order: [String] = []
        for channel in newChannels {
            let key = channel.category
            if grouped[key] == nil { order.append(key) }
            grouped[key, default: []].append(channel)
        }
        // Reihenfolge wie in der Playlist, „Ohne Kategorie“ ans Ende.
        if let index = order.firstIndex(of: Channel.ungroupedTitle) {
            order.append(order.remove(at: index))
        }

        channels = newChannels
        channelsByCategory = grouped
        categories = order.map { ChannelCategory(name: $0, count: grouped[$0]?.count ?? 0) }
    }

    private func persist() async {
        let snapshot = Snapshot(sourceURL: sourceURL, lastUpdated: lastUpdated, channels: channels)
        let fileURL = Self.storageURL
        await Task.detached(priority: .utility) {
            do {
                try FileManager.default.createDirectory(
                    at: fileURL.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
                try JSONEncoder().encode(snapshot).write(to: fileURL, options: .atomic)
            } catch {
                print("Playlist konnte nicht gespeichert werden: \(error)")
            }
        }.value
    }

    private static var storageURL: URL {
        URL.applicationSupportDirectory.appending(path: "playlist.json")
    }

    private struct Snapshot: Codable, Sendable {
        let sourceURL: URL?
        let lastUpdated: Date?
        let channels: [Channel]
    }

    private enum LoadError: LocalizedError {
        case httpStatus(Int)

        var errorDescription: String? {
            switch self {
            case .httpStatus(let code):
                "Der Server hat mit Fehler \(code) geantwortet. Bitte prüfe die Adresse."
            }
        }
    }

    private static func message(for error: Error) -> String {
        if let urlError = error as? URLError {
            switch urlError.code {
            case .notConnectedToInternet: return "Keine Internetverbindung."
            case .timedOut: return "Der Server antwortet nicht (Zeitüberschreitung)."
            case .cannotFindHost, .dnsLookupFailed: return "Der Server wurde nicht gefunden."
            default: break
            }
        }
        return error.localizedDescription
    }
}
