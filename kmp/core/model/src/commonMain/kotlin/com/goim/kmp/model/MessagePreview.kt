package com.goim.kmp.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt

/** 会话列表单行预览与 `content` JSON 解析；文案与 iOS `MessagePreview.swift` 一致。 */
object MessagePreview {

    private val json = Json { ignoreUnknownKeys = true }

    /** 会话列表预览（不出现原始 JSON）。 */
    fun listPreview(message: Message): String = preview(message.msgType, message.content)

    fun preview(msgType: MsgType, content: String): String = when (msgType) {
        MsgType.Text -> textPreview(content)
        MsgType.Image -> "[图片]"
        MsgType.Voice -> decodeFileMeta(content)?.duration?.takeIf { it > 0 }?.let { "[语音] $it\"" } ?: "[语音]"
        MsgType.Video -> decodeFileMeta(content)?.duration?.takeIf { it > 0 }?.let { "[视频] $it\"" } ?: "[视频]"
        MsgType.File -> decodeFileMeta(content)?.name?.takeIf { it.isNotEmpty() }?.let { "[文件] $it" } ?: "[文件]"
        MsgType.Sticker -> "[表情]"
        is MsgType.Unsupported -> MsgType.UNSUPPORTED_PLACEHOLDER
    }

    /** 兜底清洗已落库的原始 JSON 预览。 */
    fun sanitizedListPreview(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return raw
        val obj = parseObject(trimmed) ?: return raw
        systemNotice(obj)?.let { return it }
        if ("file_id" !in obj) return raw

        val mime = obj.string("mime")
        val duration = obj.int("duration")?.takeIf { it > 0 }
        if (mime != null) {
            if (mime.startsWith("image/")) return "[图片]"
            if (mime.startsWith("audio/")) return duration?.let { "[语音] $it\"" } ?: "[语音]"
            if (mime.startsWith("video/")) return duration?.let { "[视频] $it\"" } ?: "[视频]"
        }
        if ("width" in obj || "height" in obj) return "[图片]"
        if (duration != null) return if ("name" in obj) "[视频] $duration\"" else "[语音] $duration\""
        val name = obj.string("name")
        return if (!name.isNullOrEmpty()) "[文件] $name" else "[文件]"
    }

    /** 群系统通知文案；`content` 不是已知通知时返回 null。 */
    fun systemNotice(content: String): String? = parseObject(content)?.let(::systemNotice)

    /** 解析文件 / 图片 / 语音 / 视频消息的 `content` JSON。 */
    fun decodeFileMeta(content: String): FileMeta? {
        val obj = parseObject(content) ?: return null
        val fileId = (obj["file_id"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: return null
        val width = (obj.int("width") ?: obj.int("thumb_width"))?.takeIf { it > 0 }
        val height = (obj.int("height") ?: obj.int("thumb_height"))?.takeIf { it > 0 }
        return FileMeta(
            fileId = fileId,
            name = obj.string("name") ?: "file",
            size = (obj["size"] as? JsonPrimitive)?.longOrNull ?: 0,
            mime = obj.string("mime") ?: "application/octet-stream",
            width = width,
            height = height,
            thumbWidth = obj.int("thumb_width")?.takeIf { it > 0 },
            thumbHeight = obj.int("thumb_height")?.takeIf { it > 0 },
            duration = obj.int("duration"),
        )
    }

    private fun textPreview(content: String): String {
        systemNotice(content)?.let { return it }
        if (content.startsWith("{") && content.contains("file_id")) return sanitizedListPreview(content)
        return content
    }

    private fun systemNotice(obj: JsonObject): String? {
        val uid = obj.string("uid") ?: ""
        return when (obj.string("type")) {
            "member_joined" -> "$uid 加入了群聊"
            "member_left" -> "$uid 离开了群聊"
            "member_kicked" -> "$uid 移出了群成员"
            "group_renamed" -> "$uid 修改了群名称"
            "owner_transferred" -> "$uid 转让了群主"
            else -> null
        }
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 兼容整数与浮点（`duration` 可能是 3.6）。 */
    private fun JsonObject.int(key: String): Int? {
        val p: JsonElement = this[key] ?: return null
        val prim = p as? JsonPrimitive ?: return null
        return prim.longOrNull?.toInt() ?: prim.doubleOrNull?.roundToInt()
    }
}
