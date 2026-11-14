import Foundation

/// Liest und schreibt eine Datei im versteckten App-Ordner von Google Drive.
struct GoogleDriveClient: Sendable {
    let accessToken: String
    static let fileName = "iptv-sync.json"

    enum DriveError: LocalizedError {
        case http(Int, String)

        var errorDescription: String? {
            switch self {
            case .http(401, _), .http(403, _):
                "Google Drive hat den Zugriff verweigert. Bitte melde dich erneut an."
            case .http(let code, let detail):
                "Google Drive meldet Fehler \(code). \(detail)"
            }
        }
    }

    /// Lädt die Sync-Datei, falls vorhanden.
    func download() async throws -> (fileID: String, data: Data)? {
        var components = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        components.queryItems = [
            URLQueryItem(name: "spaces", value: "appDataFolder"),
            URLQueryItem(name: "q", value: "name = '\(Self.fileName)'"),
            URLQueryItem(name: "fields", value: "files(id)"),
        ]
        struct FileList: Decodable { struct File: Decodable { let id: String }; let files: [File] }
        let list = try JSONDecoder().decode(FileList.self, from: try await send(URLRequest(url: components.url!)))
        guard let fileID = list.files.first?.id else { return nil }

        let fileURL = URL(string: "https://www.googleapis.com/drive/v3/files/\(fileID)?alt=media")!
        return (fileID, try await send(URLRequest(url: fileURL)))
    }

    /// Schreibt die Sync-Datei (legt sie beim ersten Mal an).
    func upload(_ data: Data, existingFileID: String?) async throws {
        if let existingFileID {
            var request = URLRequest(url: URL(string: "https://www.googleapis.com/upload/drive/v3/files/\(existingFileID)?uploadType=media")!)
            request.httpMethod = "PATCH"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = data
            _ = try await send(request)
            return
        }

        let boundary = "iptv-\(UUID().uuidString)"
        let metadata = #"{"name":"\#(Self.fileName)","parents":["appDataFolder"]}"#
        var body = Data()
        body.append(Data("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n\(metadata)\r\n".utf8))
        body.append(Data("--\(boundary)\r\nContent-Type: application/json\r\n\r\n".utf8))
        body.append(data)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))

        var request = URLRequest(url: URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")!)
        request.httpMethod = "POST"
        request.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        _ = try await send(request)
    }

    private func send(_ request: URLRequest) async throws -> Data {
        var request = request
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw DriveError.http(http.statusCode, String(decoding: data.prefix(200), as: UTF8.self))
        }
        return data
    }
}
