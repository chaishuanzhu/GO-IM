package com.goim.kmp.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.goim.kmp.common.DomainError
import com.goim.kmp.database.AppDatabase
import com.goim.kmp.domain.ConnectionRepository
import com.goim.kmp.domain.InboundEvent
import com.goim.kmp.domain.OutboundEnvelope
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.Message
import com.goim.kmp.model.MessageStatus
import com.goim.kmp.model.MsgType
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionFlowTest {
    private val me = User("alice", "alice", "jwt")
    private val db = Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    private class FakeConnection : ConnectionRepository {
        override val state = MutableStateFlow(ConnectionState.DISCONNECTED)
        val events = Channel<InboundEvent>(Channel.UNLIMITED)
        override val inboundEvents: Flow<InboundEvent> = events.receiveAsFlow()
        val sent = mutableListOf<OutboundEnvelope>()
        var online = true

        override suspend fun connect(user: User, transport: TransportKind) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun ensureConnected() = Unit
        override suspend fun send(envelope: OutboundEnvelope) {
            if (!online) throw DomainError.InvalidState("not connected")
            sent += envelope
        }
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(5_000) {
        while (!check()) yield()
    }

    @Test
    fun sendAckReceiveAndUnread() = runBlocking {
        val connection = FakeConnection()
        val messages = MessageRepositoryImpl(db, connection, this)
        val conversations = ConversationRepositoryImpl(db)
        val pumps = CoroutineScope(coroutineContext + Job())
        SessionCoordinator(me, connection, messages, conversations, pumps) {}.start()

        // 发送 → sending，ACK → sent 并带上 server id
        val sent = messages.sendText("bob", ChatType.SINGLE, "hi", me)
        assertEquals(MessageStatus.SENDING, sent.status)
        connection.events.send(InboundEvent.Ack(sent.clientSeq, 777))
        eventually { messages.message(sent.id)?.status == MessageStatus.SENT }
        assertEquals(777L, messages.message(sent.id)!!.serverMsgId)

        // 入站消息：非活跃会话累加未读，活跃会话不累加
        val inbound = Message(
            serverMsgId = 900, clientSeq = 1, conversationId = "dm:alice_bob", fromUid = "bob", toUid = "alice",
            chatType = ChatType.SINGLE, msgType = MsgType.Text, content = "yo", timestampMs = sent.timestampMs + 1,
            status = MessageStatus.SENT, isOutgoing = false,
        )
        connection.events.send(InboundEvent.MessageReceived(inbound))
        eventually { conversations.conversation("dm:alice_bob")?.unreadCount == 1 }
        assertEquals("yo", conversations.conversation("dm:alice_bob")!!.lastMessagePreview)

        conversations.activeConversationId = "dm:alice_bob"
        connection.events.send(InboundEvent.MessageReceived(inbound.copy(id = "x2", serverMsgId = 901, timestampMs = inbound.timestampMs + 1)))
        eventually { messages.countMessages("dm:alice_bob") == 3 }
        assertEquals(1, conversations.conversation("dm:alice_bob")!!.unreadCount)

        // 服务端未读数覆盖本地
        connection.events.send(InboundEvent.Unread(mapOf("bob" to 5)))
        eventually { conversations.conversation("dm:alice_bob")?.unreadCount == 5 }

        // 旧历史不覆盖最新预览
        connection.events.send(InboundEvent.MessageReceived(inbound.copy(id = "old", serverMsgId = 1, content = "old", timestampMs = 1)))
        eventually { messages.countMessages("dm:alice_bob") == 4 }
        assertEquals("yo", conversations.conversation("dm:alice_bob")!!.lastMessagePreview)

        val window = messages.observeMessages("dm:alice_bob", 2).first()
        assertEquals(2, window.size)
        assertTrue(window[0].timestampMs <= window[1].timestampMs)

        pumps.cancel()
        messages.cancelPendingAcks()
    }

    @Test
    fun offlineSendFailsAndRetryRecovers() = runBlocking {
        val connection = FakeConnection().apply { online = false }
        val messages = MessageRepositoryImpl(db, connection, this)

        val failed = messages.sendText("bob", ChatType.SINGLE, "hi", me)
        assertEquals(MessageStatus.FAILED, failed.status)
        assertEquals(MessageStatus.FAILED, messages.message(failed.id)!!.status)

        connection.online = true
        messages.retry(failed)
        assertEquals(MessageStatus.SENDING, messages.message(failed.id)!!.status)
        assertEquals(1, connection.sent.size)
        messages.markStatus(failed.clientSeq, MessageStatus.SENT, 5)
        assertEquals(MessageStatus.SENT, messages.message(failed.id)!!.status)
        messages.cancelPendingAcks()
    }

    @Test
    fun historyCompletesWithDeliveredCount() = runBlocking {
        val connection = FakeConnection()
        val messages = MessageRepositoryImpl(db, connection, this, historyTimeoutMillis = 2_000)
        val pending = async { messages.loadHistory("bob", null, 30, ChatType.SINGLE) }
        eventually { messages.isHistoryInFlight() }
        val request = connection.sent.single() as OutboundEnvelope.History
        assertEquals(30, request.limit)
        messages.completeHistory(12)
        assertEquals(12, pending.await())
        assertTrue(!messages.isHistoryInFlight())

        // 超时返回 0
        assertEquals(0, messages.loadHistory("bob", 5, 30, ChatType.SINGLE))
    }
}
