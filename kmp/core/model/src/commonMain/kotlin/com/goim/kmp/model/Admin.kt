package com.goim.kmp.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64

/**
 * 是否管理员：取 JWT 的 `role` 声明（网关 `adminAuth` 也只认它）。
 * 角色在登录时写入 token，被提升或撤销后需重新登录才会变化。
 */
val User.isAdmin: Boolean get() = JwtClaims.role(token) == "admin"

internal object JwtClaims {
    private val json = Json { ignoreUnknownKeys = true }
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    /** 不校验签名，只用于决定界面是否展示管理入口；权限由服务端判定。 */
    fun role(token: String): String? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return null
        val obj = json.parseToJsonElement(base64.decode(payload).decodeToString()) as? JsonObject
        (obj?.get("role") as? JsonPrimitive)?.content
    }.getOrNull()
}

/** `GET /admin/stats`。 */
data class AdminStats(
    val healthy: Boolean,
    val onlineUsers: Int,
    val totalUsers: Int,
    val totalMessages: Int,
    /** 依赖名 → `ok` 或错误描述。 */
    val dependencies: Map<String, String>,
    val allocMb: Int,
    val goroutines: Int,
)

data class AdminUser(
    val uid: String,
    val nickname: String,
    val email: String,
    val isAdmin: Boolean,
    val isDisabled: Boolean,
    val createdAtMs: Long,
) {
    val displayName: String get() = nickname.ifBlank { uid }
}

data class AdminUserPage(val users: List<AdminUser>, val total: Int)

/** 全站消息浏览的一条；[recalled] 时服务端已把内容替换掉。 */
data class AdminMessage(
    val msgId: Long,
    val fromUid: String,
    val toUid: String,
    val chatType: ChatType,
    val msgType: MsgType,
    val content: String,
    val timestampMs: Long,
    val recalled: Boolean,
) {
    val preview: String get() = if (recalled) "[已撤回]" else MessagePreview.preview(msgType, content)
}

/** 管理后台里某个用户的一个会话：[peer] 单聊为对方 UID，群聊为群 ID。 */
data class AdminConversation(
    val peer: String,
    val chatType: ChatType,
    /** 对方昵称 / 群名，服务端查不到时为空。 */
    val title: String,
    val messageCount: Int,
    val lastMessage: AdminMessage,
) {
    val displayTitle: String get() = title.ifBlank { peer }
}

/** 消息发送者资料；[avatarUrl] 为绝对地址，未设置时为空。 */
data class AdminSender(val nickname: String, val avatarUrl: String)

/** 某个会话的一页消息（从新到旧）及本页发送者资料。 */
data class AdminChatPage(
    val messages: List<AdminMessage>,
    val senders: Map<String, AdminSender>,
    /** 群名或对方昵称，服务端查不到时为空。 */
    val title: String = "",
)
