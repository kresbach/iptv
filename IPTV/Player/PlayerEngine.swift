import Foundation

/// Welcher Player abspielen soll (Einstellung).
enum PlayerEngine: String, CaseIterable, Identifiable {
    case automatic
    case apple
    case vlc

    static let storageKey = "playerEngine"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .automatic: "Automatisch"
        case .apple: "Apple (AVPlayer)"
        case .vlc: "VLC"
        }
    }

    /// `true`, wenn das Paket „vlckit-spm“ im Projekt eingebunden ist.
    static var isVLCAvailable: Bool {
        #if canImport(VLCKitSPM) || canImport(MobileVLCKit)
        true
        #else
        false
        #endif
    }

    /// Formate, die AVPlayer nicht oder nur unzuverlässig abspielt.
    private static let vlcExtensions: Set<String> = [
        "ts", "mkv", "avi", "flv", "wmv", "mpg", "mpeg", "webm", "m2ts", "mts", "divx", "rmvb", "3gp",
    ]
    private static let vlcSchemes: Set<String> = ["rtmp", "rtsp", "udp", "rtp", "mms"]

    func resolve(for url: URL) -> ResolvedPlayer {
        switch self {
        case .apple:
            return .apple
        case .vlc:
            return Self.isVLCAvailable ? .vlc : .apple
        case .automatic:
            guard Self.isVLCAvailable else { return .apple }
            let ext = url.pathExtension.lowercased()
            let scheme = url.scheme?.lowercased() ?? ""
            return Self.vlcExtensions.contains(ext) || Self.vlcSchemes.contains(scheme) ? .vlc : .apple
        }
    }
}

enum ResolvedPlayer: Equatable {
    case apple
    case vlc
}
