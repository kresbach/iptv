import Foundation
import Observation

/// Verwaltet alle Playlists, lädt die aktive Playlist (M3U-Link, Datei oder Xtream)
/// samt TV-Guide und speichert alles lokal, damit die App sofort und offline startet.
@MainActor
@Observable
final class PlaylistStore {

    enum Phase: Equatable {
        case restoring
        case empty
        case ready
    }

    private(set) var phase: Phase = .restoring
    private(set) var playlists: [Playlist] = []
    private(set) var activeID: UUID?
    private(set) var content = PlaylistContent.empty
    private(set) var epg: EPGGuide?
    private(set) var isLoading = false
    private(set) var isLoadingEPG = false
    var errorMessage: String?
    private(set) var epgErrorMessage: String?

    @ObservationIgnored private var episodeCache: [String: [SeriesShow.Episode]] = [:]
    @ObservationIgnored private var loadGeneration = 0

    private static let libraryFile = "library.json"
    private static let epgMaxAge: TimeInterval = 6 * 3600

    // MARK: - Abfragen

    var activePlaylist: Playlist? { playlists.first { $0.id == activeID } }

    func section(_ kind: ContentKind) -> ContentSection { content.section(kind) }

    func channel(forKey key: String) -> Channel? { content.channel(forKey: key) }

