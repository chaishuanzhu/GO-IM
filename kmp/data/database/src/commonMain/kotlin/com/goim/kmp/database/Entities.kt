package com.goim.kmp.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 列名与 iOS GRDB `messages` 表一致；枚举存 Int（数值与 iOS 相同）。 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["server_msg_id"], unique = true),
        Index(value = ["conversation_id"]),
        Index(value = ["timestamp_ms"]),
        Index(value = ["client_seq"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "server_msg_id") val serverMsgId: Long?,
    @ColumnInfo(name = "client_seq") val clientSeq: Long,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "from_uid") val fromUid: String,
    @ColumnInfo(name = "to_uid") val toUid: String,
    @ColumnInfo(name = "chat_type") val chatType: Int,
    @ColumnInfo(name = "msg_type") val msgType: Int,
    val content: String,
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,
    val status: Int,
    @ColumnInfo(name = "is_outgoing") val isOutgoing: Boolean,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "chat_type") val chatType: Int,
    val title: String,
    @ColumnInfo(name = "peer_or_group_id") val peerOrGroupId: String,
    @ColumnInfo(name = "last_msg_preview", defaultValue = "") val lastMsgPreview: String,
    @ColumnInfo(name = "last_msg_at", defaultValue = "0") val lastMsgAt: Long,
    @ColumnInfo(name = "unread_count", defaultValue = "0") val unreadCount: Int,
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey @ColumnInfo(name = "group_id") val groupId: String,
    val name: String,
    @ColumnInfo(name = "owner_uid") val ownerUid: String,
    @ColumnInfo(name = "member_count", defaultValue = "0") val memberCount: Int,
)

@Entity(tableName = "group_members", primaryKeys = ["group_id", "uid"])
data class GroupMemberEntity(
    @ColumnInfo(name = "group_id") val groupId: String,
    val uid: String,
    @ColumnInfo(defaultValue = "member") val role: String,
)

@Entity(tableName = "friends")
data class FriendEntity(
    @PrimaryKey val uid: String,
    val username: String,
)

@Entity(tableName = "friend_requests", primaryKeys = ["from_uid", "to_uid"])
data class FriendRequestEntity(
    @ColumnInfo(name = "from_uid") val fromUid: String,
    @ColumnInfo(name = "to_uid") val toUid: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val username: String?,
)

@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
