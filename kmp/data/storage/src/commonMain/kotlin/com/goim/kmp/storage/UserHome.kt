package com.goim.kmp.storage

import com.goim.kmp.common.PlatformContext
import okio.FileSystem
import okio.Path

/** 平台文件系统（Okio `FileSystem.SYSTEM` 不在 common 元数据中）。 */
expect val SystemFileSystem: FileSystem

/** `GOIM/` 根目录：Android `filesDir/GOIM`、iOS `Application Support/GOIM`、Desktop 用户数据目录。 */
expect fun goimRootDirectory(context: PlatformContext): Path

/** 目录布局与 iOS `UserHome` 一致（docs/11）。 */
class AppDirectories(val root: Path) {
    val shared: Path get() = root / "Shared"
    val users: Path get() = root / "Users"
    val deviceJson: Path get() = shared / "device.json"

    fun userHome(uid: String) = UserHome(uid, users / UserHome.sanitizeUid(uid))
}

class UserHome(val uid: String, val root: Path) {
    val databaseFile: Path get() = root / "db" / "goim.sqlite"
    val media: Path get() = root / "media"
    val files: Path get() = root / "files"
    val tmp: Path get() = root / "tmp"

    fun ensureDirectories(fs: FileSystem = SystemFileSystem) {
        listOf(root / "db", media, files, tmp).forEach { fs.createDirectories(it) }
    }

    companion object {
        fun sanitizeUid(uid: String): String {
            val cleaned = uid.map { if (it.isLetterOrDigit() || it in "-_.") it else '_' }.joinToString("")
            return cleaned.ifEmpty { "unknown" }
        }
    }
}
