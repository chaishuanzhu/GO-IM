package com.goim.kmp.network

import com.goim.kmp.model.AdminConversation
import com.goim.kmp.model.AdminMessage
import com.goim.kmp.model.AdminStats
import com.goim.kmp.model.AdminUser
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.MsgType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

@Serializable
internal data class AdminMemoryDto(
    @SerialName("alloc_mb") val allocMb: Int = 0,
    val goroutines: Int = 0,
)

@Serializable
internal data class AdminStatsDto(
    val status: String = "",
    @SerialName("online_users") val onlineUsers: Int = 0,
    @SerialName("total_users") val totalUsers: Int = 0,
    @SerialName("total_messages") val totalMessages: Int = 0,
    val dependencies: Map<String, String>? = null,
    val memory: AdminMemoryDto? = null,
) {
    fun toDomain() = AdminStats(
        healthy = status == "ok",
        onlineUsers = onlineUsers,
        totalUsers = totalUsers,
        totalMessages = totalMessages,
        dependencies = dependencies.orEmpty().entries.sortedBy { it.key }.associate { it.key to it.value },
        allocMb = memory?.allocMb ?: 0,
        goroutines = memory?.goroutines ?: 0,
    )
}

@Serializable
internal data class AdminUserDto(
    val uid: String,
    val nickname: String = "",
    val email: String? = null,
    val role: String = "user",
    @SerialName("is_disabled") val isDisabled: Boolean = false,
    @SerialName("created_at") val createdAt: Long = 0,
) {
    fun toDomain() = AdminUser(uid, nickname, email.orEmpty(), role == "admin", isDisabled, createdAt)
}

@Serializable
internal data class AdminUsersDto(
    val users: List<AdminUserDto>? = null,
    val total: Int = 0,
)

/** `proto.Message` 的 encoding/json 输出：字段均 omitempty；撤回的消息 content 为 `{"recalled":true}`。 */
@Serializable
internal data class AdminMessageDto(
    @SerialName("msg_id") val msgId: JsonElement? = null,
    val from: String = "",
    val to: String = "",
    @SerialName("chat_type") val chatType: Int = ChatType.SINGLE.value,
    @SerialName("msg_type") val msgType: Int = MsgType.Text.value,
    val content: String = "",
    val timestamp: Long = 0,
) {
    fun toDomain() = AdminMessage(
        msgId = (msgId as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() } ?: 0,
        fromUid = from,
        toUid = to,
        chatType = ChatType.from(chatType),
        msgType = MsgType.from(msgType),
        content = content,
        timestampMs = timestamp,
        recalled = content == RECALLED_CONTENT,
    )

    private companion object {
        const val RECALLED_CONTENT = """{"recalled":true}"""
    }
}

@Serializable
internal data class AdminMessagesDto(val messages: List<AdminMessageDto>? = null)

@Serializable
internal data class AdminConversationDto(
    val peer: String = "",
    @SerialName("chat_type") val chatType: Int = ChatType.SINGLE.value,
    val title: String = "",
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("last_message") val lastMessage: AdminMessageDto? = null,
) {
    fun toDomain() = AdminConversation(
        peer = peer,
        chatType = ChatType.from(chatType),
        title = title,
        messageCount = messageCount,
        lastMessage = (lastMessage ?: AdminMessageDto()).toDomain(),
    )
}

@Serializable
internal data class AdminConversationsDto(val conversations: List<AdminConversationDto>? = null)

@Serializable
internal data class AdminSenderDto(
    val nickname: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
internal data class AdminChatDto(
    val messages: List<AdminMessageDto>? = null,
    val users: Map<String, AdminSenderDto>? = null,
)
