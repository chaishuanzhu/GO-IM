package com.goim.kmp.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class MessageDao {

    /** 最近 [limit] 条，时间**降序**（调用方翻转）。 */
    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp_ms DESC LIMIT :limit")
    abstract fun observeLatest(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE conversation_id = :conversationId " +
            "AND timestamp_ms < :beforeMs ORDER BY timestamp_ms DESC LIMIT :limit",
    )
    abstract suspend fun page(conversationId: String, beforeMs: Long, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    abstract suspend fun count(conversationId: String): Int

    @Query("SELECT MIN(timestamp_ms) FROM messages WHERE conversation_id = :conversationId")
    abstract suspend fun oldestTimestamp(conversationId: String): Long?

    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun byId(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE server_msg_id = :serverMsgId LIMIT 1")
    abstract suspend fun byServerMsgId(serverMsgId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE client_seq = :clientSeq AND is_outgoing = 1 LIMIT 1")
    abstract suspend fun outgoingByClientSeq(clientSeq: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE status = :status AND is_outgoing = 1 ORDER BY timestamp_ms")
    abstract suspend fun outgoingByStatus(status: Int): List<MessageEntity>

    @Upsert
    protected abstract suspend fun upsertRow(entity: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    protected abstract suspend fun deleteById(id: String)

    @Query(
        "UPDATE messages SET status = :status, server_msg_id = COALESCE(:serverMsgId, server_msg_id) " +
            "WHERE client_seq = :clientSeq AND is_outgoing = 1",
    )
    protected abstract suspend fun updateOutgoingStatus(clientSeq: Long, status: Int, serverMsgId: Long?)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    abstract suspend fun deleteConversationMessages(conversationId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId AND timestamp_ms > :timestampMs")
    abstract suspend fun countNewer(conversationId: String, timestampMs: Long): Int

    /** 本地文本消息模糊搜索（服务端搜索不可用时的兜底）。 */
    @Query(
        "SELECT * FROM messages WHERE msg_type = 1 AND status != 3 AND content LIKE '%' || :query || '%' " +
            "ORDER BY timestamp_ms DESC LIMIT :limit",
    )
    abstract suspend fun searchText(query: String, limit: Int): List<MessageEntity>

    /**
     * 幂等写入（与 iOS `LocalStore.upsertMessage` 同规则）：
     * 1. 有 server_msg_id 且已存在 → 覆盖该行（保留外发标记与 client_seq）；
     * 2. 外发且同 client_seq 的本地行存在 → 覆盖该行；
     * 3. 否则按主键 upsert。
     */
    @Transaction
    open suspend fun upsertMerged(entity: MessageEntity) {
        var record = entity
        val sid = entity.serverMsgId
        if (sid != null) {
            val existing = byServerMsgId(sid)
            if (existing != null) {
                record = record.copy(id = existing.id)
                if (existing.isOutgoing) {
                    record = record.copy(
                        isOutgoing = true,
                        clientSeq = if (existing.clientSeq != 0L) existing.clientSeq else record.clientSeq,
                    )
                }
                upsertRow(record)
                return
            }
        }
        if (entity.isOutgoing && entity.clientSeq != 0L) {
            val existing = outgoingByClientSeq(entity.clientSeq)
            if (existing != null) {
                upsertRow(record.copy(id = existing.id, serverMsgId = record.serverMsgId ?: existing.serverMsgId))
                return
            }
        }
        upsertRow(record)
    }

    /** ACK 带回的 server_msg_id 若已被一条入站副本占用，删掉副本、保留外发行。 */
    @Transaction
    open suspend fun markStatusMerged(clientSeq: Long, status: Int, serverMsgId: Long?) {
        if (serverMsgId != null) {
            val byServer = byServerMsgId(serverMsgId)
            val bySeq = outgoingByClientSeq(clientSeq)
            if (byServer != null && bySeq != null && byServer.id != bySeq.id) {
                deleteById(byServer.id)
            }
        }
        updateOutgoingStatus(clientSeq, status, serverMsgId)
    }
}

@Dao
abstract class ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY last_msg_at DESC")
    abstract fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    abstract suspend fun byId(id: String): ConversationEntity?

    @Upsert
    abstract suspend fun upsert(entity: ConversationEntity)

    @Query("UPDATE conversations SET unread_count = :count WHERE id = :id")
    abstract suspend fun setUnread(id: String, count: Int)

    @Query("UPDATE conversations SET title = :title WHERE id = :id AND title != :title")
    abstract suspend fun updateTitle(id: String, title: String)

    @Query("SELECT COALESCE(SUM(unread_count), 0) FROM conversations")
    abstract fun observeTotalUnread(): Flow<Int>

    @Query("DELETE FROM conversations WHERE id = :id")
    protected abstract suspend fun deleteRow(id: String)

    @Query("DELETE FROM messages WHERE conversation_id = :id")
    protected abstract suspend fun deleteMessages(id: String)

    /** 删除会话及其全部本地消息。 */
    @Transaction
    open suspend fun delete(id: String) {
        deleteMessages(id)
        deleteRow(id)
    }
}

@Dao
abstract class GroupDao {
    @Query("SELECT * FROM groups WHERE group_id = :groupId")
    abstract suspend fun byId(groupId: String): GroupEntity?

    @Query("SELECT * FROM groups ORDER BY name COLLATE NOCASE, group_id")
    abstract fun observeAll(): Flow<List<GroupEntity>>

    @Upsert
    abstract suspend fun upsert(entity: GroupEntity)

    @Upsert
    protected abstract suspend fun upsertAll(entities: List<GroupEntity>)

    @Query("DELETE FROM groups WHERE group_id = :groupId")
    protected abstract suspend fun deleteGroupRow(groupId: String)

    @Query("DELETE FROM group_members WHERE group_id = :groupId")
    protected abstract suspend fun deleteMembers(groupId: String)

    @Query("DELETE FROM groups WHERE group_id NOT IN (:keep)")
    protected abstract suspend fun deleteGroupsNotIn(keep: List<String>)

    @Query("DELETE FROM groups")
    protected abstract suspend fun deleteAllGroups()

    @Query("SELECT * FROM group_members WHERE group_id = :groupId")
    abstract suspend fun members(groupId: String): List<GroupMemberEntity>

    @Upsert
    protected abstract suspend fun upsertMembers(entities: List<GroupMemberEntity>)

    /** 以服务端「我加入的群」为准：已退出 / 解散的群从本地缓存移除。 */
    @Transaction
    open suspend fun replaceAll(entities: List<GroupEntity>) {
        if (entities.isEmpty()) deleteAllGroups() else deleteGroupsNotIn(entities.map { it.groupId })
        upsertAll(entities)
    }

    @Transaction
    open suspend fun replaceMembers(groupId: String, members: List<GroupMemberEntity>) {
        deleteMembers(groupId)
        upsertMembers(members)
    }

    @Transaction
    open suspend fun delete(groupId: String) {
        deleteMembers(groupId)
        deleteGroupRow(groupId)
    }
}

@Dao
abstract class FriendDao {
    @Query("SELECT * FROM friends")
    abstract fun observeFriends(): Flow<List<FriendEntity>>

    @Query("SELECT * FROM friend_requests WHERE to_uid = :toUid AND status = 'pending' ORDER BY created_at DESC")
    abstract fun observePending(toUid: String): Flow<List<FriendRequestEntity>>

    @Query("SELECT * FROM friend_requests WHERE to_uid = :toUid AND status = 'pending'")
    abstract suspend fun pending(toUid: String): List<FriendRequestEntity>

    @Query("DELETE FROM friends")
    protected abstract suspend fun deleteFriends()

    @Upsert
    abstract suspend fun upsertFriends(entities: List<FriendEntity>)

    @Query("DELETE FROM friends WHERE uid = :uid")
    abstract suspend fun deleteFriend(uid: String)

    @Query("DELETE FROM friend_requests WHERE to_uid = :toUid AND status = 'pending'")
    protected abstract suspend fun deletePending(toUid: String)

    @Upsert
    abstract suspend fun upsertRequests(entities: List<FriendRequestEntity>)

    @Query("DELETE FROM friend_requests WHERE from_uid = :fromUid AND to_uid = :toUid")
    abstract suspend fun deleteRequest(fromUid: String, toUid: String)

    @Transaction
    open suspend fun replace(friends: List<FriendEntity>, toUid: String, pending: List<FriendRequestEntity>) {
        deleteFriends()
        upsertFriends(friends)
        deletePending(toUid)
        upsertRequests(pending)
    }
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun set(entity: MetaEntity)
}
