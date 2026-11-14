import Foundation
import Observation
import UIKit

/// Lädt Filme und Episoden über eine Hintergrund-URLSession herunter, damit Downloads
/// auch bei gesperrtem Gerät oder geschlossener App weiterlaufen.
///
/// Es wird immer nur ein Download gleichzeitig geladen (IPTV-Konten erlauben oft nur
/// eine Verbindung). Startet ein Stream, pausiert der Download und setzt danach fort.
@MainActor
@Observable
final class DownloadManager {
    static let shared = DownloadManager()

    private(set) var items: [DownloadItem] = []

    /// Download während der Wiedergabe eines Streams anhalten (Standard: an).
    var pausesDuringPlayback: Bool {
        get {
            access(keyPath: \.pausesDuringPlayback)
            return UserDefaults.standard.object(forKey: Self.pauseKey) as? Bool ?? true
        }
        set {
            withMutation(keyPath: \.pausesDuringPlayback) {
                UserDefaults.standard.set(newValue, forKey: Self.pauseKey)
            }
        }
    }

    /// Vom App-Delegate übergeben, wenn iOS die App für fertige Hintergrund-Downloads weckt.
    @ObservationIgnored var backgroundCompletionHandler: (() -> Void)?

    @ObservationIgnored private var session: URLSession!
    @ObservationIgnored private var tasks: [UUID: URLSessionDownloadTask] = [:]
    @ObservationIgnored private var resumeData: [UUID: Data] = [:]
    @ObservationIgnored private var isPlaybackActive = false
    @ObservationIgnored private var saveTask: Task<Void, Never>?

    private static let pauseKey = "downloadsPauseDuringPlayback"
    private static let listFile = "downloads.json"
    nonisolated static let sessionIdentifier = "com.kresbach.IPTV.downloads"

    nonisolated static var directory: URL {
        Storage.directory.appending(path: "Downloads", directoryHint: .isDirectory)
    }

    nonisolated static func fileURL(id: UUID, fileExtension: String) -> URL {
        directory.appending(path: "\(id.uuidString).\(fileExtension)")
    }

    private init() {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        configuration.httpAdditionalHeaders = ["User-Agent": HTTPClient.userAgent]
        configuration.httpMaximumConnectionsPerHost = 1
        configuration.isDiscretionary = false
        configuration.sessionSendsLaunchEvents = true
        session = URLSession(configuration: configuration, delegate: DownloadSessionDelegate(), delegateQueue: nil)

        loadList()
        Task { await reattachRunningTasks() }
    }

    // MARK: - Abfragen

    func item(for channel: Channel) -> DownloadItem? {
        items.first { $0.key == channel.stableKey }
    }

    /// Lokale Datei eines fertigen Downloads, sonst `nil`.
    func localURL(for channel: Channel) -> URL? {
        guard let item = item(for: channel), item.isCompleted,
              FileManager.default.fileExists(atPath: item.localURL.path) else { return nil }
        return item.localURL
    }

    var completedItems: [DownloadItem] { items.filter(\.isCompleted) }
    var activeItems: [DownloadItem] { items.filter(\.isActive) }
    var failedItems: [DownloadItem] {
        items.filter { if case .failed = $0.state { true } else { false } }
    }

    /// Belegter Speicher aller fertigen Downloads in Bytes.
    var usedBytes: Int64 {
        completedItems.reduce(0) { sum, item in
            let size = (try? FileManager.default.attributesOfItem(atPath: item.localURL.path)[.size] as? NSNumber)?.int64Value
            return sum + (size ?? item.totalBytes)
        }
    }

    static var availableBytes: Int64 {
        let values = try? URL.homeDirectory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }

    // MARK: - Steuerung

    /// Reiht einen Film oder eine Episode in die Warteschlange ein.
    func download(_ channel: Channel, title: String, subtitle: String? = nil, posterURL: URL? = nil, showTitle: String? = nil) {
        guard channel.kind != .live, !channel.url.isFileURL else { return }
        if let existing = item(for: channel) {
            if case .failed = existing.state { retry(existing.id) }
            return
        }
        let ext = channel.url.pathExtension.isEmpty ? "mp4" : channel.url.pathExtension.lowercased()
        items.append(DownloadItem(
            id: UUID(),
            channel: channel,
            title: title,
            subtitle: subtitle,
            posterURL: posterURL ?? channel.logoURL,
            showTitle: showTitle,
            state: .queued,
            addedAt: .now,
            fileExtension: ext
        ))
        save()
        startNextIfPossible()
    }

