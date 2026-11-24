import Foundation

/// Puffer für Live TV: kurz für schnelles Umschalten, groß gegen Aussetzer.
enum LiveBuffer: String, CaseIterable, Identifiable {
    case fast
    case normal
    case large

    static let storageKey = "liveBuffer"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .fast: "Schnell"
        case .normal: "Normal"
        case .large: "Groß"
        }
    }

    /// VLC-Option `network-caching` in Millisekunden.
    var vlcCaching: Int {
        switch self {
        case .fast: 800
        case .normal: 1500
        case .large: 4000
        }
    }

    /// `preferredForwardBufferDuration` des Apple-Players in Sekunden (0 = automatisch).
    var appleForwardBuffer: Double {
        switch self {
        case .fast: 2
        case .normal: 0
        case .large: 20
        }
    }

    static var current: LiveBuffer {
        UserDefaults.standard.string(forKey: storageKey).flatMap(LiveBuffer.init(rawValue:)) ?? .normal
    }
}

/// Umwandlung von Halbbildern (1080i, 576i) in Vollbilder, nur bei VLC.
enum DeinterlaceMode: String, CaseIterable, Identifiable {
    case automatic
    case off
    case yadif2x
    case bob

    static let storageKey = "deinterlaceMode"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .automatic: "Automatisch"
        case .off: "Aus"
        case .yadif2x: "Immer (Yadif 2x)"
        case .bob: "Immer (Bob, sparsam)"
        }
    }

    /// Optionen für `VLCMedia`. „Automatisch“ wandelt nur Streams um, die als
    /// Halbbilder gekennzeichnet sind – mit Yadif 2x, das Bewegungen (z. B. Fußball)
    /// mit voller Bildrate (50 Bilder/s) flüssig zeigt.
    var vlcOptions: [AnyHashable: Any] {
        switch self {
        case .automatic: ["deinterlace": -1, "deinterlace-mode": "yadif2x"]
        case .off: ["deinterlace": 0]
        case .yadif2x: ["deinterlace": 1, "deinterlace-mode": "yadif2x"]
        case .bob: ["deinterlace": 1, "deinterlace-mode": "bob"]
        }
    }

    static var current: DeinterlaceMode {
        UserDefaults.standard.string(forKey: storageKey).flatMap(DeinterlaceMode.init(rawValue:)) ?? .automatic
    }
}

/// Bevorzugte Sprachen für Tonspur und Untertitel.
enum TrackLanguages {
    static let audioKey = "preferredAudioLanguage"
    static let subtitleKey = "preferredSubtitleLanguage"

    /// Auswahl in den Einstellungen; leer = automatisch bzw. aus.
    static let choices: [(code: String, title: String)] = [
        ("de", "Deutsch"), ("en", "Englisch"), ("tr", "Türkisch"), ("fr", "Französisch"),
        ("es", "Spanisch"), ("it", "Italienisch"), ("pl", "Polnisch"), ("ru", "Russisch"),
        ("ar", "Arabisch"),
    ]

    static var audio: String { UserDefaults.standard.string(forKey: audioKey) ?? "" }
    static var subtitle: String { UserDefaults.standard.string(forKey: subtitleKey) ?? "" }

    /// Kürzel und Namen, an denen Spuren erkannt werden (VLC: „Deutsch [German]“,
    /// „Track 2 - [deu]“; Apple: „Deutsch“, „Englisch“).
    private static let names: [String: (codes: [String], words: [String])] = [
        "de": (["de", "deu", "ger"], ["deutsch", "german", "allemand"]),
        "en": (["en", "eng"], ["english", "englisch", "anglais"]),
        "tr": (["tr", "tur"], ["türkçe", "turkce", "turkish", "türkisch"]),
        "fr": (["fr", "fra", "fre"], ["français", "francais", "french", "französisch"]),
        "es": (["es", "spa"], ["español", "espanol", "spanish", "spanisch"]),
        "it": (["it", "ita"], ["italiano", "italian", "italienisch"]),
        "pl": (["pl", "pol"], ["polski", "polish", "polnisch"]),
        "ru": (["ru", "rus"], ["русский", "russian", "russisch"]),
        "ar": (["ar", "ara"], ["العربية", "arabic", "arabisch"]),
    ]

    /// `true`, wenn der Spurname zur Sprache passt.
    static func matches(_ trackName: String, code: String) -> Bool {
        guard let entry = names[code] else { return false }
        let lowered = trackName.lowercased()
        let tokens = Set(lowered.split(whereSeparator: { !$0.isLetter }).map(String.init))
        if entry.codes.contains(where: { tokens.contains($0) }) { return true }
        return entry.words.contains { lowered.contains($0) }
    }
}
