package com.goim.kmp.repository

import com.goim.kmp.common.DomainError
import com.goim.kmp.domain.InboundEvent
import com.goim.kmp.domain.ServerSettingsRepository
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User
import com.goim.kmp.pipeline.LengthFrame
import com.goim.kmp.pipeline.LengthFrameDecoder
import com.goim.kmp.proto.Cmd
import com.goim.kmp.proto.MessageCodec
import com.goim.kmp.transport.IMEndpoint
import com.goim.kmp.transport.IMTransport
import com.goim.kmp.transport.TransportClosedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import im.Message as WireMessage

class ConnectionRepositoryImplTest {
    private val user = User("alice", "alice", "jwt")

    private class FakeSettings(kind: TransportKind, override val deviceId: String = "") : ServerSettingsRepository {
        override val apiBaseUrl: StateFlow<String> = MutableStateFlow("http://localhost:8080")
        override val preferredTransport = MutableStateFlow(kind)
        override val appearance = MutableStateFlow(com.goim.kmp.model.Appearance.SYSTEM)
        override fun setApiBaseUrl(url: String) = Unit
        override fun setAppearance(appearance: com.goim.kmp.model.Appearance) = Unit
        override fun setPreferredTransport(kind: TransportKind) {
            preferredTransport.value = kind
        }
    }

    private class FakeTransport(
        override val kind: TransportKind,
        private val openError: Exception? = null,
        private val acceptLogin: Boolean = true,
        var respondPong: Boolean = true,
    ) : IMTransport {
        val sent = mutableListOf<WireMessage>()
        private val inbound = Channel<ByteArray>(Channel.UNLIMITED)
        private val decoder = LengthFrameDecoder()
        var closed = false

        override suspend fun open() {
            openError?.let { throw it }
        }

        override fun incoming(): Flow<ByteArray> = flow {
            for (chunk in inbound) emit(chunk)
            throw TransportClosedException("closed")
        }

        override suspend fun send(bytes: ByteArray) {
            val payloads = if (kind == TransportKind.TCP) decoder.feed(bytes) else listOf(bytes)
            payloads.map(MessageCodec::decode).forEach { msg ->
                sent += msg
                when {
                    msg.cmd == Cmd.LOGIN && acceptLogin -> push(WireMessage(cmd = Cmd.LOGIN_RESP))
                    msg.cmd == Cmd.HEARTBEAT && respondPong -> push(WireMessage(cmd = Cmd.HEARTBEAT))
                }
            }
        }

        override suspend fun close() {
            closed = true
            inbound.close()
        }

        fun push(msg: WireMessage) {
            val bytes = MessageCodec.encode(msg)
            inbound.trySend(if (kind == TransportKind.TCP) LengthFrame.encode(bytes) else bytes)
        }

        fun drop() = inbound.close()
    }

    private class Harness(
        scope: TestScope,
        kind: TransportKind,
        validate: Boolean? = null,
        deviceId: String = "",
        platform: String = "",
        val makeTransport: (TransportKind) -> FakeTransport = { FakeTransport(it) },
    ) {
        val transports = mutableListOf<FakeTransport>()
        val endpoints = mutableListOf<IMEndpoint>()
        val repo = ConnectionRepositoryImpl(
            settings = FakeSettings(kind, deviceId),
            scope = CoroutineScope(scope.backgroundScope.coroutineContext),
            transportFactory = { k, e: IMEndpoint ->
                endpoints += e
                makeTransport(k).also { transports += it }
            },
            validateToken = { validate },
            platform = platform,
            serial = StandardTestDispatcher(scope.testScheduler),
        )
    }

    @Test
    fun tcpLoginCarriesDeviceIdentity() = runTest {
        val h = Harness(this, TransportKind.TCP, deviceId = "dev-1", platform = "ios")
        h.repo.connect(user, TransportKind.TCP)
        val login = h.transports.single().sent.first()
        assertEquals(Cmd.LOGIN, login.cmd)
        assertEquals("""{"token":"jwt","device_id":"dev-1","platform":"ios"}""", login.content)
        assertEquals("dev-1", h.endpoints.single().deviceId)
        assertEquals("ios", h.endpoints.single().platform)
    }

    @Test
    fun kickStopsReconnect() = runTest {
        val h = Harness(this, TransportKind.WEB_SOCKET)
        h.repo.connect(user, TransportKind.WEB_SOCKET)
        h.transports[0].push(WireMessage(cmd = Cmd.KICK))
        h.transports[0].drop()
        runCurrent()
        assertEquals(InboundEvent.Kick, h.repo.inboundEvents.first())
        advanceTimeBy(60_000)
        assertEquals(1, h.transports.size)
    }

