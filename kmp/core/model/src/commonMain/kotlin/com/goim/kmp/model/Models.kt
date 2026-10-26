package com.goim.kmp.model

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** `raw` 与 iOS `device.json` 中的 `preferredTransport` 取值一致。 */
enum class TransportKind(val raw: String, val title: String) {
    WEB_SOCKET("webSocket", "WebSocket"),
    TCP("tcp", "TCP"),
    ;

    companion object {
        fun fromRaw(raw: String?): TransportKind = entries.firstOrNull { it.raw == raw } ?: WEB_SOCKET
    }
}

/** 外观：跟随系统 / 浅色 / 深色（device.json `appearance`）。 */
enum class Appearance(val raw: String, val title: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    ;

    companion object {
        fun fromRaw(raw: String?): Appearance = entries.firstOrNull { it.raw == raw } ?: SYSTEM
    }
}

enum class ChatType(val value: Int) {
    SINGLE(1),
    GROUP(2),
    ;

    companion object {
        fun from(value: Int): ChatType = if (value == GROUP.value) GROUP else SINGLE
    }
}

sealed class MsgType(val value: Int) {
    data object Text : MsgType(1)
    data object Image : MsgType(2)
    data object Voice : MsgType(3)
    data object Video : MsgType(4)
    data object File : MsgType(5)
    data object Sticker : MsgType(6)

    /** 本客户端不认识的线上取值；保留原值以便原样回传。 */
    data class Unsupported(val code: Int) : MsgType(code)

    companion object {
        const val UNSUPPORTED_PLACEHOLDER = "暂不支持的消息类型，请升级到最新版本"

        fun from(value: Int): MsgType = when (value) {
            1 -> Text
            2 -> Image
            3 -> Voice
            4 -> Video
            5 -> File
            6 -> Sticker
            else -> Unsupported(value)
        }

        /** 上传 MIME → 协议 msg_type（docs/07 §10.2）。音频文件按普通文件发送，语音只来自录音。 */
        fun fromMime(mime: String): MsgType {
            val m = mime.lowercase()
            return when {
                m.startsWith("image/") -> Image
                m.startsWith("video/") -> Video
                else -> File
            }
        }
    }
}

/** 数值与 iOS `MessageStatus` 一致。 */
enum class MessageStatus(val value: Int) {
    SENDING(0),
    SENT(1),
    FAILED(2),
    RECALLED(3),
    ;

    companion object {
        fun from(value: Int): MessageStatus = entries.firstOrNull { it.value == value } ?: SENT
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,

    /** Token 被拒：停止重连并回到登录页。 */
    AUTH_EXPIRED,
}

data class User(
    val uid: String,
    val nickname: String,
    val token: String,
    val email: String = "",
    /** 绝对地址；未设置头像时为空。 */
    val avatarUrl: String = "",
) {
    val displayName: String get() = nickname.ifBlank { uid }
}

/** 邮箱验证码用途，取值与网关 `purpose` 一致。 */
enum class EmailCodePurpose(val raw: String) {
    REGISTER("register"),
    RESET_PASSWORD("reset_password"),
    BIND_EMAIL("bind_email"),
}

/** 公开资料（昵称 / 头像）；[email] 只有自己的资料才有。 */
data class UserProfile(
    val uid: String,
    val nickname: String,
    val avatarUrl: String = "",
    val email: String = "",
) {
    val displayName: String get() = nickname.ifBlank { uid }
}

data class Message(
    val id: String = newMessageId(),
    val serverMsgId: Long? = null,
    val clientSeq: Long,
    val conversationId: String,
    val fromUid: String,
    val toUid: String,
    val chatType: ChatType,
    val msgType: MsgType,
    val content: String,
    val timestampMs: Long,
    val status: MessageStatus,
    val isOutgoing: Boolean,
)

@OptIn(ExperimentalUuidApi::class)
fun newMessageId(): String = Uuid.random().toString().uppercase()

data class Conversation(
    val id: String,
    val chatType: ChatType,
    val title: String,
    val peerOrGroupId: String,
    val lastMessagePreview: String = "",
    val lastMessageAt: Long = 0,
    val unreadCount: Int = 0,
)

data class FileMeta(
    val fileId: String,
    val name: String,
    val size: Long,
    val mime: String,
    val width: Int? = null,
    val height: Int? = null,
    val thumbWidth: Int? = null,
    val thumbHeight: Int? = null,
    /** 秒；语音 / 视频使用。 */
    val duration: Int? = null,
)

/** 会话 ID 规则与 iOS `ConversationID` 相同。 */
object ConversationId {
    fun dm(uidA: String, uidB: String): String {
        val (a, b) = listOf(uidA, uidB).sorted()
        return "dm:${a}_$b"
    }

    fun group(groupId: String): String = "group:$groupId"

    fun of(selfUid: String, peerOrGroupId: String, chatType: ChatType): String =
        if (chatType == ChatType.GROUP) group(peerOrGroupId) else dm(selfUid, peerOrGroupId)
}