    func search(_ query: String, in source: [Channel]) -> [Channel] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return source }
        return source.filter {
            $0.name.localizedStandardContains(query)
                || ($0.group?.localizedStandardContains(query) ?? false)
        }
    }

    func searchShows(_ query: String, in source: [SeriesShow]) -> [SeriesShow] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return source }
        return source.filter {
            $0.title.localizedStandardContains(query) || $0.category.localizedStandardContains(query)
        }
    }

    // MARK: - Start

    /// Lädt beim App-Start Bibliothek, Zwischenspeicher und TV-Guide.
    func restore() async {
        guard phase == .restoring else { return }

        if let library = await Storage.read(Library.self, from: Self.libraryFile) {
            playlists = library.playlists
            activeID = library.activeID ?? library.playlists.first?.id
        } else {
            await migrateLegacyPlaylist()
        }

        guard let playlist = activePlaylist else {
            phase = .empty
            return
        }

        if let data = await Storage.read(PlaylistData.self, from: Self.contentFile(playlist.id)) {
            content = await Self.buildContent(data)
            phase = .ready
            epg = await Storage.read(EPGGuide.self, from: Self.epgFile(playlist.id))
            await refreshEPG(force: false)
        } else {
            phase = .ready
            await refresh()
        }
    }

    // MARK: - Playlists verwalten

    /// Legt eine Playlist an und lädt sie. Bei Fehlern wird nichts gespeichert.
    /// - Parameter fileData: Inhalt einer importierten Datei (nur bei `.file`).
    @discardableResult
    func addPlaylist(name: String, source: PlaylistSource, fileData: Data? = nil) async -> Bool {
        var playlist = Playlist(name: name, source: source)
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            if let fileData {
                try Storage.writeRaw(fileData, to: Self.sourceFile(playlist.id))
            }
            let (data, detectedEPG) = try await fetchData(for: playlist)
            playlist.detectedEPGURL = detectedEPG
            playlist.lastUpdated = .now

            playlists.append(playlist)
            activeID = playlist.id
            content = await Self.buildContent(data)
            epg = nil
            episodeCache = [:]
            phase = .ready

            await Storage.write(data, to: Self.contentFile(playlist.id))
            await saveLibrary()
            await refreshEPG(force: true)
            return true
        } catch {
            Storage.remove(Self.sourceFile(playlist.id))
            errorMessage = HTTPClient.message(for: error)
            return false
        }
    }

    /// Wechselt zur angegebenen Playlist (aus dem Zwischenspeicher oder neu geladen).
    func activate(_ id: UUID) async {
        guard id != activeID, let playlist = playlists.first(where: { $0.id == id }) else { return }
        loadGeneration += 1
        let generation = loadGeneration

        activeID = id
        content = .empty
        epg = nil
        episodeCache = [:]
        errorMessage = nil
        await saveLibrary()

        if let data = await Storage.read(PlaylistData.self, from: Self.contentFile(playlist.id)) {
            let built = await Self.buildContent(data)
            guard generation == loadGeneration else { return }
            content = built
            epg = await Storage.read(EPGGuide.self, from: Self.epgFile(playlist.id))
            await refreshEPG(force: false)
        } else {
            await refresh()
        }
    }

    /// Lädt die aktive Playlist neu vom Server.
    @discardableResult
    func refresh() async -> Bool {
        guard let playlist = activePlaylist else { return false }
        loadGeneration += 1
        let generation = loadGeneration
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }

        do {
            let (data, detectedEPG) = try await fetchData(for: playlist)
            let built = await Self.buildContent(data)
            guard generation == loadGeneration else { return false }

            content = built
            episodeCache = [:]
            update(playlist.id) {
                $0.detectedEPGURL = detectedEPG
                $0.lastUpdated = .now
            }
            await Storage.write(data, to: Self.contentFile(playlist.id))
            await saveLibrary()
            await refreshEPG(force: false)
            return true
        } catch {
            errorMessage = HTTPClient.message(for: error)
            return false
        }
    }

    func rename(_ id: UUID, to name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        update(id) { $0.name = trimmed }
        await saveLibrary()
    }

    func setCustomEPGURL(_ url: URL?, for id: UUID) async {
        update(id) { $0.customEPGURL = url }
        await saveLibrary()
        if id == activeID { await refreshEPG(force: true) }
    }

    func delete(_ id: UUID) async {
        playlists.removeAll { $0.id == id }
        for file in [Self.contentFile(id), Self.epgFile(id), Self.sourceFile(id)] {
            Storage.remove(file)
        }

        if activeID == id {
            activeID = nil
            content = .empty
            epg = nil
            if let next = playlists.first {
                await activate(next.id)
            } else {
                phase = .empty
            }
        }
        await saveLibrary()
    }

    // MARK: - Serien (Xtream lädt Episoden erst beim Öffnen)

    func episodes(for show: SeriesShow) async throws -> [SeriesShow.Episode] {
        guard show.needsEpisodeLoading else { return show.episodes }
        if let cached = episodeCache[show.id] { return cached }
        guard case .xtream(let credentials)? = activePlaylist?.source else { return [] }

        let episodes = try await XtreamClient(credentials: credentials).episodes(for: show)
        episodeCache[show.id] = episodes
        return episodes
    }

    // MARK: - TV-Guide

    func refreshEPG(force: Bool) async {
        guard let playlist = activePlaylist, let url = playlist.epgURL else {
            epgErrorMessage = nil
            return
        }
        if !force, let epg, Date.now.timeIntervalSince(epg.fetchedAt) < Self.epgMaxAge { return }
        guard !isLoadingEPG else { return }

        isLoadingEPG = true
        epgErrorMessage = nil
        defer { isLoadingEPG = false }

        let channels = content.live.items
        do {
            let guide = try await Task.detached(priority: .utility) {
                try await EPGService.load(from: url, for: channels)
            }.value
            guard playlist.id == activeID else { return }
            epg = guide
            await Storage.write(guide, to: Self.epgFile(playlist.id))
        } catch {
            epgErrorMessage = HTTPClient.message(for: error)
        }
    }

    // MARK: - Intern

    private func fetchData(for playlist: Playlist) async throws -> (PlaylistData, URL?) {
        switch playlist.source {
        case .m3u(let url):
            let raw = try await HTTPClient.data(from: url)
            return try await Self.parseM3U(raw)

        case .file:
            let fileURL = Storage.url(Self.sourceFile(playlist.id))
            let raw = try await Task.detached { try Data(contentsOf: fileURL) }.value
            return try await Self.parseM3U(raw)

        case .xtream(let credentials):
            let client = XtreamClient(credentials: credentials)
            let data = try await client.loadContent()
            return (data, client.xmltvURL)
        }
    }

    /// Parsen und Einsortieren kann bei großen Listen (> 50.000 Einträge) dauern,
    /// daher im Hintergrund statt auf dem Main Thread.
    private static func parseM3U(_ raw: Data) async throws -> (PlaylistData, URL?) {
        try await Task.detached(priority: .userInitiated) {
            let result = try M3UParser.parse(String(decoding: raw, as: UTF8.self))
            return (PlaylistData(channels: result.channels), result.epgURL)
        }.value
    }

    private static func buildContent(_ data: PlaylistData) async -> PlaylistContent {
        await Task.detached(priority: .userInitiated) { PlaylistContent(data: data) }.value
    }

    private func update(_ id: UUID, _ change: (inout Playlist) -> Void) {
        guard let index = playlists.firstIndex(where: { $0.id == id }) else { return }
        change(&playlists[index])
    }

    private func saveLibrary() async {
        await Storage.write(Library(playlists: playlists, activeID: activeID), to: Self.libraryFile)
    }

    /// Übernimmt die Playlist aus Version 1 (eine einzelne `playlist.json`).
    private func migrateLegacyPlaylist() async {
        let legacyURL = URL.applicationSupportDirectory.appending(path: "playlist.json")
        let legacy = await Task.detached {
            (try? Data(contentsOf: legacyURL)).flatMap { try? JSONDecoder().decode(LegacySnapshot.self, from: $0) }
        }.value
        guard let legacy, let sourceURL = legacy.sourceURL else { return }

        var playlist = Playlist(name: sourceURL.host() ?? "Meine Playlist", source: .m3u(url: sourceURL))
        playlist.lastUpdated = legacy.lastUpdated
        playlists = [playlist]
        activeID = playlist.id
        await Storage.write(PlaylistData(channels: legacy.channels), to: Self.contentFile(playlist.id))
        await saveLibrary()
        try? FileManager.default.removeItem(at: legacyURL)
    }

    private struct LegacySnapshot: Codable, Sendable {
        let sourceURL: URL?
        let lastUpdated: Date?
        let channels: [Channel]
    }

    private struct Library: Codable, Sendable {
        let playlists: [Playlist]
        let activeID: UUID?
    }

    private static func contentFile(_ id: UUID) -> String { "content-\(id.uuidString).json" }
    private static func epgFile(_ id: UUID) -> String { "epg-\(id.uuidString).json" }
    private static func sourceFile(_ id: UUID) -> String { "source-\(id.uuidString).m3u" }
}