    @Test
    fun webSocketConnectSendsOffline() = runTest {
        val h = Harness(this, TransportKind.WEB_SOCKET)
        h.repo.connect(user, TransportKind.WEB_SOCKET)
        assertEquals(ConnectionState.CONNECTED, h.repo.state.value)
        assertEquals(listOf(Cmd.OFFLINE), h.transports.single().sent.map { it.cmd })
    }

    @Test
    fun tcpLogsInBeforeOffline() = runTest {
        val h = Harness(this, TransportKind.TCP)
        h.repo.connect(user, TransportKind.TCP)
        assertEquals(ConnectionState.CONNECTED, h.repo.state.value)
        val sent = h.transports.single().sent
        assertEquals(listOf(Cmd.LOGIN, Cmd.OFFLINE), sent.map { it.cmd })
        assertEquals("jwt", sent[0].content)
    }

    @Test
    fun tcpMissingLoginRespIsAuthExpired() = runTest {
        val h = Harness(this, TransportKind.TCP, makeTransport = { FakeTransport(it, acceptLogin = false) })
        assertFailsWith<DomainError.NotAuthenticated> { h.repo.connect(user, TransportKind.TCP) }
        assertEquals(ConnectionState.AUTH_EXPIRED, h.repo.state.value)
        advanceTimeBy(60_000)
        assertEquals(1, h.transports.size)
    }

    @Test
    fun handshake401IsAuthExpired() = runTest {
        val h = Harness(this, TransportKind.WEB_SOCKET, makeTransport = {
            FakeTransport(it, openError = IllegalStateException("Expected status code 101 but was 401"))
        })
        assertFailsWith<DomainError.NotAuthenticated> { h.repo.connect(user, TransportKind.WEB_SOCKET) }
        assertEquals(ConnectionState.AUTH_EXPIRED, h.repo.state.value)
    }

    @Test
    fun opaqueHandshakeFailureProbesToken() = runTest {
        val h = Harness(this, TransportKind.WEB_SOCKET, validate = false, makeTransport = {
            FakeTransport(it, openError = IllegalStateException("handshake failed"))
        })
        assertFailsWith<DomainError.NotAuthenticated> { h.repo.connect(user, TransportKind.WEB_SOCKET) }
        assertEquals(ConnectionState.AUTH_EXPIRED, h.repo.state.value)
    }

    @Test
    fun networkFailureSchedulesBackoffReconnect() = runTest {
        var fail = true
        val h = Harness(this, TransportKind.WEB_SOCKET, makeTransport = {
            FakeTransport(it, openError = if (fail) IllegalStateException("Connection refused") else null)
        })
        assertFailsWith<IllegalStateException> { h.repo.connect(user, TransportKind.WEB_SOCKET) }
        assertEquals(ConnectionState.RECONNECTING, h.repo.state.value)

        fail = false
        advanceTimeBy(1_300)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, h.repo.state.value)
        assertEquals(2, h.transports.size)
    }

    @Test
    fun droppedLinkReconnectsAndInboundFlows() = runTest {
        val h = Harness(this, TransportKind.WEB_SOCKET)
        h.repo.connect(user, TransportKind.WEB_SOCKET)

        h.transports[0].push(WireMessage(cmd = Cmd.ACK, seq = 3, msg_id = 9))
        runCurrent()
        assertEquals(InboundEvent.Ack(3, 9), h.repo.inboundEvents.first())

        h.transports[0].drop()
        runCurrent()
        assertEquals(ConnectionState.RECONNECTING, h.repo.state.value)
        advanceTimeBy(1_300)
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, h.repo.state.value)
        assertEquals(2, h.transports.size)
        assertTrue(h.transports[0].closed)
    }

    @Test
    fun threeMissedHeartbeatsReconnect() = runTest {
        val h = Harness(this, TransportKind.WEB_SOCKET, makeTransport = { FakeTransport(it, respondPong = false) })
        h.repo.connect(user, TransportKind.WEB_SOCKET)

        advanceTimeBy(25_000 * 3 + 100)
        runCurrent()
        assertEquals(3, h.transports[0].sent.count { it.cmd == Cmd.HEARTBEAT })
        advanceTimeBy(25_000)
        runCurrent()
        assertTrue(h.transports[0].closed)
        assertTrue(h.repo.state.value == ConnectionState.RECONNECTING)
    }

    @Test
    fun disconnectStopsReconnect() = runTest {
        val h = Harness(this, TransportKind.WEB_SOCKET)
        h.repo.connect(user, TransportKind.WEB_SOCKET)
        h.repo.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, h.repo.state.value)
        advanceTimeBy(60_000)
        assertEquals(1, h.transports.size)
        assertFailsWith<DomainError.InvalidState> {
            h.repo.send(com.goim.kmp.domain.OutboundEnvelope.UnreadCount)
        }
    }
}
