import Compression
import Foundation

/// Lädt einen TV-Guide im XMLTV-Format (auch `.xml.gz`) und behält nur Sendungen
/// der eigenen Sender im Zeitfenster von 2 Stunden zurück bis 2 Tage voraus.
enum EPGService {

    enum EPGError: LocalizedError {
        case invalidData

        var errorDescription: String? {
            "Der TV-Guide konnte nicht gelesen werden. Ist die Adresse eine XMLTV-Datei?"
        }
    }

    static func load(from url: URL, for channels: [Channel]) async throws -> EPGGuide {
        // Große TV-Guides (oft > 100 MB) nie komplett in den Speicher laden:
        // auf die Festplatte herunterladen, dort entpacken und gestreamt lesen.
        let downloaded = try await HTTPClient.download(from: url)
        defer { try? FileManager.default.removeItem(at: downloaded) }
        let xmlFile = try gunzipFileIfNeeded(downloaded)
        defer { if xmlFile != downloaded { try? FileManager.default.removeItem(at: xmlFile) } }

        var wantedIDs = Set<String>()
        var wantedNames = Set<String>()
        for channel in channels where channel.kind == .live {
            if let id = channel.tvgID?.lowercased(), !id.isEmpty { wantedIDs.insert(id) }
            wantedNames.insert(channel.name.lowercased())
        }

        let now = Date.now
        let collector = XMLTVCollector(
            wantedIDs: wantedIDs,
            wantedNames: wantedNames,
            window: now.addingTimeInterval(-2 * 3600)...now.addingTimeInterval(48 * 3600)
        )
        guard let stream = InputStream(url: xmlFile) else { throw EPGError.invalidData }
        let parser = XMLParser(stream: stream)
        parser.delegate = collector
        guard parser.parse() || !collector.programmes.isEmpty else { throw EPGError.invalidData }

        // Sortieren und doppelte Sendungen (gleiche Startzeit) entfernen.
        let cleaned = collector.programmes.mapValues { list -> [Programme] in
            var seen = Set<Date>()
            return list.sorted { $0.start < $1.start }.filter { seen.insert($0.start).inserted }
        }
        return EPGGuide(programmes: cleaned, fetchedAt: now)
    }

    /// Entpackt eine gzip-Datei (z. B. `epg.xml.gz`) blockweise in eine neue Datei.
    /// Andere Dateien werden unverändert zurückgegeben.
    static func gunzipFileIfNeeded(_ file: URL) throws -> URL {
        let input = try FileHandle(forReadingFrom: file)
        defer { try? input.close() }

        let header = try input.read(upToCount: 10) ?? Data()
        guard header.count == 10, header[0] == 0x1f, header[1] == 0x8b else { return file }

        // Optionale Kopffelder überspringen.
        let flags = header[3]
        if flags & 0x04 != 0, let extra = try input.read(upToCount: 2), extra.count == 2 {
            _ = try input.read(upToCount: Int(extra[extra.startIndex]) | Int(extra[extra.startIndex + 1]) << 8)
        }
        if flags & 0x08 != 0 { try skipZeroTerminated(input) }
        if flags & 0x10 != 0 { try skipZeroTerminated(input) }
        if flags & 0x02 != 0 { _ = try input.read(upToCount: 2) }

        // Die letzten 8 Bytes (Prüfsumme, Länge) gehören nicht zum DEFLATE-Strom.
        let fileSize = (try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.uint64Value ?? 0
        let deflateEnd = fileSize > 8 ? fileSize - 8 : 0
        guard try input.offset() < deflateEnd else { throw EPGError.invalidData }

        let output = FileManager.default.temporaryDirectory.appending(path: "epg-\(UUID().uuidString).xml")
        FileManager.default.createFile(atPath: output.path, contents: nil)
        let writer = try FileHandle(forWritingTo: output)
        defer { try? writer.close() }

        // Apple „zlib“ = rohes DEFLATE (RFC 1951), genau der Inhalt einer gzip-Datei.
        let filter = try OutputFilter(.decompress, using: .zlib) { data in
            if let data { try? writer.write(contentsOf: data) }
        }
        while true {
            let position = try input.offset()
            guard position < deflateEnd else { break }
            let count = Int(min(UInt64(1 << 20), deflateEnd - position))
            guard let chunk = try input.read(upToCount: count), !chunk.isEmpty else { break }
            try filter.write(chunk)
        }
        try filter.finalize()
        return output
    }

    private static func skipZeroTerminated(_ handle: FileHandle) throws {
        while let byte = try handle.read(upToCount: 1), let value = byte.first, value != 0 {}
    }
}

/// SAX-Parser für XMLTV: liest Kanäle und Sendungen, ohne die ganze Datei als Baum zu halten.
private final class XMLTVCollector: NSObject, XMLParserDelegate {
    private let wantedIDs: Set<String>
    private let wantedNames: Set<String>
    private let window: ClosedRange<Date>