    /// Bricht einen laufenden Download ab bzw. löscht einen fertigen.
    func remove(_ id: UUID) {
        tasks[id]?.cancel()
        tasks[id] = nil
        resumeData[id] = nil
        if let item = items.first(where: { $0.id == id }) {
            try? FileManager.default.removeItem(at: item.localURL)
        }
        items.removeAll { $0.id == id }
        save()
        startNextIfPossible()
    }

    func removeAllCompleted() {
        for item in completedItems { remove(item.id) }
    }

    func retry(_ id: UUID) {
        update(id) { $0.state = .queued }
        save()
        startNextIfPossible()
    }

    /// Vom Player: Bei Streams den laufenden Download anhalten (eine Verbindung pro Konto).
    func playbackStarted(isLocalFile: Bool) {
        isPlaybackActive = !isLocalFile
        guard isPlaybackActive, pausesDuringPlayback else { return }
        for item in items where item.state == .downloading {
            pause(item.id, requeue: true)
        }
    }

    func playbackEnded() {
        isPlaybackActive = false
        startNextIfPossible()
    }

    // MARK: - Ablauf

    private func startNextIfPossible() {
        guard !(isPlaybackActive && pausesDuringPlayback) else { return }
        guard !items.contains(where: { $0.state == .downloading }) else { return }
        guard let next = items.first(where: { $0.state == .queued }) else { return }

        let task: URLSessionDownloadTask
        if let data = resumeData[next.id] {
            task = session.downloadTask(withResumeData: data)
            resumeData[next.id] = nil
        } else {
            var request = URLRequest(url: next.channel.url)
            request.setValue(HTTPClient.userAgent, forHTTPHeaderField: "User-Agent")
            task = session.downloadTask(with: request)
        }
        task.taskDescription = "\(next.id.uuidString)|\(next.fileExtension)"
        tasks[next.id] = task
        update(next.id) { $0.state = .downloading }
        save()
        task.resume()
    }

    private func pause(_ id: UUID, requeue: Bool) {
        guard let task = tasks[id] else { return }
        // Status zuerst setzen, damit die Abbruch-Meldung des Systems nicht als Fehler gilt.
        update(id) { $0.state = requeue ? .queued : .paused }
        tasks[id] = nil
        task.cancel { data in
            guard let data else { return }
            Task { @MainActor in DownloadManager.shared.storeResumeData(data, for: id) }
        }
        save()
    }

    /// Nach einem Neustart der App: noch laufende Hintergrund-Downloads wieder zuordnen.
    private func reattachRunningTasks() async {
        let running = await session.allTasks.compactMap { $0 as? URLSessionDownloadTask }
        var attached = Set<UUID>()
        for task in running {
            guard let id = Self.itemID(from: task.taskDescription) else { continue }
            if items.contains(where: { $0.id == id }) {
                tasks[id] = task
                attached.insert(id)
                update(id) { $0.state = .downloading }
            } else {
                task.cancel()
            }
        }
        // Als „lädt“ gespeicherte Einträge ohne laufenden Task erneut einreihen.
        for item in items where item.state == .downloading && !attached.contains(item.id) {
            update(item.id) { $0.state = .queued }
        }
        save()
        startNextIfPossible()
    }

    // MARK: - Rückmeldungen der URLSession (über den Delegate)

    func didWrite(_ id: UUID, written: Int64, total: Int64) {
        guard let item = items.first(where: { $0.id == id }) else { return }
        // Beim ersten Mal prüfen, ob die Datei überhaupt auf das Gerät passt.
        if item.totalBytes == 0, total > 0, total > Self.availableBytes - 500_000_000 {
            tasks[id]?.cancel()
            tasks[id] = nil
            update(id) { $0.state = .failed("Nicht genug Speicherplatz (\(Self.format(total)) benötigt).") }
            save()
            startNextIfPossible()
            return
        }
        update(id) {
            $0.bytesWritten = written
            if total > 0 { $0.totalBytes = total }
        }
    }

    func didFinish(_ id: UUID) {
        tasks[id] = nil
        resumeData[id] = nil
        update(id) {
            $0.state = .completed
            if $0.totalBytes == 0 { $0.totalBytes = $0.bytesWritten }
            $0.bytesWritten = $0.totalBytes
        }
        save()
        startNextIfPossible()
    }

