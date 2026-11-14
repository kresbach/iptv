import AuthenticationServices
import Foundation
import Observation
import SwiftUI
import UIKit

/// Inhalt der Sync-Datei in Google Drive.
struct SyncSnapshot: Codable, Sendable {
    var version = 1
    var updatedAt: Date
    var favorites: [String: UserDataStore.FavoriteState]
    var history: [UserDataStore.HistoryEntry]
    var progress: [String: UserDataStore.PlaybackProgress]
    var settings: SyncedSettings?
    /// Nur gesetzt, wenn „Playlists synchronisieren“ aktiv ist (enthält Zugangsdaten).
    var playlists: [Playlist]?
    var deletedPlaylists: [UUID: Date]?
}

/// Synchronisierte Einstellungen; die jeweils neuere gewinnt.
struct SyncedSettings: Codable, Sendable {
    var playerLive: String
    var playerVOD: String
    var changedAt: Date

    static let changedAtKey = "settingsChangedAt"
    static let syncPlaylistsKey = "syncPlaylists"

    @MainActor
    static var current: SyncedSettings {
        let defaults = UserDefaults.standard
        return SyncedSettings(
            playerLive: defaults.string(forKey: PlayerEngine.liveStorageKey) ?? PlayerEngine.liveDefault.rawValue,
            playerVOD: defaults.string(forKey: PlayerEngine.vodStorageKey) ?? PlayerEngine.vodDefault.rawValue,
            changedAt: defaults.object(forKey: changedAtKey) as? Date ?? .distantPast
        )
    }

    @MainActor
    func apply() {
        let defaults = UserDefaults.standard
        defaults.set(playerLive, forKey: PlayerEngine.liveStorageKey)
        defaults.set(playerVOD, forKey: PlayerEngine.vodStorageKey)
        defaults.set(changedAt, forKey: Self.changedAtKey)
    }

    /// Nach einer Änderung in den Einstellungen aufrufen.
    @MainActor
    static func markChanged() {
        UserDefaults.standard.set(Date.now, forKey: changedAtKey)
    }
}

/// Gleicht Favoriten, Verlauf, Fortschritt, Einstellungen und optional Playlists
/// über eine Datei im versteckten App-Ordner von Google Drive ab.
@MainActor
@Observable
final class SyncService {
    private(set) var isSyncing = false
    private(set) var lastSync: Date?
    private(set) var lastError: String?
    private(set) var accountEmail: String?
    private(set) var isSignedIn = false

    @ObservationIgnored private let auth = GoogleAuth()
    @ObservationIgnored private var debounceTask: Task<Void, Never>?
    private static let lastSyncKey = "lastGoogleSync"

    init() {
        isSignedIn = auth.isSignedIn
        accountEmail = auth.email
        lastSync = UserDefaults.standard.object(forKey: Self.lastSyncKey) as? Date
    }

    var isConfigured: Bool { GoogleConfig.isConfigured }

    func signIn(using session: WebAuthenticationSession, store: PlaylistStore, userData: UserDataStore) async {
        lastError = nil
        do {
            try await auth.signIn(using: session)
            isSignedIn = true
            accountEmail = auth.email
            await sync(store: store, userData: userData)
        } catch let error as ASWebAuthenticationSessionError where error.code == .canceledLogin {
            // Nutzer hat die Anmeldung abgebrochen – kein Fehler.
        } catch {
            lastError = error.localizedDescription
        }
    }

    func signOut() {
        auth.signOut()
        isSignedIn = false
        accountEmail = nil
        lastSync = nil
        UserDefaults.standard.removeObject(forKey: Self.lastSyncKey)
    }

    /// Nach lokalen Änderungen: in 20 Sekunden abgleichen (mehrere Änderungen gebündelt).
    func scheduleSync(store: PlaylistStore, userData: UserDataStore) {
        guard isSignedIn else { return }
        debounceTask?.cancel()
        debounceTask = Task {
            try? await Task.sleep(for: .seconds(20))
            guard !Task.isCancelled else { return }
            await sync(store: store, userData: userData)
        }
    }

