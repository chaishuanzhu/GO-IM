import UIKit
import ImageIO

/// Compresses images to match gateway upload rules (see docs/07-api-reference.md):
/// - JPEG body so `http.DetectContentType` → `image/jpeg` and Go `DecodeConfig` works
/// - Longest edge ≤ 4096px (server skips thumbnail above that)
/// - Size ≤ `object_storage.max_upload` (default 10MB)
enum ImageCompressor {
    /// Server thumbnail / bomb-defense limit.
    static let serverMaxPixel: CGFloat = 4096
    /// Server default `max_upload` (10 MiB).
    static let serverMaxBytes = 10 * 1024 * 1024
    /// Soft target under common reverse-proxy 1–20MB body limits.
    static let softMaxBytes = 900_000

    struct Result {
        let data: Data
        let width: Int
        let height: Int
    }

    /// Fast single-pass JPEG for immediate list preview (before full upload compress).
    static func quickPreviewJPEG(from image: UIImage, maxPixel: CGFloat = 1280, quality: CGFloat = 0.7) -> Result? {
        let working = resizedImage(image, maxPixel: maxPixel)
        guard let jpeg = working.jpegData(compressionQuality: quality), !jpeg.isEmpty else { return nil }
        let dims = pixelSize(of: jpeg) ?? pixelSize(of: working) ?? (0, 0)
        guard dims.0 > 0, dims.1 > 0 else { return nil }
        return Result(data: jpeg, width: dims.0, height: dims.1)
    }

    /// Always returns scale=1 JPEG with positive pixel dimensions (never HEIC/raw fallback).
    static func jpegDataForUpload(
        from image: UIImage,
        maxPixel: CGFloat = serverMaxPixel,
        quality: CGFloat = 0.72,
        softMaxBytes: Int = softMaxBytes,
        hardMaxBytes: Int = serverMaxBytes
    ) -> Result? {
        // Cap at server 4096 so thumbnail generation is eligible and dims decode cleanly.
        let cap = min(maxPixel, serverMaxPixel)
        var working = resizedImage(image, maxPixel: cap)
        var q = quality
        var data = working.jpegData(compressionQuality: q)

        while let current = data, current.count > softMaxBytes, q > 0.28 {
            q -= 0.08
            data = working.jpegData(compressionQuality: q)
        }
        if let current = data, current.count > softMaxBytes {
            working = resizedImage(working, maxPixel: 1600)
            q = 0.55
            data = working.jpegData(compressionQuality: q)
            while let current = data, current.count > softMaxBytes, q > 0.25 {
                q -= 0.08
                data = working.jpegData(compressionQuality: q)
            }
        }
        if let current = data, current.count > softMaxBytes {
            working = resizedImage(working, maxPixel: 1280)
            data = working.jpegData(compressionQuality: 0.45)
        }
        // Last resort: stay under hard server limit (10MB).
        if let current = data, current.count > hardMaxBytes {
            working = resizedImage(working, maxPixel: 1024)
            q = 0.4
            data = working.jpegData(compressionQuality: q)
            while let current = data, current.count > hardMaxBytes, q > 0.2 {
                q -= 0.05
                data = working.jpegData(compressionQuality: q)
            }
        }

        guard let jpeg = data, !jpeg.isEmpty, jpeg.count <= hardMaxBytes else { return nil }
        let dims = pixelSize(of: jpeg) ?? pixelSize(of: working) ?? (0, 0)
        guard dims.0 > 0, dims.1 > 0 else { return nil }
        return Result(data: jpeg, width: dims.0, height: dims.1)
    }

    /// `original == true` keeps a higher-quality JPEG under the server 10MB cap.
    static func jpegDataForUpload(from image: UIImage, original: Bool) -> Result? {
        if original {
            return jpegDataForUpload(
                from: image,
                maxPixel: serverMaxPixel,
                quality: 0.92,
                softMaxBytes: 8_000_000,
                hardMaxBytes: serverMaxBytes
            )
        }
        return jpegDataForUpload(from: image)
    }

    static func jpegDataForUpload(from raw: Data) -> Result? {
        guard let image = UIImage(data: raw) else { return nil }
        return jpegDataForUpload(from: image)
    }

    static func pixelSize(of data: Data) -> (Int, Int)? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        else { return nil }
        let w = intProp(props[kCGImagePropertyPixelWidth])
        let h = intProp(props[kCGImagePropertyPixelHeight])
        guard w > 0, h > 0 else { return nil }
        return (w, h)
    }

    private static func intProp(_ value: Any?) -> Int {
        if let i = value as? Int { return i }
        if let n = value as? NSNumber { return n.intValue }
        if let d = value as? Double { return Int(d) }
        return 0
    }

    private static func pixelSize(of image: UIImage) -> (Int, Int)? {
        if let cg = image.cgImage {
            switch image.imageOrientation {
            case .left, .leftMirrored, .right, .rightMirrored:
                return (cg.height, cg.width)
            default:
                return (cg.width, cg.height)
            }
        }
        let w = Int((image.size.width * image.scale).rounded())
        let h = Int((image.size.height * image.scale).rounded())
        guard w > 0, h > 0 else { return nil }
        return (w, h)
    }

    private static func resizedImage(_ image: UIImage, maxPixel: CGFloat) -> UIImage {
        let px = pixelSize(of: image).map { CGSize(width: $0.0, height: $0.1) } ?? {
            CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        }()
        let longest = max(px.width, px.height)
        guard longest > maxPixel, longest > 0 else {
            // Still re-render at scale=1 so JPEG pixels match points Go DecodeConfig reads.
            return render(image, size: CGSize(width: max(px.width, 1), height: max(px.height, 1)))
        }
        let scale = maxPixel / longest
        let newSize = CGSize(
            width: max((px.width * scale).rounded(.down), 1),
            height: max((px.height * scale).rounded(.down), 1)
        )
        return render(image, size: newSize)
    }

    private static func render(_ image: UIImage, size: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
