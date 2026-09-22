import Foundation

/// Per-user Application Support layout (macOS Home analogy).
public struct UserHome: Sendable, Equatable {
    public let uid: String
    public let rootURL: URL

    public var databaseURL: URL { rootURL.appendingPathComponent("db/goim.sqlite", isDirectory: false) }
    public var mediaURL: URL { rootURL.appendingPathComponent("media", isDirectory: true) }
    public var filesURL: URL { rootURL.appendingPathComponent("files", isDirectory: true) }
    public var tmpURL: URL { rootURL.appendingPathComponent("tmp", isDirectory: true) }

    public init(uid: String) {
        self.uid = uid
        self.rootURL = Self.homeDirectory(uid: uid)
    }

    public static var applicationSupportGOIM: URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        return support.appendingPathComponent("GOIM", isDirectory: true)
    }

    public static var sharedDirectory: URL {
        applicationSupportGOIM.appendingPathComponent("Shared", isDirectory: true)
    }

    public static var usersDirectory: URL {
        applicationSupportGOIM.appendingPathComponent("Users", isDirectory: true)
    }

    public static var stickerCatalogDirectory: URL {
        applicationSupportGOIM.appendingPathComponent("Catalog/Stickers", isDirectory: true)
    }

    public static func sanitizeUID(_ uid: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_."))
        let cleaned = uid.unicodeScalars.map { allowed.contains($0) ? Character($0) : "_" }
        let joined = String(cleaned)
        return joined.isEmpty ? "unknown" : joined
    }

    public static func homeDirectory(uid: String) -> URL {
        usersDirectory.appendingPathComponent(sanitizeUID(uid), isDirectory: true)
    }

    public func ensureDirectories() throws {
        let fm = FileManager.default
        try fm.createDirectory(at: rootURL.appendingPathComponent("db", isDirectory: true), withIntermediateDirectories: true)
        try fm.createDirectory(at: mediaURL, withIntermediateDirectories: true)
        try fm.createDirectory(at: filesURL, withIntermediateDirectories: true)
        try fm.createDirectory(at: tmpURL, withIntermediateDirectories: true)
    }
}

/// Device-level prefs in `GOIM/Shared/device.json` (not per-user).
public struct DevicePreferences: Codable, Sendable, Equatable {
    public var activeUID: String?
    public var apiBaseURL: String?
    public var preferredTransport: String?

    public init(activeUID: String? = nil, apiBaseURL: String? = nil, preferredTransport: String? = nil) {
        self.activeUID = activeUID
        self.apiBaseURL = apiBaseURL
        self.preferredTransport = preferredTransport
    }

    private static var fileURL: URL {
        UserHome.sharedDirectory.appendingPathComponent("device.json", isDirectory: false)
    }

    public static func load() -> DevicePreferences {
        migrateLegacyIfNeeded()
        let url = fileURL
        guard let data = try? Data(contentsOf: url),
              let prefs = try? JSONDecoder().decode(DevicePreferences.self, from: data) else {
            return DevicePreferences()
        }
        return prefs
    }

    public func save() {
        let dir = UserHome.sharedDirectory
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        guard let data = try? JSONEncoder().encode(self) else { return }
        try? data.write(to: Self.fileURL, options: .atomic)
    }

    public static func update(_ mutate: (inout DevicePreferences) -> Void) {
        var prefs = load()
        mutate(&prefs)
        prefs.save()
    }

    /// Pull old UserDefaults / Keychain transport into device.json once.
    private static func migrateLegacyIfNeeded() {
        let url = fileURL
        if FileManager.default.fileExists(atPath: url.path) { return }

        var prefs = DevicePreferences()
        if let saved = UserDefaults.standard.string(forKey: "goim.apiBaseURL"), !saved.isEmpty {
            prefs.apiBaseURL = saved
        }
        // Transport may still live in Keychain under preferred_transport — KeychainStore migrates it.
        prefs.save()
    }
}