    /// Herunterladen, zusammenführen, lokal übernehmen und wieder hochladen.
    func sync(store: PlaylistStore, userData: UserDataStore) async {
        guard isSignedIn, GoogleConfig.isConfigured, !isSyncing else { return }
        isSyncing = true
        lastError = nil
        defer { isSyncing = false }

        // Im Hintergrund noch zu Ende synchronisieren dürfen.
        let backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "GoogleDriveSync")
        defer { UIApplication.shared.endBackgroundTask(backgroundTask) }

        do {
            let token = try await auth.validAccessToken()
            let drive = GoogleDriveClient(accessToken: token)
            let remoteFile = try await drive.download()

            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            let remote = remoteFile.flatMap { try? decoder.decode(SyncSnapshot.self, from: $0.data) }

            let syncPlaylists = UserDefaults.standard.bool(forKey: SyncedSettings.syncPlaylistsKey)
            let local = localSnapshot(store: store, userData: userData, includePlaylists: syncPlaylists)
            let merged = remote.map { Self.merge(local: local, remote: $0) } ?? local

            // Lokal übernehmen.
            userData.applySync(favorites: merged.favorites, history: merged.history, progress: merged.progress)
            if let settings = merged.settings, settings.changedAt > SyncedSettings.current.changedAt {
                settings.apply()
            }
            if syncPlaylists, let playlists = merged.playlists {
                await store.applySyncedPlaylists(playlists, deleted: merged.deletedPlaylists ?? [:])
            }

            // Hochladen.
            var upload = merged
            upload.updatedAt = .now
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            try await drive.upload(try encoder.encode(upload), existingFileID: remoteFile?.fileID)

            lastSync = .now
            UserDefaults.standard.set(lastSync, forKey: Self.lastSyncKey)
        } catch GoogleAuth.AuthError.notSignedIn {
            isSignedIn = false
            lastError = "Die Google-Anmeldung ist abgelaufen. Bitte melde dich erneut an."
        } catch {
            lastError = error.localizedDescription
        }
    }

    private func localSnapshot(store: PlaylistStore, userData: UserDataStore, includePlaylists: Bool) -> SyncSnapshot {
        let state = userData.syncState()
        return SyncSnapshot(
            updatedAt: .now,
            favorites: state.favorites,
            history: state.history,
            progress: state.progress,
            settings: SyncedSettings.current,
            playlists: includePlaylists ? store.playlists : nil,
            deletedPlaylists: includePlaylists ? store.deletedPlaylists : nil
        )
    }

    /// Führt zwei Stände zusammen; je Eintrag gewinnt die neuere Änderung.
    static func merge(local: SyncSnapshot, remote: SyncSnapshot) -> SyncSnapshot {
        var result = local

        result.favorites = local.favorites.merging(remote.favorites) { mine, theirs in
            theirs.changedAt > mine.changedAt ? theirs : mine
        }

        var history: [String: UserDataStore.HistoryEntry] = [:]
        for entry in local.history + remote.history where (history[entry.key]?.date ?? .distantPast) < entry.date {
            history[entry.key] = entry
        }
        result.history = history.values.sorted { $0.date > $1.date }

        result.progress = local.progress.merging(remote.progress) { mine, theirs in
            theirs.updated > mine.updated ? theirs : mine
        }

        if let theirs = remote.settings, theirs.changedAt > (local.settings?.changedAt ?? .distantPast) {
            result.settings = theirs
        }

        let deleted = (local.deletedPlaylists ?? [:]).merging(remote.deletedPlaylists ?? [:]) { max($0, $1) }
        if let mine = local.playlists {
            var byID = Dictionary(uniqueKeysWithValues: mine.map { ($0.id, $0) })
            for playlist in remote.playlists ?? [] where byID[playlist.id] == nil {
                byID[playlist.id] = playlist
            }
            result.playlists = byID.values.filter { deleted[$0.id] == nil }.sorted { $0.name < $1.name }
            result.deletedPlaylists = deleted
        } else {
            // Dieses Gerät synchronisiert keine Playlists: die der anderen Geräte erhalten.
            result.playlists = remote.playlists
            result.deletedPlaylists = remote.deletedPlaylists
        }
        return result
    }
}
