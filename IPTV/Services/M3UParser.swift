import Foundation

/// Liest M3U/M3U8-Playlists im erweiterten IPTV-Format, z. B.:
///
///     #EXTM3U
///     #EXTINF:-1 tvg-id="ard.de" tvg-logo="https://…/ard.png" group-title="Nachrichten",Das Erste HD
///     http://example.com/live/ard.m3u8
///
/// Der Parser ist absichtlich tolerant: Unbekannte Tags werden übersprungen,
/// fehlende Namen fallen auf `tvg-name` bzw. den Dateinamen der URL zurück.
enum M3UParser {

    enum ParseError: LocalizedError {
        case noChannels

        var errorDescription: String? {
            switch self {
            case .noChannels:
                "Die Datei enthält keine Sender. Ist es wirklich eine M3U-Playlist?"
            }
        }
    }

    struct Result: Sendable {
        let channels: [Channel]
        /// EPG-Adresse aus dem Kopf (`#EXTM3U url-tvg="…"` bzw. `x-tvg-url`).
        let epgURL: URL?
    }

    static func parse(_ text: String) throws -> Result {
        var channels: [Channel] = []
        var epgURL: URL?
        channels.reserveCapacity(1024)

        var pendingName: String?
        var pendingAttributes: [String: String] = [:]
        var pendingGroup: String?

        for rawLine in text.split(whereSeparator: \.isNewline) {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            guard !line.isEmpty else { continue }

            if line.hasPrefix("#EXTM3U") {
                let attributes = parseAttributes(Substring(line.dropFirst("#EXTM3U".count)))
                let value = attributes["url-tvg"] ?? attributes["x-tvg-url"] ?? attributes["tvg-url"]
                epgURL = value?
                    .split(separator: ",").first
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .flatMap(URL.init(string:))
            } else if line.hasPrefix("#EXTINF") {
                let info = parseExtInf(line)
                pendingName = info.name
                pendingAttributes = info.attributes
            } else if line.hasPrefix("#EXTGRP:") {
                pendingGroup = String(line.dropFirst("#EXTGRP:".count))
                    .trimmingCharacters(in: .whitespaces)
            } else if line.hasPrefix("#") {
                continue // #EXTM3U, #EXTVLCOPT, Kommentare …
            } else if let url = makeURL(from: line) {
                let group = (pendingAttributes["group-title"] ?? pendingGroup)
                    .flatMap { $0.isEmpty ? nil : $0 }
                let name = [pendingName, pendingAttributes["tvg-name"]]
                    .compactMap { $0 }
                    .first { !$0.isEmpty }
                    ?? url.deletingPathExtension().lastPathComponent

                channels.append(Channel(
                    name: name,
                    url: url,
                    group: group,
                    tvgID: pendingAttributes["tvg-id"].flatMap { $0.isEmpty ? nil : $0 },
                    logoURL: pendingAttributes["tvg-logo"].flatMap(URL.init(string:))
                ))

                pendingName = nil
                pendingAttributes = [:]
                pendingGroup = nil
            }
        }

        guard !channels.isEmpty else { throw ParseError.noChannels }
        return Result(channels: channels, epgURL: epgURL)
    }

    // MARK: - Hilfsfunktionen

    /// Zerlegt `#EXTINF:-1 key="value" …,Name` in Attribute und Namen.
    /// Das Komma, das den Namen abtrennt, ist das erste Komma außerhalb von Anführungszeichen.
    static func parseExtInf(_ line: String) -> (name: String, attributes: [String: String]) {
        let body = line.drop(while: { $0 != ":" }).dropFirst()

        var inQuotes = false
        var commaIndex: Substring.Index?
        for index in body.indices {
            switch body[index] {
            case "\"": inQuotes.toggle()
            case "," where !inQuotes:
                commaIndex = index
            default: break
            }
            if commaIndex != nil { break }
        }

        guard let commaIndex else {
            return ("", parseAttributes(body))
        }
        let name = body[body.index(after: commaIndex)...]
            .trimmingCharacters(in: .whitespaces)
        return (name, parseAttributes(body[..<commaIndex]))
    }

    /// Liest `key="value"`- bzw. `key=value`-Paare. Schlüssel werden kleingeschrieben.
    static func parseAttributes(_ text: Substring) -> [String: String] {
        var result: [String: String] = [:]
        var cursor = text.startIndex

        while cursor < text.endIndex,
              let equals = text[cursor...].firstIndex(of: "=") {
            let keyStart = text[cursor..<equals]
                .lastIndex(where: \.isWhitespace)
                .map { text.index(after: $0) } ?? cursor
            let key = text[keyStart..<equals].lowercased()

            var valueStart = text.index(after: equals)
            guard valueStart < text.endIndex else { break }

            let valueEnd: Substring.Index
            if text[valueStart] == "\"" {
                valueStart = text.index(after: valueStart)
                valueEnd = text[valueStart...].firstIndex(of: "\"") ?? text.endIndex
                cursor = valueEnd < text.endIndex ? text.index(after: valueEnd) : text.endIndex
            } else {
                valueEnd = text[valueStart...].firstIndex(where: \.isWhitespace) ?? text.endIndex
                cursor = valueEnd
            }

            if !key.isEmpty {
                result[key] = String(text[valueStart..<valueEnd])
                    .trimmingCharacters(in: .whitespaces)
            }
        }
        return result
    }

    /// Akzeptiert nur Stream-URLs mit Schema (http, https, rtmp, rtsp …).
    private static func makeURL(from line: String) -> URL? {
        let url = URL(string: line)
            ?? line.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed).flatMap(URL.init(string:))
        guard let url, url.scheme != nil else { return nil }
        return url
    }
}
