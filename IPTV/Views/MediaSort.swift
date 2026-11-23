import SwiftUI

/// Sortierung für Filme und Serien. Die Auswahl bleibt je Bereich gespeichert.
enum MediaSort: String, CaseIterable, Identifiable {
    case standard
    case newest
    case title
    case rating
    case year
    case smallest
    case largest

    var id: String { rawValue }

    var title: String {
        switch self {
        case .standard: "Standard"
        case .newest: "Neu hinzugefügt"
        case .title: "A–Z"
        case .rating: "Bewertung"
        case .year: "Jahr"
        case .smallest: "Kleinste zuerst"
        case .largest: "Größte zuerst"
        }
    }

    var symbol: String {
        switch self {
        case .standard: "list.bullet"
        case .newest: "sparkles"
        case .title: "textformat"
        case .rating: "star"
        case .year: "calendar"
        case .smallest, .largest: "internaldrive"
        }
    }

    /// Größen gibt es nur bei Filmen (Dateigröße vom Server).
    var needsSizes: Bool { self == .smallest || self == .largest }

    static let movieOptions: [MediaSort] = allCases
    static let seriesOptions: [MediaSort] = [.standard, .newest, .title, .rating, .year]

    static let movieStorageKey = "movieSort"
    static let seriesStorageKey = "seriesSort"
    static let topRatedStorageKey = "onlyTopRated"
    /// Grenze für „Nur ★ 7+“.
    static let topRatedThreshold = 7.0

    /// Sortiert stabil; Einträge ohne Wert (z. B. ohne Bewertung) kommen ans Ende.
    func apply<Item>(
        to items: [Item],
        title: (Item) -> String,
        rating: (Item) -> Double?,
        added: (Item) -> Date?,
        year: (Item) -> Int?
    ) -> [Item] {
        switch self {
        case .standard, .smallest, .largest:
            return items
        case .title:
            return items
                .map { (item: $0, key: title($0)) }
                .sorted { $0.key.localizedStandardCompare($1.key) == .orderedAscending }
                .map(\.item)
        case .rating:
            return Self.descending(items, by: rating)
        case .year:
            return Self.descending(items, by: year)
        case .newest:
            // Ohne Datum (M3U): die zuletzt in der Playlist stehenden gelten als neu.
            if items.contains(where: { added($0) != nil }) {
                return Self.descending(items, by: added)
            }
            return items.reversed()
        }
    }

    private static func descending<Item, Key: Comparable>(_ items: [Item], by key: (Item) -> Key?) -> [Item] {
        let keyed = items.enumerated().map { (offset: $0.offset, item: $0.element, key: key($0.element)) }
        return keyed.sorted { a, b in
            switch (a.key, b.key) {
            case let (x?, y?) where x != y: return x > y
            case (_?, nil): return true
            case (nil, _?): return false
            default: return a.offset < b.offset
            }
        }.map(\.item)
    }
}

/// Menü in der Symbolleiste: Sortierung und „Nur ★ 7+“.
struct MediaSortMenu: View {
    @Binding var sort: MediaSort
    @Binding var onlyTopRated: Bool
    let options: [MediaSort]
    /// Ob überhaupt Bewertungen vorliegen (sonst ist der Filter sinnlos).
    let hasRatings: Bool

    var body: some View {
        Menu {
            Picker("Sortierung", selection: $sort) {
                ForEach(options) { option in
                    Label(option.title, systemImage: option.symbol).tag(option)
                }
            }
            if hasRatings {
                Divider()
                Toggle(isOn: $onlyTopRated) {
                    Label("Nur ★ \(Int(MediaSort.topRatedThreshold))+", systemImage: "star.fill")
                }
            }
        } label: {
            Label("Sortieren", systemImage: sort == .standard && !onlyTopRated
                  ? "arrow.up.arrow.down" : "arrow.up.arrow.down.circle.fill")
        }
    }
}

/// Kleine Bewertung auf Postern, z. B. „★ 7.4“.
struct RatingBadge: View {
    let rating: Double

    var body: some View {
        Text("★ \(rating.formatted(.number.precision(.fractionLength(1))))")
            .font(.caption2.weight(.bold).monospacedDigit())
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(.black.opacity(0.6), in: .capsule)
            .accessibilityLabel("Bewertung \(rating.formatted(.number.precision(.fractionLength(1)))) von 10")
    }
}

/// Merkt sich das letzte Sortierergebnis, damit große Listen nicht bei jedem
/// Neuzeichnen (z. B. Download-Fortschritt) erneut sortiert werden.
@MainActor
final class SortCache<Item> {
    private var key: AnyHashable?
    private var cached: [Item] = []

    func value(for key: AnyHashable, compute: () -> [Item]) -> [Item] {
        if key == self.key { return cached }
        cached = compute()
        self.key = key
        return cached
    }
}

/// Schlüssel für den `SortCache`: ändert sich, sobald sich Liste oder Auswahl ändern.
struct SortCacheKey: Hashable {
    let sort: MediaSort
    let onlyTopRated: Bool
    let query: String
    let count: Int
    let firstID: AnyHashable?
    let lastID: AnyHashable?
}