    /// XMLTV-Kanal-ID → unsere Schlüssel (tvg-id bzw. Sendername).
    private var keysForChannel: [String: Set<String>] = [:]
    private(set) var programmes: [String: [Programme]] = [:]

    private var currentChannelID: String?
    private var currentProgramme: (channel: String, start: Date, end: Date)?
    private var currentTitle: String?
    private var currentDescription: String?
    private var text = ""

    private let formatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMddHHmmss Z"
        return formatter
    }()

    private let formatterWithoutZone: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyyMMddHHmmss"
        return formatter
    }()

    init(wantedIDs: Set<String>, wantedNames: Set<String>, window: ClosedRange<Date>) {
        self.wantedIDs = wantedIDs
        self.wantedNames = wantedNames
        self.window = window
    }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes: [String: String] = [:]
    ) {
        text = ""
        switch elementName {
        case "channel":
            currentChannelID = attributes["id"]?.lowercased()
            if let id = currentChannelID, wantedIDs.contains(id) {
                keysForChannel[id, default: []].insert(id)
            }
        case "programme":
            guard let channel = attributes["channel"]?.lowercased(),
                  let start = date(attributes["start"]),
                  let end = date(attributes["stop"]),
                  end >= window.lowerBound, start <= window.upperBound else {
                currentProgramme = nil
                return
            }
            // Auch Sender ohne <channel>-Eintrag über die ID zuordnen.
            if wantedIDs.contains(channel) { keysForChannel[channel, default: []].insert(channel) }
            guard keysForChannel[channel] != nil else {
                currentProgramme = nil
                return
            }
            currentProgramme = (channel, start, end)
            currentTitle = nil
            currentDescription = nil
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        text += string
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        switch elementName {
        case "display-name":
            if let id = currentChannelID, wantedNames.contains(value.lowercased()) {
                keysForChannel[id, default: []].insert(value.lowercased())
            }
        case "channel":
            currentChannelID = nil
        case "title" where currentProgramme != nil:
            if currentTitle == nil { currentTitle = value }
        case "desc" where currentProgramme != nil:
            if currentDescription == nil { currentDescription = value }
        case "programme":
            if let current = currentProgramme, let keys = keysForChannel[current.channel] {
                let programme = Programme(
                    title: currentTitle ?? "Ohne Titel",
                    description: currentDescription.flatMap { $0.isEmpty ? nil : $0 },
                    start: current.start,
                    end: current.end
                )
                for key in keys { programmes[key, default: []].append(programme) }
            }
            currentProgramme = nil
        default:
            break
        }
        text = ""
    }

    private func date(_ value: String?) -> Date? {
        guard let value = value?.trimmingCharacters(in: .whitespaces), value.count >= 14 else { return nil }
        return formatter.date(from: value) ?? formatterWithoutZone.date(from: String(value.prefix(14)))
    }
}
