import UIKit
import Kingfisher

/// Decoded sticker UIImages shared across bubble reloads (avoids GIF re-decode flash).
@MainActor
enum StickerImageCache {
    static let shared = StickerImageCacheStore()
}

@MainActor
final class StickerImageCacheStore {
    private let cache = NSCache<NSString, UIImage>()

    init() {
        cache.countLimit = 128
    }

    func image(for key: String) -> UIImage? {
        cache.object(forKey: key as NSString)
    }

    func image(for key: String, data: Data) -> UIImage? {
        if let cached = cache.object(forKey: key as NSString) {
            return cached
        }
        guard let image = KingfisherWrapper<UIImage>.image(data: data, options: ImageCreatingOptions()) else {
            return nil
        }
        cache.setObject(image, forKey: key as NSString)
        return image
    }
}
