import Foundation
import UIKit
import Domain

/// Local sticker packs + OSS catalog (`https://oss.chaisz.com/im-sticker-pack`).
public actor StickerRepositoryImpl: StickerRepository {
    public static let defaultCatalogURL = URL(string: "https://oss.chaisz.com/im-sticker-pack/catalog.json")!

    private let rootURL: URL
    private let defaults: UserDefaults
    private var uid: String?
    private let catalogCacheKey = "goim.stickers.catalogCache"
    private let versionsKey = "goim.stickers.packVersions"
    private var memoryCache: [String: Data] = [:]
    /// Preset / last-known catalog (bundle + OSS).
    private var catalogPacks: [StickerPack] = []

    private var recentKey: String {
        if let uid, !uid.isEmpty {
            return "goim.\(UserHome.sanitizeUID(uid)).stickers.recent"
        }
        return "goim.stickers.recent"
    }

    public init(fileManager: FileManager = .default, defaults: UserDefaults = .standard) {
        rootURL = UserHome.stickerCatalogDirectory
        self.defaults = defaults
        try? fileManager.createDirectory(at: rootURL, withIntermediateDirectories: true)
    }

    public func setActiveUID(_ uid: String?) {
        self.uid = uid
        // One-shot: migrate global recent list into the first account that opens stickers.
        if let uid, !uid.isEmpty {
            let key = "goim.\(UserHome.sanitizeUID(uid)).stickers.recent"
            if defaults.stringArray(forKey: key) == nil,
               let legacy = defaults.stringArray(forKey: "goim.stickers.recent"), !legacy.isEmpty {
                defaults.set(legacy, forKey: key)
                defaults.removeObject(forKey: "goim.stickers.recent")
            }
        }
    }

    /// Load bundled catalog.json so the panel has an index before OSS sync.
    public func loadPresetCatalog() {
        if let url = Bundle.main.url(forResource: "catalog", withExtension: "json", subdirectory: "Stickers")
            ?? Bundle.main.url(forResource: "catalog", withExtension: "json"),
           let data = try? Data(contentsOf: url) {
            applyCatalogData(data)
            return
        }
        if let cached = defaults.data(forKey: catalogCacheKey) {
            applyCatalogData(cached)
        }
    }

    public func prepareBuiltInPackIfNeeded() {
        loadPresetCatalog()
    }

    public func installedPacks() async -> [StickerPack] {
        loadPresetCatalog()
        // Prefer catalog order; merge local stickers list when pack.json is on disk.
        var result: [StickerPack] = []
        var seen = Set<String>()
        for pack in catalogPacks {
            if let local = loadPack(at: rootURL.appendingPathComponent(pack.packId, isDirectory: true)) {
                result.append(local)
            } else {
                result.append(pack)
            }
            seen.insert(pack.packId)
        }
        if let dirs = try? FileManager.default.contentsOfDirectory(
            at: rootURL,
            includingPropertiesForKeys: [.isDirectoryKey],
            options: [.skipsHiddenFiles]
        ) {
            for dir in dirs {
                var isDir: ObjCBool = false
                guard FileManager.default.fileExists(atPath: dir.path, isDirectory: &isDir), isDir.boolValue,
                      let pack = loadPack(at: dir), !seen.contains(pack.packId) else { continue }
                result.append(pack)
            }
        }
        return result
    }

    public func stickers(in packId: String) async -> [StickerItem] {
        try? await ensurePackManifest(packId: packId)
        if let local = loadPack(at: rootURL.appendingPathComponent(packId, isDirectory: true)) {
            return local.stickers
        }
        return catalogPacks.first(where: { $0.packId == packId })?.stickers ?? []
    }

    public func imageData(for ref: StickerRef) async -> Data? {
        let key = "\(ref.packId)/\(ref.stickerId)"
        if let cached = memoryCache[key] { return cached }

        // 1) Local pack file
        if let local = await localFileURL(packId: ref.packId, stickerId: ref.stickerId),
           let data = try? Data(contentsOf: local), !data.isEmpty {
            memoryCache[key] = data
            return data
        }

        // 2) Explicit CDN url on the message
        if let urlString = ref.url,
           let url = URL(string: urlString),
           url.scheme == "http" || url.scheme == "https",
           let data = await fetchAndCache(url: url, packId: ref.packId, stickerId: ref.stickerId, format: ref.format) {
            return data
        }

        // 3) Catalog / pack base_url
        if let cdn = await resolveCDNURL(for: ref),
           let data = await fetchAndCache(url: cdn, packId: ref.packId, stickerId: ref.stickerId, format: ref.format) {
            return data
        }
        return nil
    }

    public func localFileURL(packId: String, stickerId: String) async -> URL? {
        let packDir = rootURL.appendingPathComponent(packId, isDirectory: true)
        if let pack = loadPack(at: packDir),
           let item = pack.stickers.first(where: { $0.stickerId == stickerId }) {
            let url = packDir.appendingPathComponent(item.fileName)
            if FileManager.default.fileExists(atPath: url.path) { return url }
        }
        for ext in ["png", "webp", "gif", "jpg", "jpeg"] {
            let url = packDir.appendingPathComponent("\(stickerId).\(ext)")
            if FileManager.default.fileExists(atPath: url.path) { return url }
        }
        return nil
    }

    public func recordRecent(_ ref: StickerRef) async {
        var list = defaults.stringArray(forKey: recentKey) ?? []
        let token = "\(ref.packId)/\(ref.stickerId)"
        list.removeAll { $0 == token }
        list.insert(token, at: 0)
        if list.count > 20 { list = Array(list.prefix(20)) }
        defaults.set(list, forKey: recentKey)
    }

    public func recentStickers(limit: Int) async -> [StickerRef] {
        let list = defaults.stringArray(forKey: recentKey) ?? []
        var refs: [StickerRef] = []
        for token in list.prefix(limit) {
            let parts = token.split(separator: "/", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { continue }
            let packId = parts[0]
            let stickerId = parts[1]
            if let item = await stickers(in: packId).first(where: { $0.stickerId == stickerId }) {
                refs.append(await enrichedRef(item))
            } else {
                refs.append(StickerRef(packId: packId, stickerId: stickerId))
            }
        }
        return refs
    }

    public func syncCatalog(from catalogURL: URL?) async throws {
        loadPresetCatalog()
        let url = catalogURL ?? Self.defaultCatalogURL
        let (data, response) = try await URLSession.shared.data(from: url)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw DomainError.server(http.statusCode, "sticker catalog")
        }
        applyCatalogData(data)
        defaults.set(data, forKey: catalogCacheKey)

        var versions = defaults.dictionary(forKey: versionsKey) as? [String: Int] ?? [:]
        for pack in catalogPacks {
            let localVersion = versions[pack.packId] ?? 0
            if pack.version > localVersion || loadPack(at: rootURL.appendingPathComponent(pack.packId)) == nil {
                try? await ensurePackManifest(packId: pack.packId)
                versions[pack.packId] = pack.version
            }
        }
        defaults.set(versions, forKey: versionsKey)
    }

    /// Absolute CDN URL for sending (receiver can download if pack missing).
    public func publicURL(for item: StickerItem) async -> String? {
        try? await ensurePackManifest(packId: item.packId)
        if let pack = loadPack(at: rootURL.appendingPathComponent(item.packId)),
           let base = pack.baseURL, !base.isEmpty {
            return joinURL(base, item.fileName)
        }
        if let pack = catalogPacks.first(where: { $0.packId == item.packId }),
           let base = pack.baseURL, !base.isEmpty {
            return joinURL(base, item.fileName)
        }
        return "https://oss.chaisz.com/im-sticker-pack/\(item.packId)/\(item.fileName)"
    }

    public func enrichedRef(_ item: StickerItem) async -> StickerRef {
        var ref = item.asRef()
        ref.url = await publicURL(for: item)
        return ref
    }

    // MARK: - Private

    private func ensurePackManifest(packId: String) async throws {
        let packDir = rootURL.appendingPathComponent(packId, isDirectory: true)
        let manifest = packDir.appendingPathComponent("pack.json")
        if FileManager.default.fileExists(atPath: manifest.path) { return }

        guard let pack = catalogPacks.first(where: { $0.packId == packId }),
              let base = pack.baseURL,
              let manifestURL = URL(string: joinURL(base, "pack.json")) else {
            throw DomainError.invalidState("unknown sticker pack \(packId)")
        }
        let (data, _) = try await URLSession.shared.data(from: manifestURL)
        try FileManager.default.createDirectory(at: packDir, withIntermediateDirectories: true)
        try data.write(to: manifest, options: .atomic)
        // Persist base_url if missing in remote pack.json
        if var obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if obj["base_url"] == nil { obj["base_url"] = base }
            if obj["pack_id"] == nil { obj["pack_id"] = packId }
            let rewritten = try JSONSerialization.data(withJSONObject: obj, options: [.prettyPrinted])
            try rewritten.write(to: manifest, options: .atomic)
        }
    }

    private func fetchAndCache(url: URL, packId: String, stickerId: String, format: String) async -> Data? {
        do {
            let (data, _) = try await URLSession.shared.data(from: url)
            guard !data.isEmpty else { return nil }
            let key = "\(packId)/\(stickerId)"
            memoryCache[key] = data
            let packDir = rootURL.appendingPathComponent(packId, isDirectory: true)
            try? FileManager.default.createDirectory(at: packDir, withIntermediateDirectories: true)
            let ext = format.isEmpty ? (url.pathExtension.isEmpty ? "png" : url.pathExtension) : format
            let fileURL = packDir.appendingPathComponent("\(stickerId).\(ext)")
            try? data.write(to: fileURL, options: .atomic)
            return data
        } catch {
            return nil
        }
    }

    private func resolveCDNURL(for ref: StickerRef) async -> URL? {
        try? await ensurePackManifest(packId: ref.packId)
        if let pack = loadPack(at: rootURL.appendingPathComponent(ref.packId)),
           let item = pack.stickers.first(where: { $0.stickerId == ref.stickerId }),
           let base = pack.baseURL {
            return URL(string: joinURL(base, item.fileName))
        }
        if let pack = catalogPacks.first(where: { $0.packId == ref.packId }),
           let base = pack.baseURL {
            let file = pack.stickers.first(where: { $0.stickerId == ref.stickerId })?.fileName
                ?? "\(ref.stickerId).\(ref.format.isEmpty ? "png" : ref.format)"
            return URL(string: joinURL(base, file))
        }
        return URL(string: "https://oss.chaisz.com/im-sticker-pack/\(ref.packId)/\(ref.stickerId).\(ref.format.isEmpty ? "png" : ref.format)")
    }

    private func applyCatalogData(_ data: Data) {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let packs = root["packs"] as? [[String: Any]] else { return }
        catalogPacks = packs.compactMap { entry in
            guard let packId = entry["pack_id"] as? String,
                  let name = entry["name"] as? String else { return nil }
            let version = entry["version"] as? Int ?? 1
            let baseURL = entry["base_url"] as? String
            let cover = entry["cover"] as? String
            // Catalog may omit full sticker list; fill after pack.json download.
            let stickers: [StickerItem] = (entry["stickers"] as? [[String: Any]] ?? []).compactMap { s in
                guard let id = s["id"] as? String else { return nil }
                let file = (s["file"] as? String) ?? "\(id).png"
                return StickerItem(
                    packId: packId,
                    stickerId: id,
                    fileName: file,
                    width: s["w"] as? Int ?? 240,
                    height: s["h"] as? Int ?? 240
                )
            }
            return StickerPack(
                packId: packId,
                name: name,
                version: version,
                coverFileName: cover,
                stickers: stickers,
                baseURL: baseURL
            )
        }
    }

    private func loadPack(at dir: URL) -> StickerPack? {
        let manifest = dir.appendingPathComponent("pack.json")
        guard let data = try? Data(contentsOf: manifest),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let packId = obj["pack_id"] as? String,
              let name = obj["name"] as? String else { return nil }
        let version = obj["version"] as? Int ?? 1
        let cover = obj["cover"] as? String
        let baseURL = obj["base_url"] as? String
        let rawStickers = obj["stickers"] as? [[String: Any]] ?? []
        let stickers: [StickerItem] = rawStickers.compactMap { s in
            guard let id = s["id"] as? String else { return nil }
            let file = (s["file"] as? String) ?? "\(id).png"
            return StickerItem(
                packId: packId,
                stickerId: id,
                fileName: file,
                width: s["w"] as? Int ?? 240,
                height: s["h"] as? Int ?? 240
            )
        }
        return StickerPack(
            packId: packId,
            name: name,
            version: version,
            coverFileName: cover,
            stickers: stickers,
            baseURL: baseURL
        )
    }

    private func joinURL(_ base: String, _ file: String) -> String {
        if base.hasSuffix("/") { return base + file }
        return base + "/" + file
    }
}
