package com.goim.kmp.repository

import co.touchlab.kermit.Logger
import com.goim.kmp.common.DomainError
import com.goim.kmp.common.nowMillis
import com.goim.kmp.domain.ConnectionRepository
import com.goim.kmp.domain.InboundEvent
import com.goim.kmp.domain.OutboundEnvelope
import com.goim.kmp.domain.ServerSettingsRepository
import com.goim.kmp.model.ConnectionState
import com.goim.kmp.model.TransportKind
import com.goim.kmp.model.User
import com.goim.kmp.pipeline.CmdDispatchHandler
import com.goim.kmp.pipeline.IMPipeline
import com.goim.kmp.pipeline.LengthFrameInboundHandler
import com.goim.kmp.pipeline.LengthFrameOutboundHandler
import com.goim.kmp.pipeline.LoggingHandler
import com.goim.kmp.pipeline.ProtobufDecodeHandler
import com.goim.kmp.pipeline.ProtobufEncodeHandler
import com.goim.kmp.proto.Cmd
import com.goim.kmp.transport.IMEndpoint
import com.goim.kmp.transport.IMTransport
import com.goim.kmp.transport.ReconnectPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import im.Message as WireMessage

/**
 * 长连接状态机（对齐 iOS `ConnectionRepositoryImpl`）：
 * 建连 → (TCP) CmdLogin 等 LoginResp → CmdOffline → connected；心跳 25s、连续 3 次无回包断开重连；
 * 指数退避重连；鉴权失败进入 [ConnectionState.AUTH_EXPIRED] 并停止重连。
 *
 * 所有可变状态只在 [serial] 上读写（等价 Swift actor，挂起点可重入）。
 */
