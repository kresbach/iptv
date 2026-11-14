@preconcurrency import AVFoundation
import Foundation

/// Einstellungen für Downloads (in den Einstellungen änderbar, in UserDefaults gespeichert).
enum DownloadSettings {
    static let compressionKey = "downloadCompression"
    static let storageLimitKey = "downloadStorageLimitGB"
    static let autoDeleteKey = "downloadAutoDeleteWatched"

    static var compression: DownloadCompression {
        UserDefaults.standard.string(forKey: compressionKey).flatMap(DownloadCompression.init(rawValue:)) ?? .medium
    }

    /// Speicherlimit in Bytes, `nil` = kein Limit.
    static var storageLimitBytes: Int64? {
        let gigabytes = UserDefaults.standard.integer(forKey: storageLimitKey)
        return gigabytes > 0 ? Int64(gigabytes) * 1_000_000_000 : nil
    }

    static var autoDeleteWatched: Bool {
        UserDefaults.standard.bool(forKey: autoDeleteKey)
    }

    static let storageLimitOptions = [0, 5, 10, 20, 50, 100]
}

/// Stufen für das Verkleinern heruntergeladener MP4-Dateien auf dem Gerät.
enum DownloadCompression: String, CaseIterable, Identifiable {
    case off
    case medium
    case small

    var id: String { rawValue }

    var title: String {
        switch self {
        case .off: "Aus (Originalqualität)"
        case .medium: "Mittel (720p, ca. 30–50 %)"
        case .small: "Klein (SD, ca. 10–25 %)"
        }
    }

    /// Export-Voreinstellung von AVFoundation.
    var preset: String? {
        switch self {
        case .off: nil
        case .medium: AVAssetExportPreset1280x720
        case .small: AVAssetExportPresetMediumQuality
        }
    }

    /// Nur diese Formate kann iOS umwandeln (MKV z. B. nicht).
    static let supportedExtensions: Set<String> = ["mp4", "m4v", "mov"]
}
