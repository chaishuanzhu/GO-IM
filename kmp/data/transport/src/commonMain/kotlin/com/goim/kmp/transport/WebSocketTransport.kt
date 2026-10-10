package com.goim.kmp.transport

import com.goim.kmp.model.TransportKind
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.URLProtocol
import io.ktor.http.path
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/** `ws(s)://{host}/ws?token={jwt}`，binary 帧。[client] 需安装 `WebSockets` 插件。 */
class WebSocketTransport(
    private val endpoint: IMEndpoint,
    private val client: HttpClient,
) : IMTransport {
    override val kind = TransportKind.WEB_SOCKET

    private var session: DefaultClientWebSocketSession? = null

    override suspend fun open() {
        session = client.webSocketSession {
            url {
                protocol = if (endpoint.useTls) URLProtocol.WSS else URLProtocol.WS
                host = endpoint.host
                port = endpoint.httpPort
                path("ws")
                parameters.append("token", endpoint.token)
            }
        }
    }

    override fun incoming(): Flow<ByteArray> = flow {
        val s = session ?: throw TransportClosedException("ws not connected")
        try {
            for (frame in s.incoming) {
                if (frame is Frame.Binary) emit(frame.readBytes())
            }
        } catch (e: Exception) {
            throw TransportClosedException(e.message ?: "ws closed", e)
        }
        throw TransportClosedException("ws closed")
    }

    override suspend fun send(bytes: ByteArray) {
        val s = session ?: throw TransportClosedException("ws not connected")
        s.send(Frame.Binary(true, bytes))
    }

    override suspend fun close() {
        val s = session ?: return
        session = null
        // 断网时 close 帧可能发不出去，不能无限等。
        runCatching { withTimeoutOrNull(1_000) { s.close() } }
        s.cancel()
    }
}