class ConnectionRepositoryImpl(
    private val settings: ServerSettingsRepository,
    private val scope: CoroutineScope,
    private val transportFactory: (TransportKind, IMEndpoint) -> IMTransport,
    /** 握手失败时探测 token：false = 被拒，null = 无法判断。 */
    private val validateToken: suspend (User) -> Boolean? = { null },
    /** 端类型（见 `devicePlatform`）；空 = 不上报，网关按旧客户端处理。 */
    private val platform: String = "",
    private val heartbeatIntervalMillis: Long = 25_000,
    private val openTimeoutMillis: Long = 10_000,
    private val loginTimeoutMillis: Long = 5_000,
    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : ConnectionRepository {

    private val log = Logger.withTag("IM")

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val events = Channel<InboundEvent>(Channel.UNLIMITED)

    /** 单一收集者（SessionCoordinator）。 */
    override val inboundEvents: Flow<InboundEvent> = events.receiveAsFlow()

    private var transport: IMTransport? = null
    private var pipeline: IMPipeline? = null
    private var readJob: Job? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null

    private var generation = 0
    private var seq = 0L
    private var currentUser: User? = null
    private var currentKind = settings.preferredTransport.value
    private var intentionalDisconnect = false
    private var authExpired = false
    private var linkEstablished = false
    private var reconnectAttempt = 0
    private var missedHeartbeats = 0
    private var pongReceived = false

    override suspend fun connect(user: User, transport: TransportKind) = withContext(serial) {
        connectInternal(user, transport, cancelReconnect = true)
    }

    override suspend fun disconnect() = withContext(serial) {
        intentionalDisconnect = true
        authExpired = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
        teardown(keepUser = false)
        _state.value = ConnectionState.DISCONNECTED
    }

    override suspend fun ensureConnected() = withContext(serial) {
        if (!mayReconnectNow()) return@withContext
        val user = currentUser ?: return@withContext
        when (_state.value) {
            ConnectionState.CONNECTED, ConnectionState.CONNECTING -> return@withContext
            // 前台 / 网络恢复时不必等完退避，立即重试。
            ConnectionState.RECONNECTING -> {
                reconnectJob?.cancel()
                reconnectJob = null
                reconnectAttempt = 0
            }
            ConnectionState.DISCONNECTED, ConnectionState.AUTH_EXPIRED -> Unit
        }
        try {
            connectInternal(user, currentKind, cancelReconnect = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // connectInternal 已处理 authExpired / 排队重连
        }
    }

    override suspend fun send(envelope: OutboundEnvelope) = withContext(serial) {
        val p = pipeline
        val user = currentUser
        if (p == null || user == null || !linkEstablished) throw DomainError.InvalidState("not connected")
        p.write(map(envelope, user))
    }

    // region connect

    private suspend fun connectInternal(user: User, kind: TransportKind, cancelReconnect: Boolean) {
        intentionalDisconnect = false
        authExpired = false
        linkEstablished = false
        currentUser = user
        currentKind = kind
        if (cancelReconnect) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        stopHeartbeat()
        teardown(keepUser = true)
        _state.value = ConnectionState.CONNECTING

        val gen = ++generation
        val loginGate = CompletableDeferred<Unit>()
        val endpoint = IMEndpoint.from(settings.apiBaseUrl.value, user.token, settings.deviceId, platform)
        val t = transportFactory(kind, endpoint)
        transport = t
        val p = buildPipeline(user, kind, t, loginGate)
        pipeline = p

        try {
            withTimeout(openTimeoutMillis) { t.open() }
            readJob = scope.launch(serial) { readLoop(gen, t, p, loginGate) }

            if (kind == TransportKind.TCP) {
                p.write(WireMessage(cmd = Cmd.LOGIN, content = loginContent(endpoint)))
                try {
                    withTimeout(loginTimeoutMillis) { loginGate.await() }
                } catch (_: TimeoutCancellationException) {
                    // 链路已通但没有 LoginResp：服务端拒绝了 token。
                    throw DomainError.NotAuthenticated()
                }
            }
            p.write(WireMessage(cmd = Cmd.OFFLINE, seq = nextSeq()))

            linkEstablished = true
            reconnectAttempt = 0
            startHeartbeat(gen)
            _state.value = ConnectionState.CONNECTED
            log.i { "connected via ${kind.title}" }
        } catch (e: TimeoutCancellationException) {
            onConnectFailed(gen, user, DomainError.Network("connect timeout"))
        } catch (e: CancellationException) {
            if (gen == generation) teardown(keepUser = true)
            throw e
        } catch (e: Exception) {
            onConnectFailed(gen, user, e)
        }
    }

    /** 抛出原错误（或 NotAuthenticated）；必要时排队重连。 */
    private suspend fun onConnectFailed(gen: Int, user: User, error: Exception): Nothing {
        if (gen != generation) throw error
        log.w { "connect failed: ${error.message}" }
        stopHeartbeat()
        linkEstablished = false
        teardown(keepUser = true)
        if (isAuthError(error) || validateToken(user) == false) {
            handleAuthExpired()
            throw DomainError.NotAuthenticated()
        }
        if (mayReconnectNow()) scheduleReconnect(user)
        throw error
    }

    private fun buildPipeline(user: User, kind: TransportKind, t: IMTransport, loginGate: CompletableDeferred<Unit>): IMPipeline {
        val p = IMPipeline { bytes -> t.send(bytes) }
        p.addLast(LoggingHandler())
        if (kind == TransportKind.TCP) p.addLast(LengthFrameInboundHandler())
        p.addLast(ProtobufDecodeHandler())
        p.addLast(
            CmdDispatchHandler(
                selfUid = user.uid,
                onEvent = { events.trySend(it) },
                onLoginResp = { loginGate.complete(Unit) },
                onHeartbeat = { pongReceived = true },
                onKick = {
                    intentionalDisconnect = true
                    reconnectJob?.cancel()
                    reconnectJob = null
                },
            ),
        )
        if (kind == TransportKind.TCP) p.addLast(LengthFrameOutboundHandler())
        p.addLast(ProtobufEncodeHandler())
        return p
    }

    private suspend fun readLoop(gen: Int, t: IMTransport, p: IMPipeline, loginGate: CompletableDeferred<Unit>) {
        val reason = try {
            t.incoming().collect { chunk -> p.fireChannelRead(chunk) }
            "closed"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e::class.simpleName ?: "closed"
        }
        loginGate.completeExceptionally(DomainError.Network(reason))
        // 新协程处理：teardown 会取消 readJob（即当前协程）。
        scope.launch(serial) { onTransportFailed(gen, reason) }
    }

    private suspend fun onTransportFailed(gen: Int, reason: String) {
        if (gen != generation) return
        log.w { "transport failed: $reason" }
        stopHeartbeat()
        val wasEstablished = linkEstablished
        linkEstablished = false
        if (intentionalDisconnect || authExpired) return
        // 握手阶段的失败由 connectInternal 的 catch 处理，避免重复排队。
        if (!wasEstablished) return
        teardown(keepUser = true)
        _state.value = ConnectionState.DISCONNECTED
        currentUser?.let { scheduleReconnect(it) }
    }

    private suspend fun handleAuthExpired() {
        authExpired = true
        intentionalDisconnect = true
        // 可能在 reconnectJob 内被调用：先落状态，再取消（取消自身后的挂起点会抛出）。
        _state.value = ConnectionState.AUTH_EXPIRED
        reconnectJob?.cancel()
        reconnectJob = null
        stopHeartbeat()
        teardown(keepUser = false)
    }

    private fun isAuthError(e: Throwable): Boolean =
        e is DomainError.NotAuthenticated || ReconnectPolicy.isAuthFailure(e.message)

    // endregion

    // region reconnect

    private fun mayReconnectNow() = ReconnectPolicy.mayReconnect(
        intentionalDisconnect = intentionalDisconnect,
        authExpired = authExpired,
        hasUser = currentUser != null,
    )

    private fun scheduleReconnect(user: User) {
        if (!mayReconnectNow()) return
        if (reconnectJob?.isActive == true) return
        _state.value = ConnectionState.RECONNECTING
        reconnectJob = scope.launch(serial) {
            while (isActive && mayReconnectNow()) {
                val attempt = reconnectAttempt++
                val wait = ReconnectPolicy.delayMillis(attempt)
                log.i { "reconnect attempt=$attempt in ${wait}ms" }
                _state.value = ConnectionState.RECONNECTING
                delay(wait)
                if (!mayReconnectNow()) break
                try {
                    connectInternal(user, currentKind, cancelReconnect = false)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (authExpired) break
                }
            }
        }.also { job ->
            job.invokeOnCompletion { scope.launch(serial) { if (reconnectJob === job) reconnectJob = null } }
        }
    }

    // endregion

    // region heartbeat

    private fun startHeartbeat(gen: Int) {
        heartbeatJob?.cancel()
        missedHeartbeats = 0
        pongReceived = true
        heartbeatJob = scope.launch(serial) {
            while (isActive) {
                delay(heartbeatIntervalMillis)
                if (gen != generation || !tickHeartbeat()) break
            }
        }
    }

    /** 返回 false 结束心跳循环；断链处理放到新协程（会取消心跳协程自身）。 */
    private suspend fun tickHeartbeat(): Boolean {
        if (!linkEstablished || intentionalDisconnect || authExpired || currentUser == null) return false
        if (pongReceived) {
            missedHeartbeats = 0
        } else if (++missedHeartbeats >= MAX_MISSED_HEARTBEATS) {
            scope.launch(serial) { tearDownStaleLink() }
            return false
        }
        pongReceived = false
        return try {
            pipeline?.write(WireMessage(cmd = Cmd.HEARTBEAT, seq = nextSeq()))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            scope.launch(serial) { tearDownStaleLink() }
            false
        }
    }

    private suspend fun tearDownStaleLink() {
        if (!linkEstablished) return
        log.w { "heartbeat lost, reconnecting" }
        linkEstablished = false
        stopHeartbeat()
        teardown(keepUser = true)
        _state.value = ConnectionState.DISCONNECTED
        currentUser?.let { scheduleReconnect(it) }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        missedHeartbeats = 0
        pongReceived = false
    }

    // endregion

    private suspend fun teardown(keepUser: Boolean) {
        generation++
        readJob?.cancel()
        readJob = null
        val t = transport
        transport = null
        pipeline = null
        linkEstablished = false
        if (!keepUser) currentUser = null
        t?.close()
    }

    private fun nextSeq(): Long = ++seq

    /** TCP CmdLogin：带设备信息时发 JSON，否则发裸 JWT（网关两种都认）。 */
    private fun loginContent(endpoint: IMEndpoint): String {
        if (endpoint.deviceId.isEmpty() && endpoint.platform.isEmpty()) return endpoint.token
        return buildJsonObject {
            put("token", endpoint.token)
            put("device_id", endpoint.deviceId)
            put("platform", endpoint.platform)
        }.toString()
    }

    private fun map(envelope: OutboundEnvelope, user: User): WireMessage {
        val now = nowMillis()
        return when (envelope) {
            is OutboundEnvelope.Chat -> envelope.message.let { m ->
                WireMessage(
                    cmd = Cmd.CHAT, seq = if (m.clientSeq == 0L) nextSeq() else m.clientSeq, from = user.uid, to = m.toUid,
                    chat_type = m.chatType.value, msg_type = m.msgType.value, content = m.content, timestamp = now, need_ack = true,
                )
            }
            is OutboundEnvelope.File -> envelope.message.let { m ->
                WireMessage(
                    cmd = Cmd.FILE, seq = if (m.clientSeq == 0L) nextSeq() else m.clientSeq, from = user.uid, to = m.toUid,
                    chat_type = m.chatType.value, msg_type = m.msgType.value, content = m.content, timestamp = now, need_ack = true,
                )
            }
            OutboundEnvelope.Offline -> WireMessage(cmd = Cmd.OFFLINE, seq = nextSeq(), from = user.uid, timestamp = now)
            is OutboundEnvelope.History -> WireMessage(
                cmd = Cmd.HISTORY, seq = envelope.limit.toLong(), from = user.uid, to = envelope.peer,
                chat_type = envelope.chatType.value, timestamp = envelope.before ?: now,
            )
            is OutboundEnvelope.ReadReceipt -> WireMessage(
                cmd = Cmd.READ_RECEIPT, seq = nextSeq(), from = user.uid, to = envelope.to,
                chat_type = envelope.chatType.value, timestamp = now,
            )
            OutboundEnvelope.UnreadCount -> WireMessage(cmd = Cmd.UNREAD_COUNT, seq = nextSeq(), from = user.uid, timestamp = now)
            OutboundEnvelope.Heartbeat -> WireMessage(cmd = Cmd.HEARTBEAT, seq = nextSeq(), from = user.uid, timestamp = now)
            is OutboundEnvelope.FriendRequest -> WireMessage(
                cmd = Cmd.FRIEND_REQUEST, seq = nextSeq(), from = user.uid, to = envelope.to, timestamp = now,
            )
            is OutboundEnvelope.FriendResponse -> WireMessage(
                cmd = Cmd.FRIEND_RESPONSE, seq = nextSeq(), from = user.uid, to = envelope.to, timestamp = now,
                content = if (envelope.accept) """{"action":"accept"}""" else """{"action":"reject"}""",
            )
        }
    }

    private companion object {
        const val MAX_MISSED_HEARTBEATS = 3
    }
}