    func didFail(_ id: UUID, message: String, resumeData data: Data?, wasCancelled: Bool) {
        // Selbst ausgelöste Pausen/Abbrüche sind kein Fehler.
        guard let item = items.first(where: { $0.id == id }), item.state == .downloading else { return }
        tasks[id] = nil
        if let data { resumeData[id] = data }
        update(id) { $0.state = wasCancelled ? .queued : .failed(message) }
        save()
        if !wasCancelled { startNextIfPossible() }
    }

    func storeResumeData(_ data: Data, for id: UUID) {
        resumeData[id] = data
    }

    func finishBackgroundEvents() {
        backgroundCompletionHandler?()
        backgroundCompletionHandler = nil
    }

    // MARK: - Intern

    private func update(_ id: UUID, _ change: (inout DownloadItem) -> Void) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        change(&items[index])
    }

    nonisolated static func itemID(from description: String?) -> UUID? {
        description?.split(separator: "|").first.flatMap { UUID(uuidString: String($0)) }
    }

    static func format(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    private func loadList() {
        guard let data = try? Data(contentsOf: Storage.url(Self.listFile)),
              let saved = try? JSONDecoder().decode([DownloadItem].self, from: data) else { return }
        // Fertige Einträge ohne Datei (z. B. vom System gelöscht) aussortieren.
        items = saved.filter { !$0.isCompleted || FileManager.default.fileExists(atPath: $0.localURL.path) }
    }

    private func save() {
        saveTask?.cancel()
        let snapshot = items
        saveTask = Task {
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            await Storage.write(snapshot, to: Self.listFile)
        }
    }
}

/// Empfängt die Ereignisse der Hintergrund-URLSession (auf einem eigenen Thread)
/// und gibt sie an den `DownloadManager` auf dem Main Actor weiter.
final class DownloadSessionDelegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var lastPercent: [Int: Int] = [:]

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let id = DownloadManager.itemID(from: downloadTask.taskDescription) else { return }
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 200
        let mimeType = downloadTask.response?.mimeType ?? ""

        guard (200..<300).contains(status), !mimeType.contains("text/html") else {
            let message = (200..<300).contains(status)
                ? "Der Server hat statt des Videos eine Webseite geliefert."
                : "Der Server hat mit Fehler \(status) geantwortet."
            Task { @MainActor in DownloadManager.shared.didFail(id, message: message, resumeData: nil, wasCancelled: false) }
            return
        }

        // Die Datei muss hier (synchron) verschoben werden, sonst löscht iOS sie.
        let ext = downloadTask.taskDescription?.split(separator: "|").last.map(String.init) ?? "mp4"
        let destination = DownloadManager.fileURL(id: id, fileExtension: ext)
        do {
            try FileManager.default.createDirectory(at: DownloadManager.directory, withIntermediateDirectories: true)
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: location, to: destination)
            var file = destination
            var values = URLResourceValues()
            values.isExcludedFromBackup = true  // Videos nicht ins iCloud-Backup
            try? file.setResourceValues(values)
            Task { @MainActor in DownloadManager.shared.didFinish(id) }
        } catch {
            let message = "Die Datei konnte nicht gespeichert werden: \(error.localizedDescription)"
            Task { @MainActor in DownloadManager.shared.didFail(id, message: message, resumeData: nil, wasCancelled: false) }
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard let id = DownloadManager.itemID(from: downloadTask.taskDescription) else { return }
        // Nur bei jedem vollen Prozent melden, statt bei jedem Datenpaket.
        let percent = totalBytesExpectedToWrite > 0
            ? Int(totalBytesWritten * 100 / totalBytesExpectedToWrite)
            : Int(totalBytesWritten / 10_000_000)
        let shouldReport = lock.withLock {
            guard lastPercent[downloadTask.taskIdentifier] != percent else { return false }
            lastPercent[downloadTask.taskIdentifier] = percent
            return true
        }
        guard shouldReport else { return }
        Task { @MainActor in
            DownloadManager.shared.didWrite(id, written: totalBytesWritten, total: totalBytesExpectedToWrite)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.withLock { lastPercent[task.taskIdentifier] = nil }
        guard let error, let id = DownloadManager.itemID(from: task.taskDescription) else { return }
        let nsError = error as NSError
        let data = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        let cancelled = nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled
        let message = HTTPClient.message(for: error)
        Task { @MainActor in
            DownloadManager.shared.didFail(id, message: message, resumeData: data, wasCancelled: cancelled)
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        Task { @MainActor in DownloadManager.shared.finishBackgroundEvents() }
    }
}
