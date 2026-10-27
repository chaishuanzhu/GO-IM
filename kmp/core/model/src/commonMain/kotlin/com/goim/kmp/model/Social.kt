package com.goim.kmp.model

data class Group(
    val groupId: String,
    val name: String,
    val ownerUid: String,
    val memberCount: Int = 0,
) {
    val displayName: String get() = name.ifBlank { groupId }
}

data class GroupMember(
    val groupId: String,
    val uid: String,
    val role: String = "member",
)

data class Friend(
    val uid: String,
    val nickname: String = uid,
    val avatarUrl: String = "",
) {
    val displayName: String get() = nickname.ifBlank { uid }
}

/** `raw` 与 iOS `FriendRequestStatus` 存库取值一致。 */
enum class FriendRequestStatus(val raw: String) {
    PENDING("pending"),
    ACCEPTED("accepted"),
    REJECTED("rejected"),
    ;

    companion object {
        fun fromRaw(raw: String?): FriendRequestStatus = entries.firstOrNull { it.raw == raw } ?: PENDING
    }
}

data class FriendRequest(
    val fromUid: String,
    val toUid: String,
    val status: FriendRequestStatus = FriendRequestStatus.PENDING,
    val createdAt: Long = 0,
    val nickname: String? = null,
    val avatarUrl: String = "",
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: fromUid

    /** 请求的另一方；[nickname] / [avatarUrl] 描述的就是此人。 */
    fun peerUid(selfUid: String): String = if (fromUid == selfUid) toUid else fromUid
}

/** 搜索命中；带会话定位信息以便跳转。 */
data class SearchHit(
    val serverMsgId: Long,
    val conversationId: String,
    val chatType: ChatType,
    val peerOrGroupId: String,
    val fromUid: String,
    val content: String,
    val timestampMs: Long,
    /** 本地兜底搜索命中时的本地消息 ID（可能没有 serverMsgId）。 */
    val localMessageId: String? = null,
)
