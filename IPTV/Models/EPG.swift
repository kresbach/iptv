import Foundation

/// Eine Sendung im TV-Guide.
struct Programme: Identifiable, Hashable, Codable, Sendable {
    let title: String
    let description: String?
    let start: Date
    let end: Date

    var id: Date { start }

    func isLive(at date: Date) -> Bool { start <= date && date < end }

    func progress(at date: Date) -> Double {
        let total = end.timeIntervalSince(start)
        guard total > 0 else { return 0 }
        return min(max(date.timeIntervalSince(start) / total, 0), 1)
    }

    var timeRange: String {
        "\(start.formatted(date: .omitted, time: .shortened)) – \(end.formatted(date: .omitted, time: .shortened))"
    }
}

/// TV-Guide einer Playlist: Sendungen je Sender-Kennung (kleingeschrieben).
struct EPGGuide: Codable, Sendable {
    var programmes: [String: [Programme]]
    var fetchedAt: Date

    func schedule(for channel: Channel) -> [Programme] {
        for key in Self.lookupKeys(for: channel) {
            if let list = programmes[key], !list.isEmpty { return list }
        }
        return []
    }

    func current(for channel: Channel, at date: Date = .now) -> Programme? {
        schedule(for: channel).first { $0.isLive(at: date) }
    }

    func next(for channel: Channel, at date: Date = .now) -> Programme? {
        schedule(for: channel).first { $0.start > date }
    }

    /// Erst über `tvg-id`, sonst über den Sendernamen.
    static func lookupKeys(for channel: Channel) -> [String] {
        [channel.tvgID, channel.name].compactMap { $0?.lowercased() }.filter { !$0.isEmpty }
    }
}
