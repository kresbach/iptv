import Foundation

/// Ein heruntergeladener (oder wartender) Film bzw. eine Episode.
struct DownloadItem: Identifiable, Codable, Hashable, Sendable {
    enum State: Codable, Hashable, Sendable {
        case queued
        case downloading
        case paused
        case completed
        case failed(String)
    }

    let id: UUID
    /// Original-Eintrag mit Stream-Link; über `stableKey` mit Fortschritt und Favoriten verknüpft.
    var channel: Channel
    let title: String
    /// z. B. „Breaking Bad · Staffel 1, Episode 3“
    let subtitle: String?
    let posterURL: URL?
    /// Gesetzt bei Episoden, zum Gruppieren in der Übersicht.
    let showTitle: String?
    var state: State
    var bytesWritten: Int64 = 0
    var totalBytes: Int64 = 0
    let addedAt: Date
    var fileExtension: String
    /// Verkleinerung nach dem Download (nur MP4/MOV), `nil` = nicht vorgesehen.
    var compression: Compression?
    var compressionProgress: Double?

    enum Compression: Codable, Hashable, Sendable {
        case pending
        case running
        /// Fertig; `originalBytes` ist die Größe vor dem Verkleinern.
        case done(originalBytes: Int64)
        case failed(String)
    }

    var isCompressing: Bool {
        compression == .running || compression == .pending
    }

    var key: String { channel.stableKey }

    var fraction: Double {
        totalBytes > 0 ? min(Double(bytesWritten) / Double(totalBytes), 1) : 0
    }

    var isCompleted: Bool { state == .completed }

    var isActive: Bool {
        switch state {
        case .queued, .downloading, .paused: true
        case .completed, .failed: false
        }
    }

    var localURL: URL { DownloadManager.fileURL(id: id, fileExtension: fileExtension) }
}
