import Foundation

public enum AttachmentKind: Sendable {
    case media
    case file

    var bucket: AttachmentBucket {
        switch self {
        case .media: return .media
        case .file: return .files
        }
    }
}

public enum AttachmentBucket: Sendable {
    case tmp
    case media
    case files
}

/// Disk-backed attachments under `Users/{uid}/{tmp,media,files}`.
public final class LocalMediaStore: @unchecked Sendable {
    public static let shared = LocalMediaStore()

    private let lock = NSLock()
    private var home: UserHome?

    public init() {}

    public func configure(home: UserHome) {
        lock.lock()
        defer { lock.unlock() }
        self.home = home
        try? home.ensureDirectories()
    }

    public func reset() {
        lock.lock()
        defer { lock.unlock() }
        home = nil
    }

    public static func isLocalFileId(_ fileId: String) -> Bool {
        fileId.hasPrefix("local:")
    }

    /// Stage outgoing bytes in `tmp/`; returns `local:{uuid}`.
    public func stage(data: Data, suggestedName: String) throws -> String {
        let root = try requireTmp()
        let id = "local:\(UUID().uuidString)"
        let url = root.appendingPathComponent(Self.diskKey(for: id), isDirectory: false)
        try data.write(to: url, options: .atomic)
        let metaURL = url.appendingPathExtension("name")
        try? suggestedName.data(using: .utf8)?.write(to: metaURL, options: .atomic)
        return id
    }

    /// Move staged local file (or tmp download) into `files/` or `media/` under the remote id.
    public func promote(localId: String, remoteFileId: String, to kind: AttachmentKind) throws {
        let home = try requireHome()
        let src = try resolveExistingURL(fileId: localId)
            ?? { throw NSError(domain: "LocalMediaStore", code: 2, userInfo: [
                NSLocalizedDescriptionKey: "staged file missing",
            ]) }()
        let destDir = kind == .file ? home.filesURL : home.mediaURL
        let suggestedName: String? = {
            let meta = src.appendingPathExtension("name")
            guard let data = try? Data(contentsOf: meta) else { return src.pathExtension.isEmpty ? nil : src.lastPathComponent }
            return String(data: data, encoding: .utf8)
        }()
        let key = Self.diskKey(for: remoteFileId)
        let fileName = Self.storedFileName(key: key, suggestedName: suggestedName)
        let dest = destDir.appendingPathComponent(fileName, isDirectory: false)
        try FileManager.default.createDirectory(at: destDir, withIntermediateDirectories: true)
        Self.removeMatching(prefix: key, in: destDir)
        try FileManager.default.moveItem(at: src, to: dest)
        let nameMeta = src.appendingPathExtension("name")
        if FileManager.default.fileExists(atPath: nameMeta.path) {
            try? FileManager.default.removeItem(at: nameMeta)
        }
        if let suggestedName, let data = suggestedName.data(using: .utf8) {
            try? data.write(to: dest.appendingPathExtension("name"), options: .atomic)
        }
    }

    /// Write downloaded bytes into tmp then promote to files (atomic complete).
    /// Uses `suggestedName`'s extension so Quick Look can resolve UTI (e.g. `.pdf`).
    public func storeDownload(fileId: String, from tempURL: URL, suggestedName: String? = nil) throws -> URL {
        let home = try requireHome()
        try home.ensureDirectories()
        let key = Self.diskKey(for: fileId)
        let fileName = Self.storedFileName(key: key, suggestedName: suggestedName)
        let tmpDest = home.tmpURL.appendingPathComponent(fileName, isDirectory: false)
        if FileManager.default.fileExists(atPath: tmpDest.path) {
            try? FileManager.default.removeItem(at: tmpDest)
        }
        // Also clear any prior extensionless / other-ext copies for this id.
        Self.removeMatching(prefix: key, in: home.tmpURL)
        try FileManager.default.moveItem(at: tempURL, to: tmpDest)
        let finalURL = home.filesURL.appendingPathComponent(fileName, isDirectory: false)
        Self.removeMatching(prefix: key, in: home.filesURL)
        try FileManager.default.moveItem(at: tmpDest, to: finalURL)
        if let suggestedName, let data = suggestedName.data(using: .utf8) {
            try? data.write(to: finalURL.appendingPathExtension("name"), options: .atomic)
        }
        return finalURL
    }

