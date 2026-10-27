package com.goim.kmp.repository

import com.goim.kmp.common.DomainError
import com.goim.kmp.common.nowMillis
import com.goim.kmp.database.AppDatabase
import com.goim.kmp.database.GroupMemberEntity
import com.goim.kmp.database.toDomain
import com.goim.kmp.database.toEntity
import com.goim.kmp.domain.ConnectionRepository
import com.goim.kmp.domain.FriendRepository
import com.goim.kmp.domain.GroupRepository
import com.goim.kmp.domain.InboundEvent
import com.goim.kmp.domain.OutboundEnvelope
import com.goim.kmp.domain.SearchRepository
import com.goim.kmp.domain.SearchResult
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.Conversation
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.Friend
import com.goim.kmp.model.FriendRequest
import com.goim.kmp.model.FriendRequestStatus
import com.goim.kmp.model.Group
import com.goim.kmp.model.GroupMember
import com.goim.kmp.model.SearchHit
import com.goim.kmp.network.GoImUserApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

class GroupRepositoryImpl(
    private val api: GoImUserApi,
    database: AppDatabase,
    private val conversations: ConversationRepositoryImpl,
) : GroupRepository {
    private val dao = database.groupDao()

    override fun observeGroups(): Flow<List<Group>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun group(groupId: String): Group? = persist { dao.byId(groupId)?.toDomain() }

    override suspend fun refresh(): List<Group> {
        val groups = api.listGroups()
        persist { dao.replaceAll(groups.map { it.toEntity() }) }
        conversations.syncGroupTitles(groups)
        return groups
    }

    override suspend fun create(name: String, members: List<String>): Group {
        val group = api.createGroup(name, members)
        persist { dao.upsert(group.toEntity()) }
        conversations.ensureConversation(group.toConversation())
        return group
    }

    override suspend fun join(groupId: String): Group? {
        api.joinGroup(groupId)
        val group = runCatching { refresh() }.getOrNull()?.firstOrNull { it.groupId == groupId }
        conversations.ensureConversation(group?.toConversation() ?: Group(groupId, "", "").toConversation())
        return group
    }

    override suspend fun leave(groupId: String) {
        api.leaveGroup(groupId)
        persist { dao.delete(groupId) }
    }

    override suspend fun invite(groupId: String, uid: String) = api.inviteToGroup(groupId, uid)

    override suspend fun members(groupId: String): List<GroupMember> {
        val owner = persist { dao.byId(groupId)?.ownerUid }
        val members = api.groupMembers(groupId).map { uid ->
            GroupMember(groupId, uid, role = if (uid == owner) "owner" else "member")
        }
        persist { dao.replaceMembers(groupId, members.map { GroupMemberEntity(it.groupId, it.uid, it.role) }) }
        return members
    }

    private fun Group.toConversation() = Conversation(
        id = ConversationId.group(groupId),
        chatType = ChatType.GROUP,
        title = displayName,
        peerOrGroupId = groupId,
    )
}

/**
 * 好友：发请求 / 处理请求优先走长连接（服务端会实时通知对方），
 * 拿不到确认时回退 HTTP（结果权威）。
 */
