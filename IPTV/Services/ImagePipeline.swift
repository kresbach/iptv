import ImageIO
import SwiftUI
import UIKit

/// Lädt Logos und Cover einmal, verkleinert sie im Hintergrund auf Anzeigegröße
/// und hält sie im Speicher. Das hält lange Senderlisten flüssig.
actor ImagePipeline {
    static let shared = ImagePipeline()

    private nonisolated(unsafe) let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        // Nach Speichergröße begrenzen (ca. 80 MB), nicht nach Anzahl – Poster sind groß.
        cache.totalCostLimit = 80 * 1024 * 1024
        return cache
    }()
    private var running: [String: Task<UIImage?, Never>] = [:]

    nonisolated func cachedImage(for url: URL, maxPixelSize: CGFloat) -> UIImage? {
        cache.object(forKey: Self.key(url, maxPixelSize) as NSString)
    }

    func image(for url: URL, maxPixelSize: CGFloat) async -> UIImage? {
        let key = Self.key(url, maxPixelSize)
        if let cached = cache.object(forKey: key as NSString) { return cached }
        if let task = running[key] { return await task.value }

        let task = Task.detached(priority: .utility) { () -> UIImage? in
            guard let data = try? await HTTPClient.data(from: url, useCache: true) else { return nil }
            return Self.downsample(data, maxPixelSize: maxPixelSize)
        }
        running[key] = task
        let image = await task.value
        running[key] = nil
        if let image {
            let cost = image.cgImage.map { $0.bytesPerRow * $0.height } ?? 0
            cache.setObject(image, forKey: key as NSString, cost: cost)
        }
        return image
    }

    private static func key(_ url: URL, _ size: CGFloat) -> String { "\(Int(size))|\(url.absoluteString)" }

    private static func downsample(_ data: Data, maxPixelSize: CGFloat) -> UIImage? {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions) else { return nil }
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ] as CFDictionary
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else { return nil }
        return UIImage(cgImage: image)
    }
}

/// Bild aus dem Netz über die `ImagePipeline`. Zeigt nichts an, solange es lädt
/// oder fehlt, damit der Platzhalter darunter sichtbar bleibt.
struct CachedImage: View {
    let url: URL?
    let maxPixelSize: CGFloat
    var contentMode: ContentMode = .fill
    /// Hintergrund und Innenabstand, sobald ein Bild da ist (z. B. für transparente Logos).
    var loadedBackground: Color?
    var inset: CGFloat = 0

    @State private var image: UIImage?

    init(
        url: URL?,
        maxPixelSize: CGFloat,
        contentMode: ContentMode = .fill,
        loadedBackground: Color? = nil,
        inset: CGFloat = 0
    ) {
        self.url = url
        self.maxPixelSize = maxPixelSize
        self.contentMode = contentMode
        self.loadedBackground = loadedBackground
        self.inset = inset
        _image = State(initialValue: url.flatMap {
            ImagePipeline.shared.cachedImage(for: $0, maxPixelSize: maxPixelSize)
        })
    }

    var body: some View {
        ZStack {
            if let image {
                if let loadedBackground { loadedBackground }
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
                    .padding(inset)
            }
        }
        .task(id: url) {
            guard let url else { return }
            if image == nil {
                image = await ImagePipeline.shared.image(for: url, maxPixelSize: maxPixelSize)
            }
        }
    }
}
