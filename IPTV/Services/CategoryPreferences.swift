import Foundation
import Observation

/// Ausgeblendete, gesperrte und umsortierte Kategorien sowie die Kindersicherungs-PIN.
@MainActor
@Observable
final class CategoryPreferences {
    static let shared = CategoryPreferences()
    private struct Stored: Codable {
        var hidden: Set<String> = []
        var locked: Set<String> = []
        /// Eigene Reihenfolge je Bereich (Kategorienamen).
        var order: [String: [String]] = [:]
    }

    private var stored = Stored()
    /// `true`, nachdem in dieser Sitzung die richtige PIN eingegeben wurde.
    private(set) var isUnlocked = false
    private(set) var hasPIN = false

    private static let defaultsKey = "categoryPreferences"
    private static let pinAccount = "parentalPIN"

    init() {
        if let data = UserDefaults.standard.data(forKey: Self.defaultsKey),
           let saved = try? JSONDecoder().decode(Stored.self, from: data) {
            stored = saved
        }
        hasPIN = Keychain.read(Self.pinAccount) != nil
    }

    private static func key(_ kind: ContentKind, _ name: String) -> String { "\(kind.rawValue)|\(name)" }

    // MARK: - Abfragen

    func isHidden(_ kind: ContentKind, _ name: String) -> Bool { stored.hidden.contains(Self.key(kind, name)) }
    func isLocked(_ kind: ContentKind, _ name: String) -> Bool { stored.locked.contains(Self.key(kind, name)) }

    /// Gesperrt und in dieser Sitzung noch nicht per PIN freigegeben.
    func isBlocked(_ kind: ContentKind, _ name: String) -> Bool {
        hasPIN && !isUnlocked && isLocked(kind, name)
    }

    /// Ob Einschränkungen aktiv sind (sonst kann das Filtern übersprungen werden).
    private var hasRestrictions: Bool {
        !stored.hidden.isEmpty || (hasPIN && !isUnlocked && !stored.locked.isEmpty)
    }

    func isVisible(_ channel: Channel) -> Bool {
        !isHidden(channel.kind, channel.category) && !isBlocked(channel.kind, channel.category)
    }

    func isVisible(_ show: SeriesShow) -> Bool {
        !isHidden(.series, show.category) && !isBlocked(.series, show.category)
    }

    /// Entfernt Einträge aus ausgeblendeten oder gesperrten Kategorien.
    func filter(_ channels: [Channel]) -> [Channel] {
        hasRestrictions ? channels.filter { isVisible($0) } : channels
    }

    func filter(_ shows: [SeriesShow]) -> [SeriesShow] {
        hasRestrictions ? shows.filter { isVisible($0) } : shows
    }

    /// Kategorien in eigener Reihenfolge; ausgeblendete nur auf Wunsch.
    func arranged(_ categories: [ChannelCategory], kind: ContentKind, includeHidden: Bool = false) -> [ChannelCategory] {
        var result = categories
        if let order = stored.order[kind.rawValue], !order.isEmpty {
            let position = Dictionary(order.enumerated().map { ($0.element, $0.offset) }, uniquingKeysWith: { first, _ in first })
            result = categories.enumerated().sorted { a, b in
                let pa = position[a.element.name] ?? Int.max
                let pb = position[b.element.name] ?? Int.max
                return pa != pb ? pa < pb : a.offset < b.offset
            }.map(\.element)
        }
        return includeHidden ? result : result.filter { !isHidden(kind, $0.name) }
    }

    // MARK: - Ändern

    func setHidden(_ hidden: Bool, kind: ContentKind, name: String) {
        if hidden { stored.hidden.insert(Self.key(kind, name)) } else { stored.hidden.remove(Self.key(kind, name)) }
        save()
    }

    func setLocked(_ locked: Bool, kind: ContentKind, name: String) {
        if locked { stored.locked.insert(Self.key(kind, name)) } else { stored.locked.remove(Self.key(kind, name)) }
        save()
    }

    /// Speichert die neue Reihenfolge nach dem Verschieben in der Bearbeiten-Liste.
    func move(kind: ContentKind, current: [ChannelCategory], from source: IndexSet, to destination: Int) {
        var names = current.map(\.name)
        names.move(fromOffsets: source, toOffset: destination)
        stored.order[kind.rawValue] = names
        save()
    }

    func resetOrder(kind: ContentKind) {
        stored.order[kind.rawValue] = nil
        save()
    }

    // MARK: - PIN

    /// Prüft die PIN und gibt gesperrte Kategorien für diese Sitzung frei.
    @discardableResult
    func unlock(with pin: String) -> Bool {
        guard let saved = Keychain.read(Self.pinAccount), saved == pin else { return false }
        isUnlocked = true
        return true
    }

    /// Wieder sperren (z. B. wenn die App in den Hintergrund geht).
    func lock() {
        isUnlocked = false
    }

    func setPIN(_ pin: String?) {
        if let pin, !pin.isEmpty {
            Keychain.write(pin, for: Self.pinAccount)
            hasPIN = true
            isUnlocked = true
        } else {
            Keychain.delete(Self.pinAccount)
            hasPIN = false
            isUnlocked = false
            stored.locked = []
            save()
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(stored) {
            UserDefaults.standard.set(data, forKey: Self.defaultsKey)
        }
    }
}
