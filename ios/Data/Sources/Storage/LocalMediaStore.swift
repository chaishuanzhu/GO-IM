import Foundation

/// Disk-backed staging for outgoing media so bubbles can render before upload finishes.
public final class LocalMediaStore: @unchecked Sendable {
    public static let shared = LocalMediaStore()

    private let root: URL

    public init(root: URL? = nil) {
        if let root {
            self.root = root
        } else {
            let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            self.root = support.appendingPathComponent("GOIM/staged", isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: self.root, withIntermediateDirectories: true)
    }

    public static func isLocalFileId(_ fileId: String) -> Bool {
        fileId.hasPrefix("local:")
    }

    public func stage(data: Data, suggestedName: String) throws -> String {
        let id = "local:\(UUID().uuidString)"
        let url = path(for: id)
        try data.write(to: url, options: .atomic)
        let metaURL = url.appendingPathExtension("name")
        try? suggestedName.data(using: .utf8)?.write(to: metaURL, options: .atomic)
        return id
    }

    public func data(for fileId: String) -> Data? {
        guard Self.isLocalFileId(fileId) else { return nil }
        return try? Data(contentsOf: path(for: fileId))
    }

    public func urlIfPresent(for fileId: String) -> URL? {
        guard Self.isLocalFileId(fileId) else { return nil }
        let url = path(for: fileId)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    public func replace(_ fileId: String, data: Data) throws {
        guard Self.isLocalFileId(fileId) else {
            throw NSError(domain: "LocalMediaStore", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "not a local file id",
            ])
        }
        try data.write(to: path(for: fileId), options: .atomic)
    }

    public func remove(_ fileId: String) {
        guard Self.isLocalFileId(fileId) else { return }
        let url = path(for: fileId)
        try? FileManager.default.removeItem(at: url)
        try? FileManager.default.removeItem(at: url.appendingPathExtension("name"))
    }

    private func path(for fileId: String) -> URL {
        let key = String(fileId.dropFirst("local:".count))
        return root.appendingPathComponent(key, isDirectory: false)
    }
}