class FriendRepositoryImpl(
    private val api: GoImUserApi,
    database: AppDatabase,
    private val connection: ConnectionRepository,
    private val selfUid: String,
    private val confirmTimeoutMillis: Long = 5_000,
    private val pollIntervalMillis: Long = 500,
) : FriendRepository {
    private val dao = database.friendDao()
    private val requestResults = MutableSharedFlow<InboundEvent.FriendRequestResult>(extraBufferCapacity = 8)

    override fun observeFriends(): Flow<List<Friend>> = dao.observeFriends().map { rows ->
        rows.map { it.toDomain() }.sortedWith(compareBy({ it.displayName.lowercase() }, { it.uid }))
    }

    override fun observeRequests(): Flow<List<FriendRequest>> =
        dao.observePending(selfUid).map { rows -> rows.map { it.toDomain() } }

    override fun observeRequestHistory(): Flow<List<FriendRequest>> =
        dao.observeHistory(selfUid).map { rows -> rows.map { it.toDomain() } }

    override suspend fun refresh() {
        val snapshot = api.friendList()
        val friends = snapshot.friends.map { it.toEntity() }
        val pending = snapshot.pending.map { it.toEntity() }
        persist {
            val history = snapshot.history
            if (history != null) {
                dao.replaceWithHistory(friends, selfUid, history.map { it.toEntity() }, pending)
            } else {
                dao.replace(friends = friends, toUid = selfUid, pending = pending)
            }
        }
    }

    override suspend fun sendRequest(to: String) {
        deliverRequest(to)
        val record = FriendRequest(fromUid = selfUid, toUid = to.trim(), createdAt = nowMillis())
        persist { dao.upsertRequests(listOf(record.toEntity())) }
    }

    private suspend fun deliverRequest(to: String) {
        val target = to.trim()
        if (target.isEmpty()) throw DomainError.InvalidState("请输入用户 ID")
        if (target == selfUid) throw DomainError.InvalidState("不能添加自己为好友")

        if (connection.state.value != ConnectionState.CONNECTED) return api.sendFriendRequest(target)

        var sent = false
        val echo = coroutineScope {
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(confirmTimeoutMillis) { requestResults.first { it.peerUid == target } }
            }
            try {
                connection.send(OutboundEnvelope.FriendRequest(target))
                sent = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                waiter.cancel()
            }
            if (sent) waiter.await() else null
        }
        if (!sent) return api.sendFriendRequest(target)
        if (echo != null) {
            echo.error?.let { throw DomainError.Server(409, it) }
            return
        }
        // 未收到回执（服务端好友功能不可用时会静默丢弃）：用 HTTP 求证；409 说明请求其实已存在。
        try {
            api.sendFriendRequest(target)
        } catch (e: DomainError.Server) {
            if (e.code != 409) throw e
        }
    }

    override suspend fun respond(fromUid: String, accept: Boolean) {
        val viaSocket = connection.state.value == ConnectionState.CONNECTED &&
            runCatching { connection.send(OutboundEnvelope.FriendResponse(fromUid, accept)) }.isSuccess
        if (viaSocket) {
            val deadline = confirmTimeoutMillis / pollIntervalMillis
            repeat(deadline.toInt().coerceAtLeast(1)) {
                delay(pollIntervalMillis)
                // 刷新成功才算服务端确认；刷新失败时 pending 仍是本地旧值，不能据此判断
                if (runCatching { refresh() }.isSuccess &&
                    persist { dao.pending(selfUid) }.none { it.fromUid == fromUid }
                ) {
                    return
                }
            }
        }
        try {
            api.respondFriendRequest(fromUid, accept)
        } catch (e: DomainError.Server) {
            // 长连接已处理过该请求时 HTTP 返回 404（no pending friend request）
            if (!viaSocket || e.code != 404) throw e
        }
        applyResponse(fromUid, accept)
        runCatching { refresh() }
    }

    private suspend fun applyResponse(fromUid: String, accept: Boolean) {
        persist {
            val status = if (accept) FriendRequestStatus.ACCEPTED else FriendRequestStatus.REJECTED
            dao.setRequestStatus(fromUid, selfUid, status.raw)
            if (accept) dao.upsertFriends(listOf(Friend(fromUid).toEntity()))
        }
    }

    override suspend fun remove(uid: String) {
        try {
            api.removeFriend(uid)
        } catch (e: DomainError.Server) {
            // 404：服务端已无该好友关系（对方先删了），本地照常移除
            if (e.code != 404) throw e
        }
        persist { dao.deleteFriend(uid) }
        runCatching { refresh() }
    }

    /** 对方接受请求或删除了我：先改本地让列表立即变化，再全量刷新。 */
    internal suspend fun onResponded(event: InboundEvent.FriendResponded) {
        when {
            event.removed -> persist { dao.deleteFriend(event.peerUid) }
            event.accepted -> persist { dao.upsertFriends(listOf(Friend(event.peerUid).toEntity())) }
        }
        runCatching { refresh() }
    }

    /** 他人请求先落库（角标即时出现），再全量刷新。 */
    internal suspend fun onRequestReceived(event: InboundEvent.FriendRequestReceived) {
        persist {
            dao.upsertRequests(
                listOf(
                    FriendRequest(
                        fromUid = event.fromUid,
                        toUid = selfUid,
                        createdAt = event.timestampMs,
                        nickname = event.nickname?.takeIf { it != event.fromUid },
                    ).toEntity(),
                ),
            )
        }
        runCatching { refresh() }
    }

    internal fun onRequestResult(event: InboundEvent.FriendRequestResult) {
        requestResults.tryEmit(event)
    }
}

class SearchRepositoryImpl(
    private val api: GoImUserApi,
    database: AppDatabase,
    private val selfUid: String,
) : SearchRepository {
    private val messages = database.messageDao()

    override suspend fun search(query: String, limit: Int): SearchResult {
        val q = query.trim()
        if (q.isEmpty()) return SearchResult(emptyList())
        return try {
            val remote = api.search(q, limit)
            // 网关未配置消息库时返回 200 + 空结果，与"无命中"无法区分：空结果时再查本机。
            if (remote.isNotEmpty()) SearchResult(remote) else localSearch(q, limit).takeIf { it.hits.isNotEmpty() } ?: SearchResult(emptyList())
        } catch (e: DomainError.Server) {
            if (e.code == 400 || e.code == 401 || e.code == 403 || e.code == 429) throw e
            localSearch(q, limit)
        } catch (_: DomainError.Network) {
            localSearch(q, limit)
        }
    }

    private suspend fun localSearch(query: String, limit: Int): SearchResult {
        val hits = persist { messages.searchText(query, limit) }.map { row ->
            val m = row.toDomain()
            val peer = when {
                m.chatType == ChatType.GROUP -> m.toUid
                m.isOutgoing || m.fromUid == selfUid -> m.toUid
                else -> m.fromUid
            }
            SearchHit(
                serverMsgId = m.serverMsgId ?: 0,
                conversationId = m.conversationId,
                chatType = m.chatType,
                peerOrGroupId = peer,
                fromUid = m.fromUid,
                content = m.content,
                timestampMs = m.timestampMs,
                localMessageId = m.id,
            )
        }
        return SearchResult(hits, isLocalFallback = true)
    }
}
