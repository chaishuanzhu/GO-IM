package com.goim.kmp.repository

import com.goim.kmp.common.nowMillis
import com.goim.kmp.database.AppDatabase
import com.goim.kmp.database.ConversationEntity
import com.goim.kmp.database.toDomain
import com.goim.kmp.domain.ConversationRepository
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.Group
import com.goim.kmp.model.Message
import com.goim.kmp.model.MessagePreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class ConversationRepositoryImpl(database: AppDatabase) : ConversationRepository {
    private val dao = database.conversationDao()
    private val groups = database.groupDao()
    private val active = MutableStateFlow<String?>(null)

    override var activeConversationId: String?
        get() = active.value
        set(value) {
            active.value = value
        }

    override fun observeConversations(): Flow<List<Conversation>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeTotalUnread(): Flow<Int> = dao.observeTotalUnread()

    override suspend fun conversation(id: String): Conversation? = persist { dao.byId(id)?.toDomain() }

    override suspend fun upsertConversation(from: Message, title: String?, incrementUnread: Boolean) = persist {
        val existing = dao.byId(from.conversationId)
        val peer = when {
            from.chatType == ChatType.GROUP -> from.toUid
            from.isOutgoing -> from.toUid
            else -> from.fromUid
        }
        val resolvedTitle = when {
            !title.isNullOrEmpty() -> title
            from.chatType == ChatType.GROUP ->
                groups.byId(peer)?.name?.takeIf { it.isNotEmpty() } ?: existing?.title ?: peer
            else -> existing?.title ?: peer
        }
        // 补拉的旧历史不覆盖更新的预览（iOS 会被覆盖，这里修正）。
        val isNewest = existing == null || from.timestampMs >= existing.lastMsgAt
        val unread = (existing?.unreadCount ?: 0) + if (incrementUnread && !from.isOutgoing) 1 else 0
        dao.upsert(
            ConversationEntity(
                id = from.conversationId,
                chatType = from.chatType.value,
                title = resolvedTitle,
                peerOrGroupId = existing?.peerOrGroupId ?: peer,
                lastMsgPreview = if (isNewest) MessagePreview.listPreview(from) else existing!!.lastMsgPreview,
                lastMsgAt = if (isNewest) from.timestampMs else existing!!.lastMsgAt,
                unreadCount = unread,
            ),
        )
    }

    override suspend fun setUnread(conversationId: String, count: Int) = persist { dao.setUnread(conversationId, count) }

    override suspend fun deleteConversation(id: String) = persist { dao.delete(id) }

    override suspend fun ensureConversation(conversation: Conversation) = persist {
        val existing = dao.byId(conversation.id)
        if (existing == null) {
            dao.upsert(
                ConversationEntity(
                    id = conversation.id,
                    chatType = conversation.chatType.value,
                    title = conversation.title,
                    peerOrGroupId = conversation.peerOrGroupId,
                    lastMsgPreview = "",
                    lastMsgAt = nowMillis(),
                    unreadCount = 0,
                ),
            )
        } else if (conversation.title.isNotEmpty()) {
            dao.updateTitle(conversation.id, conversation.title)
        }
    }

    /** 把群名同步到已有群会话标题（对齐 iOS `syncGroupTitles`）。 */
    internal suspend fun syncGroupTitles(list: List<Group>) = persist {
        list.filter { it.name.isNotEmpty() }.forEach { dao.updateTitle(ConversationId.group(it.groupId), it.name) }
    }
}
