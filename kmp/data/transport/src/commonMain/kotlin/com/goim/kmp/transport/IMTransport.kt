package com.goim.kmp.transport

import com.goim.kmp.model.TransportKind
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.flow.Flow

/**
 * 长连接通道。WS：一帧 = 一个 protobuf；TCP：原始字节流（由 Pipeline 拆包）。
 *
 * [incoming] 只允许一个收集者；链路断开时以 [TransportClosedException] 结束。
 */
interface IMTransport {
    val kind: TransportKind

    /** 建立连接，链路可用后返回；失败抛出异常。 */
    suspend fun open()

    fun incoming(): Flow<ByteArray>

    suspend fun send(bytes: ByteArray)

    suspend fun close()
}

class TransportClosedException(reason: String, cause: Throwable? = null) : Exception(reason, cause)

/** 由 API 地址推导：WS 与 HTTP 同 host/port，TCP 用同 host + 8081（与 iOS `ServerConfig` 一致）。 */
data class IMEndpoint(
    val host: String,
    val httpPort: Int,
    val tcpPort: Int,
    val useTls: Boolean,
    val token: String,
) {
    companion object {
        const val DEFAULT_TCP_PORT = 8081

        fun from(apiBaseUrl: String, token: String): IMEndpoint {
            val url = Url(apiBaseUrl)
            val tls = url.protocol == URLProtocol.HTTPS
            return IMEndpoint(
                host = url.host,
                httpPort = url.port,
                tcpPort = DEFAULT_TCP_PORT,
                useTls = tls,
                token = token,
            )
        }
    }
}
