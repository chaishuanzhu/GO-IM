package com.goim.kmp.repository

import com.goim.kmp.common.DomainError
import com.goim.kmp.common.nowMillis
import com.goim.kmp.database.AppDatabase
import com.goim.kmp.database.toDomain
import com.goim.kmp.database.toEntity
import com.goim.kmp.domain.ConnectionRepository
import com.goim.kmp.domain.MessageRepository
import com.goim.kmp.domain.OutboundEnvelope
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConversationId
import com.goim.kmp.model.FileMeta
import com.goim.kmp.model.Message
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.MsgType
import com.goim.kmp.model.StickerRef
import com.goim.kmp.model.User
import com.goim.kmp.model.hasLocalAttachment
import com.goim.kmp.model.toContentJson
import com.goim.kmp.pipeline.OutboundAckTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withTimeoutOrNull

class MessageRepositoryImpl(
    database: AppDatabase,
    private val connection: ConnectionRepository,
    scope: CoroutineScope,
    private val historyTimeoutMillis: Long = 15_000,
) : MessageRepository {
    private val dao = database.messageDao()

    private val ackTracker = OutboundAckTracker(
        scope = scope,
        resend = { seq ->
            val message = dao.outgoingByClientSeq(seq)?.toDomain()
            message != null && runCatching { sendWire(message) }.isSuccess
        },
        onGiveUp = { seq -> markStatus(seq, MessageStatus.FAILED, null) },
    )

    /** 进行中的 CmdHistory 请求；同一时刻只有一个（新请求会先结束旧的）。 */
    private val historyWait = MutableStateFlow<CompletableDeferred<Int>?>(null)

    private val lastClientSeq = MutableStateFlow(0L)

    override fun observeMessages(conversationId: String, limit: Int): Flow<List<Message>> =
        dao.observeLatest(conversationId, limit)
            .conflate()
            .map { rows -> rows.asReversed().map { it.toDomain() } }

    override suspend fun countMessages(conversationId: String): Int = persist { dao.count(conversationId) }

    override suspend fun upsert(message: Message) = persist { dao.upsertMerged(message.toEntity()) }

    override suspend fun markStatus(clientSeq: Long, status: MessageStatus, serverMsgId: Long?) {
        if (status == MessageStatus.SENT || status == MessageStatus.FAILED) ackTracker.acknowledge(clientSeq)
        persist { dao.markStatusMerged(clientSeq, status.value, serverMsgId) }
    }

    override suspend fun sendText(to: String, chatType: ChatType, text: String, from: User): Message {
        val message = outgoing(to, chatType, MsgType.Text, text, from)
        upsert(message)
        return deliver(message)
    }

    override suspend fun enqueueOutgoingFile(to: String, chatType: ChatType, meta: FileMeta, from: User): Message {
        val message = outgoing(to, chatType, MsgType.fromMime(meta.mime), meta.toContentJson(), from)
        upsert(message)
        return message
    }

    override suspend fun deliverOutgoingFile(message: Message, meta: FileMeta): Message {
        val pending = message.copy(content = meta.toContentJson(), status = MessageStatus.SENDING)
        upsert(pending)
        return deliver(pending)
    }

    override suspend fun sendSticker(to: String, chatType: ChatType, sticker: StickerRef, from: User): Message {
        val message = outgoing(to, chatType, MsgType.Sticker, sticker.toContentJson(), from)
        upsert(message)
        return deliver(message)
    }

    /** 同一毫秒内连发多条（多图）时 clientSeq 仍需唯一递增。 */
    private fun outgoing(to: String, chatType: ChatType, type: MsgType, content: String, from: User): Message {
        val now = nowMillis()
        val seq = lastClientSeq.updateAndGet { last -> maxOf(now % 1_000_000_000_000L, last + 1) }
        return Message(
            clientSeq = seq,
            conversationId = ConversationId.of(from.uid, to, chatType),
            fromUid = from.uid,
            toUid = to,
            chatType = chatType,
            msgType = type,
            content = content,
            timestampMs = now,
            status = MessageStatus.SENDING,
            isOutgoing = true,
        )
    }

    override suspend fun retry(message: Message) {
        if (!message.isOutgoing) throw DomainError.InvalidState("only outgoing messages can be retried")
        val pending = message.copy(status = MessageStatus.SENDING)
        upsert(pending)
        deliver(pending)
    }

    override suspend fun messages(status: MessageStatus): List<Message> =
        persist { dao.outgoingByStatus(status.value).map { it.toDomain() } }

    override suspend fun message(id: String): Message? = persist { dao.byId(id)?.toDomain() }

    override suspend fun messageByServerId(serverMsgId: Long): Message? =
        persist { dao.byServerMsgId(serverMsgId)?.toDomain() }

    override suspend fun countNewerThan(conversationId: String, timestampMs: Long): Int =
        persist { dao.countNewer(conversationId, timestampMs) }

    override suspend fun loadHistory(peer: String, before: Long?, limit: Int, chatType: ChatType): Int {
        finishHistoryWait(0)
        val wait = CompletableDeferred<Int>()
        historyWait.value = wait
        try {
            connection.send(OutboundEnvelope.History(peer, before, limit, chatType))
        } catch (e: CancellationException) {
            finishHistoryWait(0)
            throw e
        } catch (_: Exception) {
            finishHistoryWait(0)
            return 0
        }
        return withTimeoutOrNull(historyTimeoutMillis) { wait.await() } ?: run {
            finishHistoryWait(0)
            0
        }
    }

    override fun completeHistory(delivered: Int) = finishHistoryWait(delivered)

    override fun isHistoryInFlight(): Boolean = historyWait.value != null

    override suspend fun markRead(peer: String, chatType: ChatType) {
        connection.send(OutboundEnvelope.ReadReceipt(peer, chatType))
    }

    suspend fun cancelPendingAcks() = ackTracker.cancelAll()

    /** 先挂 ACK 计时再发出（ACK 可能先于发送返回到达）；链路不可用则落为 FAILED 返回（不抛出）。 */
    private suspend fun deliver(message: Message): Message {
        ackTracker.track(message.clientSeq)
        return try {
            sendWire(message)
            message
        } catch (e: CancellationException) {
            ackTracker.acknowledge(message.clientSeq)
            throw e
        } catch (_: Exception) {
            ackTracker.acknowledge(message.clientSeq)
            val failed = message.copy(status = MessageStatus.FAILED)
            upsert(failed)
            failed
        }
    }

    private suspend fun sendWire(message: Message) {
        if (message.hasLocalAttachment) throw DomainError.InvalidState("附件尚未上传")
        when (message.msgType) {
            MsgType.Text -> connection.send(OutboundEnvelope.Chat(message))
            is MsgType.Unsupported -> throw DomainError.InvalidState(MsgType.UNSUPPORTED_PLACEHOLDER)
            else -> connection.send(OutboundEnvelope.File(message))
        }
    }

    private fun finishHistoryWait(delivered: Int) {
        historyWait.getAndUpdate { null }?.complete(delivered)
    }
}

internal suspend fun <T> persist(block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: DomainError) {
    throw e
} catch (e: Exception) {
    throw DomainError.Persistence(e.message ?: e::class.simpleName ?: "unknown", e)
}
