package com.goim.kmp.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val mediaJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** 文件类消息（msg_type 2–5）的 `content`；字段与 iOS `encodeFileContent` 一致，非正数尺寸 / 时长不写出。 */
fun FileMeta.toContentJson(): String = buildJsonObject {
    put("file_id", fileId)
    put("name", name)
    put("size", size)
    put("mime", mime)
    width?.takeIf { it > 0 }?.let { put("width", it) }
    height?.takeIf { it > 0 }?.let { put("height", it) }
    thumbWidth?.takeIf { it > 0 }?.let { put("thumb_width", it) }
    thumbHeight?.takeIf { it > 0 }?.let { put("thumb_height", it) }
    duration?.takeIf { it > 0 }?.let { put("duration", it) }
}.toString()

/** 本地暂存的附件 ID（上传完成前写进消息 `content`），与 iOS `LocalMediaStore` 相同前缀。 */
object LocalFileId {
    const val PREFIX = "local:"

    fun isLocal(fileId: String): Boolean = fileId.startsWith(PREFIX)
}

/** 按扩展名推断 MIME（选择器不一定给出类型）。 */
object MimeTypes {
    private val byExtension = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
        "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
        "mp4" to "video/mp4", "m4v" to "video/x-m4v", "mov" to "video/quicktime", "3gp" to "video/3gpp",
        "webm" to "video/webm", "mkv" to "video/x-matroska", "avi" to "video/x-msvideo",
        "m4a" to "audio/mp4", "aac" to "audio/aac", "mp3" to "audio/mpeg", "wav" to "audio/wav",
        "amr" to "audio/amr", "ogg" to "audio/ogg", "flac" to "audio/flac",
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "json" to "application/json", "zip" to "application/zip", "rar" to "application/vnd.rar",
        "7z" to "application/x-7z-compressed",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )

    const val DEFAULT = "application/octet-stream"

    fun fromFileName(name: String): String =
        byExtension[name.substringAfterLast('.', "").lowercase()] ?: DEFAULT
}

/** 消息仍引用本地暂存文件（尚未上传成功）。 */
val Message.hasLocalAttachment: Boolean
    get() = msgType != MsgType.Text && msgType != MsgType.Sticker &&
        MessagePreview.decodeFileMeta(content)?.fileId?.let(LocalFileId::isLocal) == true

/** 贴纸消息（msg_type=6）的 `content`；以 pack_id + sticker_id 为准，`url` 是接收方未安装该包时的回退地址。 */
@Serializable
data class StickerRef(
    @SerialName("pack_id") val packId: String,
    @SerialName("sticker_id") val stickerId: String,
    val format: String = "png",
    val width: Int = 240,
    val height: Int = 240,
    val url: String? = null,
) {
    val key: String get() = "$packId/$stickerId"

    val isAnimated: Boolean get() = format.lowercase() == "gif" || format.lowercase() == "webp"

    fun toContentJson(): String = mediaJson.encodeToString(serializer(), this)

    companion object {
        fun decode(content: String): StickerRef? =
            runCatching { mediaJson.decodeFromString(serializer(), content) }.getOrNull()
    }
}

data class StickerItem(
    val packId: String,
    val stickerId: String,
    val fileName: String,
    val width: Int = 240,
    val height: Int = 240,
) {
    fun asRef(url: String? = null): StickerRef {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return StickerRef(packId, stickerId, ext.ifEmpty { "png" }, width, height, url)
    }
}

data class StickerPack(
    val packId: String,
    val name: String,
    val version: Int,
    val coverFileName: String?,
    val stickers: List<StickerItem>,
    val baseUrl: String?,
)
