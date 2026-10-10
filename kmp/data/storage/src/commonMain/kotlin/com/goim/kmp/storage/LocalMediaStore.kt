package com.goim.kmp.storage

import com.goim.kmp.model.LocalFileId
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 附件磁盘布局与 iOS `LocalMediaStore` 一致：
 * - 待发送：`tmp/{uuid}[.ext]`，消息里引用为 `local:{uuid}`
 * - 上传成功后（图片 / 视频 / 语音）：`media/{fileId}[.ext]`，其它：`files/{fileId}[.ext]`
 * - 下载：先写 `tmp/{fileId}.part`，完成后移动到 `files/`
 * - 同名 `.name` 文件保存原始文件名
 */
class LocalMediaStore(
    private val home: UserHome,
    private val fs: FileSystem = SystemFileSystem,
) {
    @OptIn(ExperimentalUuidApi::class)
    fun stage(bytes: ByteArray, suggestedName: String): String {
        fs.createDirectories(home.tmp)
        val id = LocalFileId.PREFIX + Uuid.random().toString().uppercase()
        val path = home.tmp / storedFileName(diskKey(id), suggestedName)
        writeAtomically(path, bytes)
        writeName(path, suggestedName)
        return id
    }

    fun bytes(fileId: String): ByteArray? = path(fileId)?.let { p -> runCatching { fs.read(p) { readByteArray() } }.getOrNull() }

    fun path(fileId: String): Path? {
        val key = diskKey(fileId)
        val dirs = if (LocalFileId.isLocal(fileId)) listOf(home.tmp) else listOf(home.files, home.media, home.tmp)
        return dirs.firstNotNullOfOrNull { findStored(key, it) }
    }

    fun displayName(fileId: String): String? =
        path(fileId)?.let { p -> runCatching { fs.read(nameFile(p)) { readUtf8() } }.getOrNull() }

    /** 复制到 `media/` 或 `files/`；暂存副本保留到 [remove]，避免 UI 在 content 改写前丢图。 */
    fun promote(localId: String, remoteFileId: String, mime: String) {
        val src = path(localId) ?: error("staged file missing")
        val name = runCatching { fs.read(nameFile(src)) { readUtf8() } }.getOrNull()
        val dir = if (isMedia(mime)) home.media else home.files
        fs.createDirectories(dir)
        val key = diskKey(remoteFileId)
        removeMatching(key, dir)
        val dest = dir / storedFileName(key, name ?: src.name)
        val tmp = dir / "$key.copying"
        fs.copy(src, tmp)
        fs.atomicMove(tmp, dest)
        name?.let { writeName(dest, it) }
    }

    /** 下载暂存文件；调用方写完后用 [commitDownload] 落盘。 */
    fun downloadTarget(fileId: String): Path {
        fs.createDirectories(home.tmp)
        val part = home.tmp / "${diskKey(fileId)}.part"
        fs.delete(part, mustExist = false)
        return part
    }

    fun commitDownload(fileId: String, part: Path, suggestedName: String?): Path {
        fs.createDirectories(home.files)
        val key = diskKey(fileId)
        removeMatching(key, home.files)
        val dest = home.files / storedFileName(key, suggestedName)
        fs.atomicMove(part, dest)
        suggestedName?.takeIf { it.isNotEmpty() }?.let { writeName(dest, it) }
        return dest
    }

    fun abortDownload(part: Path) {
        fs.delete(part, mustExist = false)
    }

    fun remove(fileId: String) {
        val p = path(fileId) ?: return
        fs.delete(p, mustExist = false)
        fs.delete(nameFile(p), mustExist = false)
    }

    private fun findStored(key: String, dir: Path): Path? {
        val exact = dir / key
        if (fs.exists(exact)) return exact
        val items = runCatching { fs.list(dir) }.getOrNull() ?: return null
        return items.firstOrNull { p ->
            val n = p.name
            !n.endsWith(".name") && !n.endsWith(".part") && !n.endsWith(".copying") && n.startsWith("$key.")
        }
    }

    private fun removeMatching(key: String, dir: Path) {
        runCatching { fs.list(dir) }.getOrNull()?.forEach { p ->
            if (p.name == key || p.name.startsWith("$key.")) fs.delete(p, mustExist = false)
        }
    }

    private fun writeAtomically(path: Path, bytes: ByteArray) {
        val tmp = path.parent!! / "${path.name}.copying"
        fs.sink(tmp).buffer().use { it.write(bytes) }
        fs.atomicMove(tmp, path)
    }

    private fun writeName(path: Path, name: String) {
        runCatching { fs.write(nameFile(path)) { writeUtf8(name) } }
    }

    private fun nameFile(path: Path) = path.parent!! / "${path.name}.name"

    companion object {
        fun diskKey(fileId: String): String {
            val key = fileId.removePrefix(LocalFileId.PREFIX)
            val cleaned = key.map { if (it.isAsciiAlnum() || it in "-_.") it else '_' }.joinToString("")
            return cleaned.ifEmpty { "unknown" }
        }

        fun fileExtension(name: String?): String {
            if (name.isNullOrEmpty() || !name.contains('.')) return ""
            return name.substringAfterLast('.').lowercase().map { if (it.isAsciiAlnum()) it else '_' }.joinToString("")
        }

        fun storedFileName(key: String, suggestedName: String?): String {
            val ext = fileExtension(suggestedName)
            return if (ext.isEmpty()) key else "$key.$ext"
        }

        fun isMedia(mime: String): Boolean {
            val m = mime.lowercase()
            return m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/")
        }

        private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
    }
}