    /// Copy `source` into a Quick Look–friendly temp file that keeps `displayName`'s extension.
    public static func previewURL(copying source: URL, displayName: String) throws -> URL {
        let safe = displayName.isEmpty ? source.lastPathComponent : displayName
        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("goim-ql-\(UUID().uuidString)-\(safe)", isDirectory: false)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.copyItem(at: source, to: dest)
        return dest
    }

    public func data(for fileId: String) -> Data? {
        guard let url = urlIfPresent(for: fileId) else { return nil }
        return try? Data(contentsOf: url)
    }

    public func urlIfPresent(for fileId: String) -> URL? {
        resolveExistingURL(fileId: fileId)
    }

    public func replace(_ fileId: String, data: Data) throws {
        guard Self.isLocalFileId(fileId) else {
            throw NSError(domain: "LocalMediaStore", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "not a local file id",
            ])
        }
        let url = try requireTmp().appendingPathComponent(Self.diskKey(for: fileId), isDirectory: false)
        try data.write(to: url, options: .atomic)
    }

    public func remove(_ fileId: String) {
        guard let url = resolveExistingURL(fileId: fileId) else { return }
        try? FileManager.default.removeItem(at: url)
        try? FileManager.default.removeItem(at: url.appendingPathExtension("name"))
    }

    public func removeIncompleteDownload(fileId: String) {
        guard let home else { return }
        Self.removeMatching(prefix: Self.diskKey(for: fileId), in: home.tmpURL)
    }

    // MARK: - Internals

    private func requireHome() throws -> UserHome {
        lock.lock()
        defer { lock.unlock() }
        guard let home else {
            throw NSError(domain: "LocalMediaStore", code: 3, userInfo: [
                NSLocalizedDescriptionKey: "UserHome not configured",
            ])
        }
        return home
    }

    private func requireTmp() throws -> URL {
        let home = try requireHome()
        try home.ensureDirectories()
        return home.tmpURL
    }

    private func resolveExistingURL(fileId: String) -> URL? {
        lock.lock()
        let home = self.home
        lock.unlock()
        guard let home else { return nil }
        let key = Self.diskKey(for: fileId)
        let dirs: [URL]
        if Self.isLocalFileId(fileId) {
            dirs = [home.tmpURL]
        } else {
            dirs = [home.filesURL, home.mediaURL, home.tmpURL]
        }
        for dir in dirs {
            if let match = Self.findStoredFile(key: key, in: dir) {
                return match
            }
        }
        return nil
    }

    public static func diskKey(for fileId: String) -> String {
        var key = fileId
        if key.hasPrefix("local:") {
            key = String(key.dropFirst("local:".count))
        }
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_."))
        let cleaned = key.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" }
        let joined = String(cleaned)
        return joined.isEmpty ? UUID().uuidString : joined
    }

    public static func storedFileName(key: String, suggestedName: String?) -> String {
        let ext = fileExtension(from: suggestedName)
        return ext.isEmpty ? key : "\(key).\(ext)"
    }

    public static func fileExtension(from suggestedName: String?) -> String {
        guard let suggestedName, !suggestedName.isEmpty else { return "" }
        let ext = (suggestedName as NSString).pathExtension
            .trimmingCharacters(in: CharacterSet(charactersIn: "."))
            .lowercased()
        let allowed = CharacterSet.alphanumerics
        let cleaned = String(ext.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" })
        return cleaned
    }

    private static func findStoredFile(key: String, in dir: URL) -> URL? {
        let fm = FileManager.default
        let exact = dir.appendingPathComponent(key, isDirectory: false)
        if fm.fileExists(atPath: exact.path) { return exact }
        guard let items = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else {
            return nil
        }
        let matches = items.filter { url in
            let name = url.lastPathComponent
            if name.hasSuffix(".name") { return false }
            return name == key || name.hasPrefix("\(key).")
        }
        return matches.first
    }

    private static func removeMatching(prefix key: String, in dir: URL) {
        let fm = FileManager.default
        guard let items = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) else { return }
        for item in items {
            let name = item.lastPathComponent
            if name == key || name.hasPrefix("\(key).") {
                try? fm.removeItem(at: item)
            }
        }
    }

    public static func attachmentKind(mime: String, msgTypeHint: MsgTypeHint? = nil) -> AttachmentKind {
        if let msgTypeHint {
            switch msgTypeHint {
            case .file: return .file
            case .media: return .media
            }
        }
        let m = mime.lowercased()
        if m.hasPrefix("image/") || m.hasPrefix("video/") || m.hasPrefix("audio/") {
            return .media
        }
        return .file
    }

    public enum MsgTypeHint: Sendable {
        case file
        case media
    }
}
