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
        let raw = try await HTTPClient.data(from: url)
        let xml = try gunzipIfNeeded(raw)

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
        let parser = XMLParser(data: xml)
        parser.delegate = collector
        guard parser.parse() || !collector.programmes.isEmpty else { throw EPGError.invalidData }

        let sorted = collector.programmes.mapValues { $0.sorted { $0.start < $1.start } }
        return EPGGuide(programmes: sorted, fetchedAt: now)
    }

    /// Entpackt gzip-Daten (z. B. `epg.xml.gz`); andere Daten bleiben unverändert.
    static func gunzipIfNeeded(_ input: Data) throws -> Data {
        let bytes = [UInt8](input)
        guard bytes.count > 18, bytes[0] == 0x1f, bytes[1] == 0x8b else { return input }

        let flags = bytes[3]
        var offset = 10
        if flags & 0x04 != 0 { offset += 2 + (Int(bytes[offset]) | Int(bytes[offset + 1]) << 8) }  // FEXTRA
        if flags & 0x08 != 0 { while offset < bytes.count, bytes[offset] != 0 { offset += 1 }; offset += 1 }  // FNAME
        if flags & 0x10 != 0 { while offset < bytes.count, bytes[offset] != 0 { offset += 1 }; offset += 1 }  // FCOMMENT
        if flags & 0x02 != 0 { offset += 2 }  // FHCRC
        guard offset < bytes.count - 8 else { throw EPGError.invalidData }

        // Apple „zlib“ = rohes DEFLATE (RFC 1951), genau der Inhalt einer gzip-Datei.
        let deflated = Data(bytes[offset..<(bytes.count - 8)]) as NSData
        return try deflated.decompressed(using: .zlib) as Data
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
