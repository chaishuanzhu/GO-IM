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
        let dest = destDir.appendingPathComponent(Self.diskKey(for: remoteFileId), isDirectory: false)
        try FileManager.default.createDirectory(at: destDir, withIntermediateDirectories: true)
        if FileManager.default.fileExists(atPath: dest.path) {
            try? FileManager.default.removeItem(at: dest)
        }
        try FileManager.default.moveItem(at: src, to: dest)
        let nameMeta = src.appendingPathExtension("name")
        if FileManager.default.fileExists(atPath: nameMeta.path) {
            try? FileManager.default.removeItem(at: nameMeta)
        }
    }

    /// Write downloaded bytes into tmp then promote to files (atomic complete).
    public func storeDownload(fileId: String, from tempURL: URL) throws -> URL {
        let home = try requireHome()
        try home.ensureDirectories()
        let tmpDest = home.tmpURL.appendingPathComponent(Self.diskKey(for: fileId), isDirectory: false)
        if FileManager.default.fileExists(atPath: tmpDest.path) {
            try? FileManager.default.removeItem(at: tmpDest)
        }
        try FileManager.default.moveItem(at: tempURL, to: tmpDest)
        let finalURL = home.filesURL.appendingPathComponent(Self.diskKey(for: fileId), isDirectory: false)
        if FileManager.default.fileExists(atPath: finalURL.path) {
            try? FileManager.default.removeItem(at: finalURL)
        }
        try FileManager.default.moveItem(at: tmpDest, to: finalURL)
        return finalURL
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
        let tmp = home.tmpURL.appendingPathComponent(Self.diskKey(for: fileId), isDirectory: false)
        try? FileManager.default.removeItem(at: tmp)
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
        let candidates: [URL]
        if Self.isLocalFileId(fileId) {
            candidates = [home.tmpURL.appendingPathComponent(key, isDirectory: false)]
        } else {
            candidates = [
                home.filesURL.appendingPathComponent(key, isDirectory: false),
                home.mediaURL.appendingPathComponent(key, isDirectory: false),
                home.tmpURL.appendingPathComponent(key, isDirectory: false),
            ]
        }
        return candidates.first { FileManager.default.fileExists(atPath: $0.path) }
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
