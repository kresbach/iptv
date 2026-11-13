import Foundation
import Observation

/// Favoriten, zuletzt gesehene Einträge und Wiedergabefortschritt.
@MainActor
@Observable
final class UserDataStore {

    struct HistoryEntry: Codable, Hashable, Sendable {
        let key: String
        /// Momentaufnahme, damit der Eintrag auch ohne geladene Playlist abspielbar bleibt.
        let channel: Channel
        var date: Date
    }

    struct PlaybackProgress: Codable, Hashable, Sendable {
        let channel: Channel
        var position: Double
        var duration: Double
        var updated: Date

        var fraction: Double { duration > 0 ? min(position / duration, 1) : 0 }
        var isFinished: Bool { fraction > 0.95 }
        var remaining: Double { max(duration - position, 0) }
    }

    private struct Snapshot: Codable, Sendable {
        var favorites: [String] = []
        var history: [HistoryEntry] = []
        var progress: [String: PlaybackProgress] = [:]
    }

    private(set) var favorites: [String] = []
    private(set) var history: [HistoryEntry] = []
    private(set) var progress: [String: PlaybackProgress] = [:]

    @ObservationIgnored private var favoriteSet: Set<String> = []
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    private static let fileName = "userdata.json"
    private static let historyLimit = 40
    private static let progressLimit = 300

    func restore() async {
        guard let snapshot = await Storage.read(Snapshot.self, from: Self.fileName) else { return }
        favorites = snapshot.favorites
        favoriteSet = Set(snapshot.favorites)
        history = snapshot.history
        progress = snapshot.progress
    }

    // MARK: - Favoriten

    func isFavorite(_ key: String) -> Bool { favoriteSet.contains(key) }
    func isFavorite(_ channel: Channel) -> Bool { isFavorite(channel.stableKey) }
    func isFavorite(_ show: SeriesShow) -> Bool { isFavorite(show.favoriteKey) }

    func toggleFavorite(_ key: String) {
        if favoriteSet.remove(key) != nil {
            favorites.removeAll { $0 == key }
        } else {
            favoriteSet.insert(key)
            favorites.insert(key, at: 0)
        }
        scheduleSave()
    }

    func toggleFavorite(_ channel: Channel) { toggleFavorite(channel.stableKey) }
    func toggleFavorite(_ show: SeriesShow) { toggleFavorite(show.favoriteKey) }

    // MARK: - Verlauf

    func recordPlayback(of channel: Channel) {
        history.removeAll { $0.key == channel.stableKey }
        history.insert(HistoryEntry(key: channel.stableKey, channel: channel, date: .now), at: 0)
        if history.count > Self.historyLimit { history.removeLast(history.count - Self.historyLimit) }
        scheduleSave()
    }

    func clearHistory() {
        history = []
        progress = [:]
        scheduleSave()
    }

    // MARK: - Fortschritt (Filme und Episoden)

    func playbackProgress(for channel: Channel) -> PlaybackProgress? { progress[channel.stableKey] }

    func saveProgress(for channel: Channel, position: Double, duration: Double) {
        guard channel.kind != .live, position.isFinite, duration.isFinite, duration > 60 else { return }
        progress[channel.stableKey] = PlaybackProgress(channel: channel, position: position, duration: duration, updated: .now)
        if progress.count > Self.progressLimit,
           let oldest = progress.min(by: { $0.value.updated < $1.value.updated })?.key {
            progress[oldest] = nil
        }
        scheduleSave()
    }

    /// Startposition zum Weiterschauen, falls sinnvoll (mind. 30 s gesehen, noch nicht fertig).
    func resumePosition(for channel: Channel) -> Double? {
        guard let entry = progress[channel.stableKey], entry.position > 30, !entry.isFinished else { return nil }
        return max(entry.position - 5, 0)
    }

    /// Angefangene Filme und Episoden, zuletzt gesehene zuerst.
    var continueWatching: [PlaybackProgress] {
        progress.values
            .filter { $0.position > 30 && !$0.isFinished }
            .sorted { $0.updated > $1.updated }
    }

    // MARK: - Speichern

    private func scheduleSave() {
        saveTask?.cancel()
        let snapshot = Snapshot(favorites: favorites, history: history, progress: progress)
        saveTask = Task {
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            await Storage.write(snapshot, to: Self.fileName)
        }
    }
}
