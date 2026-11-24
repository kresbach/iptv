import Foundation

/// Sendungsarchiv (Catch-up) im Xtream-Format:
/// `http://server/timeshift/<Benutzer>/<Passwort>/<Minuten>/<JJJJ-MM-TT:HH-MM>/<Sender-ID>.ts`.
/// Funktioniert für Sender, bei denen der Anbieter ein Archiv meldet (`tv_archive`
/// bzw. `catchup-days` in der M3U) und deren Link das Xtream-Schema hat.
enum Catchup {
    /// Zerlegt `http://host:port[/pfad]/live/user/pass/123.m3u8` (auch ohne `/live`)
    /// in Basisadresse, Zugangsdaten und Sender-ID. Zugangsdaten bleiben URL-kodiert.
    private static func parts(of url: URL) -> (base: String, user: String, pass: String, id: String)? {
        guard url.query == nil, url.fragment == nil,
              let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else { return nil }
        var pieces = url.absoluteString.components(separatedBy: "/")
        // ["http:", "", "host:port", …, "live"?, "user", "pass", "123.ts"]
        guard pieces.count >= 6, let last = pieces.popLast() else { return nil }
        let id = last.split(separator: ".").first.map(String.init) ?? ""
        guard !id.isEmpty, id.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        let pass = pieces.removeLast()
        let user = pieces.removeLast()
        if pieces.last?.lowercased() == "live" { pieces.removeLast() }
        guard pieces.count >= 3, !user.isEmpty, !pass.isEmpty else { return nil }
        return (pieces.joined(separator: "/"), user, pass, id)
    }

    /// Bietet der Sender ein Archiv an?
    static func isSupported(_ channel: Channel) -> Bool {
        channel.kind == .live && (channel.archiveDays ?? 0) > 0 && parts(of: channel.url) != nil
    }

    /// Lässt sich die Sendung aus dem Archiv abspielen (begonnen und nicht älter als das Archiv)?
    static func isAvailable(_ channel: Channel, _ programme: Programme, now: Date = .now) -> Bool {
        guard isSupported(channel), let days = channel.archiveDays else { return false }
        return programme.start < now.addingTimeInterval(-60)
            && programme.start > now.addingTimeInterval(-TimeInterval(days) * 86_400)
    }

    /// Link zur Sendung im Archiv. Die Startzeit wird in der Zeitzone des Servers angegeben.
    static func url(for channel: Channel, programme: Programme, timeZone: String?) -> URL? {
        guard let parts = parts(of: channel.url) else { return nil }
        let minutes = min(max(Int(programme.end.timeIntervalSince(programme.start) / 60), 1), 24 * 60)
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = timeZone.flatMap(TimeZone.init(identifier:)) ?? .current
        formatter.dateFormat = "yyyy-MM-dd:HH-mm"
        let start = formatter.string(from: programme.start)
        return URL(string: "\(parts.base)/timeshift/\(parts.user)/\(parts.pass)/\(minutes)/\(start)/\(parts.id).ts")
    }

    /// Abspielbarer Eintrag für die Sendung: spulbar wie ein Film.
    static func archiveChannel(for channel: Channel, programme: Programme, timeZone: String?) -> Channel? {
        guard let link = url(for: channel, programme: programme, timeZone: timeZone) else { return nil }
        return Channel(
            name: programme.title,
            url: link,
            group: channel.name,
            tvgID: channel.tvgID,
            logoURL: channel.logoURL,
            kind: .movie
        )
    }

    /// Vergangene Sendungen eines Senders, die im Archiv liegen, neueste zuerst.
    static func pastProgrammes(for channel: Channel, guide: EPGGuide?, now: Date = .now) -> [Programme] {
        guard let guide, isSupported(channel) else { return [] }
        return guide.schedule(for: channel)
            .filter { isAvailable(channel, $0, now: now) && $0.end <= now }
            .sorted { $0.start > $1.start }
    }

    /// So viele Tage reicht das längste Archiv zurück (höchstens 7, für den TV-Guide).
    static func maxArchiveDays(of channels: [Channel]) -> Int {
        min(channels.lazy.filter { isSupported($0) }.compactMap(\.archiveDays).max() ?? 0, 7)
    }
}

extension PlaylistStore {
    /// Abspielbarer Archiv-Eintrag für eine vergangene oder laufende Sendung.
    func archiveChannel(for channel: Channel, programme: Programme) -> Channel? {
        Catchup.archiveChannel(for: channel, programme: programme, timeZone: content.data.serverTimeZone)
    }
}
