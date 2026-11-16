import Foundation

/// Einstellungen für Downloads (in den Einstellungen änderbar, in UserDefaults gespeichert).
enum DownloadSettings {
    static let storageLimitKey = "downloadStorageLimitGB"
    static let autoDeleteKey = "downloadAutoDeleteWatched"

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
