import UIKit
import Photos
import PhotosUI
import UniformTypeIdentifiers
import AVFoundation
import Domain

/// Shared media loading helpers for chat pickers / asset confirm.
@MainActor
enum ChatMediaLoader {
    static func loadUIImage(from asset: PHAsset) async -> UIImage? {
        await withCheckedContinuation { cont in
            let opts = PHImageRequestOptions()
            opts.deliveryMode = .highQualityFormat
            opts.resizeMode = .none
            opts.isNetworkAccessAllowed = true
            var resumed = false
            PHImageManager.default().requestImage(
                for: asset,
                targetSize: PHImageManagerMaximumSize,
                contentMode: .aspectFit,
                options: opts
            ) { image, info in
                let cancelled = (info?[PHImageCancelledKey] as? Bool) ?? false
                let failed = info?[PHImageErrorKey] != nil
                let degraded = (info?[PHImageResultIsDegradedKey] as? Bool) ?? false
                if cancelled || failed {
                    guard !resumed else { return }
                    resumed = true
                    cont.resume(returning: nil)
                    return
                }
                if degraded { return }
                guard !resumed else { return }
                resumed = true
                cont.resume(returning: image)
            }
        }
    }

    static func loadUIImage(from provider: NSItemProvider) async throws -> UIImage {
        if provider.canLoadObject(ofClass: UIImage.self) {
            return try await withCheckedThrowingContinuation { cont in
                provider.loadObject(ofClass: UIImage.self) { object, error in
                    if let error { cont.resume(throwing: error) }
                    else if let image = object as? UIImage { cont.resume(returning: image) }
                    else { cont.resume(throwing: DomainError.invalidState("无法读取图片对象")) }
                }
            }
        }
        let data = try await loadImageData(from: provider)
        guard let image = UIImage(data: data) else {
            throw DomainError.invalidState("图片解码失败")
        }
        return image
    }

    static func loadImageData(from provider: NSItemProvider) async throws -> Data {
        if provider.canLoadObject(ofClass: UIImage.self) {
            let image: UIImage = try await withCheckedThrowingContinuation { cont in
                provider.loadObject(ofClass: UIImage.self) { object, error in
                    if let error { cont.resume(throwing: error) }
                    else if let image = object as? UIImage { cont.resume(returning: image) }
                    else { cont.resume(throwing: DomainError.invalidState("无法读取图片对象")) }
                }
            }
            if let data = image.jpegData(compressionQuality: 0.8) { return data }
        }
        if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
            return try await withCheckedThrowingContinuation { cont in
                provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, error in
                    if let error { cont.resume(throwing: error) }
                    else if let data, !data.isEmpty { cont.resume(returning: data) }
                    else { cont.resume(throwing: DomainError.invalidState("图片数据为空")) }
                }
            }
        }
        throw DomainError.invalidState("当前照片无法加载，请换一张重试")
    }

    static func loadVideo(from provider: NSItemProvider) async throws -> (Data, String, String, Int) {
        let typeId = provider.hasItemConformingToTypeIdentifier(UTType.movie.identifier)
            ? UTType.movie.identifier : "public.movie"
        let url: URL = try await withCheckedThrowingContinuation { cont in
            provider.loadFileRepresentation(forTypeIdentifier: typeId) { url, error in
                if let error { cont.resume(throwing: error); return }
                guard let url else {
                    cont.resume(throwing: DomainError.invalidState("视频数据为空"))
                    return
                }
                let dest = FileManager.default.temporaryDirectory
                    .appendingPathComponent("goim-video-\(UUID().uuidString)-\(url.lastPathComponent)")
                do {
                    try? FileManager.default.removeItem(at: dest)
                    try FileManager.default.copyItem(at: url, to: dest)
                    cont.resume(returning: dest)
                } catch {
                    cont.resume(throwing: error)
                }
            }
        }
        defer { try? FileManager.default.removeItem(at: url) }
        let data = try Data(contentsOf: url)
        let duration = Int(ceil(CMTimeGetSeconds(AVURLAsset(url: url).duration)))
        let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "video/mp4"
        return (data, url.lastPathComponent, mime, max(duration, 0))
    }
}
