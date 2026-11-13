import Foundation

/// Dateiablage der App in *Application Support*.
enum Storage {
    static var directory: URL {
        URL.applicationSupportDirectory.appending(path: "IPTV", directoryHint: .isDirectory)
    }

    static func url(_ name: String) -> URL { directory.appending(path: name) }

    static func write<T: Encodable & Sendable>(_ value: T, to name: String) async {
        let fileURL = url(name)
        await Task.detached(priority: .utility) {
            do {
                try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
                try JSONEncoder().encode(value).write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            } catch {
                print("Speichern von \(fileURL.lastPathComponent) fehlgeschlagen: \(error)")
            }
        }.value
    }

    static func read<T: Decodable & Sendable>(_ type: T.Type, from name: String) async -> T? {
        let fileURL = url(name)
        return await Task.detached(priority: .userInitiated) {
            guard let data = try? Data(contentsOf: fileURL) else { return nil }
            return try? JSONDecoder().decode(T.self, from: data)
        }.value
    }

    static func writeRaw(_ data: Data, to name: String) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try data.write(to: url(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func remove(_ name: String) {
        try? FileManager.default.removeItem(at: url(name))
    }
}
