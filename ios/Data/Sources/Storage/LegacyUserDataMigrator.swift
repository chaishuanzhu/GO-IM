import Foundation

/// One-shot move of legacy `GOIM/goim.sqlite` + `GOIM/staged` into `Users/{uid}/`.
public enum LegacyUserDataMigrator {
    public static func migrateIfNeeded(activeUID: String?) {
        let fm = FileManager.default
        let goim = UserHome.applicationSupportGOIM
        let legacyDB = goim.appendingPathComponent("goim.sqlite", isDirectory: false)
        guard fm.fileExists(atPath: legacyDB.path) else {
            migrateStagedOnlyIfNeeded(activeUID: activeUID)
            return
        }
        guard let uid = activeUID, !uid.isEmpty else { return }

        let home = UserHome(uid: uid)
        do {
            try home.ensureDirectories()
            try moveIfPresent(legacyDB, to: home.databaseURL)
            try moveIfPresent(
                goim.appendingPathComponent("goim.sqlite-wal", isDirectory: false),
                to: home.rootURL.appendingPathComponent("db/goim.sqlite-wal", isDirectory: false)
            )
            try moveIfPresent(
                goim.appendingPathComponent("goim.sqlite-shm", isDirectory: false),
                to: home.rootURL.appendingPathComponent("db/goim.sqlite-shm", isDirectory: false)
            )

            let staged = goim.appendingPathComponent("staged", isDirectory: true)
            if fm.fileExists(atPath: staged.path) {
                try mergeDirectory(staged, into: home.mediaURL)
                try? fm.removeItem(at: staged)
            }

            // Old Stickers → Catalog/Stickers
            let oldStickers = goim.appendingPathComponent("Stickers", isDirectory: true)
            let catalog = UserHome.stickerCatalogDirectory
            if fm.fileExists(atPath: oldStickers.path) {
                try fm.createDirectory(at: catalog, withIntermediateDirectories: true)
                try mergeDirectory(oldStickers, into: catalog)
                try? fm.removeItem(at: oldStickers)
            }
        } catch {
            // Best-effort; leave legacy files if move fails so next launch can retry.
        }
    }

    private static func migrateStagedOnlyIfNeeded(activeUID: String?) {
        guard let uid = activeUID, !uid.isEmpty else { return }
        let fm = FileManager.default
        let staged = UserHome.applicationSupportGOIM.appendingPathComponent("staged", isDirectory: true)
        guard fm.fileExists(atPath: staged.path) else { return }
        let home = UserHome(uid: uid)
        try? home.ensureDirectories()
        try? mergeDirectory(staged, into: home.mediaURL)
        try? fm.removeItem(at: staged)
    }

    private static func moveIfPresent(_ src: URL, to dest: URL) throws {
        let fm = FileManager.default
        guard fm.fileExists(atPath: src.path) else { return }
        if fm.fileExists(atPath: dest.path) {
            try fm.removeItem(at: src)
            return
        }
        try fm.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.moveItem(at: src, to: dest)
    }

    private static func mergeDirectory(_ src: URL, into dest: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: dest, withIntermediateDirectories: true)
        let items = try fm.contentsOfDirectory(at: src, includingPropertiesForKeys: nil)
        for item in items {
            let target = dest.appendingPathComponent(item.lastPathComponent, isDirectory: false)
            if fm.fileExists(atPath: target.path) {
                try? fm.removeItem(at: item)
            } else {
                try fm.moveItem(at: item, to: target)
            }
        }
    }
}
