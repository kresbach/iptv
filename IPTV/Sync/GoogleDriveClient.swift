import Foundation

/// Liest und schreibt die Sync-Datei im Ordner „IPTV“ von Google Drive (dieselbe Datei wie die Fernseher-App).
/// Berechtigung „drive.file“: die App sieht nur Dateien, die sie bzw. die Fernseher-App selbst angelegt hat.
struct GoogleDriveClient: Sendable {
    let accessToken: String
    static let fileName = "iptv-sync.json"
    static let folderName = "IPTV"

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

    private struct FileList: Decodable { struct File: Decodable { let id: String }; let files: [File] }

    /// Erste passende Datei (neueste zuerst); `appData` = alter versteckter App-Ordner.
    private func findID(_ query: String, appData: Bool = false) async throws -> String? {
        var components = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        var items = [
            URLQueryItem(name: "q", value: "\(query) and trashed = false"),
            URLQueryItem(name: "orderBy", value: "modifiedTime desc"),
            URLQueryItem(name: "fields", value: "files(id)"),
        ]
        if appData { items.append(URLQueryItem(name: "spaces", value: "appDataFolder")) }
        components.queryItems = items
        let list = try JSONDecoder().decode(FileList.self, from: try await send(URLRequest(url: components.url!)))
        return list.files.first?.id
    }

    /// Lädt die Sync-Datei. `fileID` ist nil, wenn sie noch aus dem alten versteckten App-Ordner stammt –
    /// dann legt `upload` sie im Ordner „IPTV“ neu an (einmalige Übernahme).
    func download() async throws -> (fileID: String?, data: Data)? {
        if let id = try await findID("name = '\(Self.fileName)'") {
            return (id, try await send(URLRequest(url: URL(string: "https://www.googleapis.com/drive/v3/files/\(id)?alt=media")!)))
        }
        // Alte Sync-Datei (vor der Umstellung) übernehmen; schlägt das fehl, einfach neu beginnen.
        if let legacy = try? await findID("name = '\(Self.fileName)'", appData: true),
           let data = try? await send(URLRequest(url: URL(string: "https://www.googleapis.com/drive/v3/files/\(legacy)?alt=media")!)) {
            return (nil, data)
        }
        return nil
    }

    /// Ordner „IPTV“ finden oder anlegen.
    private func folderID() async throws -> String {
        if let id = try await findID("name = '\(Self.folderName)' and mimeType = 'application/vnd.google-apps.folder'") {
            return id
        }
        var request = URLRequest(url: URL(string: "https://www.googleapis.com/drive/v3/files?fields=id")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(#"{"name":"\#(Self.folderName)","mimeType":"application/vnd.google-apps.folder"}"#.utf8)
        struct Created: Decodable { let id: String }
        return try JSONDecoder().decode(Created.self, from: try await send(request)).id
    }

    /// Schreibt die Sync-Datei (legt sie beim ersten Mal im Ordner „IPTV“ an).
    func upload(_ data: Data, existingFileID: String?) async throws {
        if let existingFileID {
            var request = URLRequest(url: URL(string: "https://www.googleapis.com/upload/drive/v3/files/\(existingFileID)?uploadType=media")!)
            request.httpMethod = "PATCH"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = data
            _ = try await send(request)
            return
        }

        let folder = try await folderID()
        let boundary = "iptv-\(UUID().uuidString)"
        let metadata = #"{"name":"\#(Self.fileName)","parents":["\#(folder)"]}"#
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
