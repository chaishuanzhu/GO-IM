package com.goim.kmp.repository

import co.touchlab.kermit.Logger
import com.goim.kmp.domain.ConnectionRepository
import com.goim.kmp.domain.ConversationRepository
import com.goim.kmp.domain.FileRepository
import com.goim.kmp.domain.GroupRepository
import com.goim.kmp.domain.InboundEvent
import com.goim.kmp.domain.MessageRepository
import com.goim.kmp.domain.OutboundEnvelope
import com.goim.kmp.domain.ProfileRepository
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.MessagePreview
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.User
import com.goim.kmp.model.hasLocalAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

enum class SessionEndReason { LOGOUT, KICKED, AUTH_EXPIRED }

/** 会话级事件泵（对齐 iOS `CompositionRoot.pumpInboundEvents / pumpConnectionState`）。 */
class SessionCoordinator(
    private val user: User,
    private val connection: ConnectionRepository,
    private val messages: MessageRepository,
    private val conversations: ConversationRepository,
    private val scope: CoroutineScope,
    private val groups: GroupRepository? = null,
    private val friends: FriendRepositoryImpl? = null,
    private val files: FileRepository? = null,
    private val profiles: ProfileRepository? = null,
    private val onEnded: (SessionEndReason) -> Unit,
) {
    private val log = Logger.withTag("Session")
    private var catchUpJob: Job? = null
    private val groupRefresh = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch { connection.inboundEvents.collect { safely { handle(it) } } }
        groups?.let { repo -> scope.launch { for (tick in groupRefresh) safely { repo.refresh() } } }
        scope.launch {
            connection.state.collect { state ->
                when (state) {
                    ConnectionState.CONNECTED -> {
                        catchUpJob?.cancel()
                        catchUpJob = scope.launch { catchUp() }
                    }
                    ConnectionState.AUTH_EXPIRED -> onEnded(SessionEndReason.AUTH_EXPIRED)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun handle(event: InboundEvent) {
        when (event) {
            is InboundEvent.MessageReceived -> {
                val msg = event.message
                messages.upsert(msg)
                val incrementUnread = !msg.isOutgoing &&
                    !messages.isHistoryInFlight() &&
                    conversations.activeConversationId != msg.conversationId
                conversations.upsertConversation(msg, title = null, incrementUnread = incrementUnread)
                if (msg.chatType == ChatType.GROUP && groups != null &&
                    (MessagePreview.systemNotice(msg.content) != null || groups.group(msg.toUid) == null)
                ) {
                    groupRefresh.trySend(Unit)
                }
            }
            is InboundEvent.Ack -> messages.markStatus(event.seq, MessageStatus.SENT, event.msgId.takeIf { it != 0L })
            is InboundEvent.HistoryFinished -> messages.completeHistory(event.delivered)
            InboundEvent.Kick -> onEnded(SessionEndReason.KICKED)
            is InboundEvent.Unread -> event.counts.forEach { (key, count) ->
                conversations.setUnread(conversationIdForUnreadKey(key), count)
            }
            is InboundEvent.FriendRequestReceived -> friends?.onRequestReceived(event)
            is InboundEvent.FriendRequestResult -> friends?.onRequestResult(event)
            is InboundEvent.FriendResponded -> friends?.onResponded(event)
        }
    }

    /** 重连后补齐：未读数、当前会话最近 50 条、重发 sending（CmdOffline 已在建连时发出）。 */
    private suspend fun catchUp() {
        safely { connection.send(OutboundEnvelope.UnreadCount) }
        conversations.activeConversationId?.let { activeId ->
            safely {
                conversations.conversation(activeId)?.let { conv ->
                    messages.loadHistory(conv.peerOrGroupId, before = null, limit = 50, chatType = conv.chatType)
                }
            }
        }
        safely {
            messages.messages(MessageStatus.SENDING).forEach { pending ->
                if (!pending.hasLocalAttachment) {
                    messages.retry(pending)
                    return@forEach
                }
                // 附件未上传完：仍在上传的交给用例收尾；进程重启遗留的标记失败，由气泡重试重新上传
                val localId = MessagePreview.decodeFileMeta(pending.content)?.fileId
                if (localId == null || files?.isUploading(localId) != true) {
                    messages.upsert(pending.copy(status = MessageStatus.FAILED))
                }
            }
        }
        groupRefresh.trySend(Unit)
        friends?.let { safely { it.refresh() } }
        profiles?.let { safely { it.refreshMe() } }
    }

    private fun conversationIdForUnreadKey(key: String): String = when {
        key.startsWith("group:") -> key
        key.startsWith("g_") -> ConversationId.group(key)
        else -> ConversationId.dm(user.uid, key)
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "session pump error" }
        }
    }
}
