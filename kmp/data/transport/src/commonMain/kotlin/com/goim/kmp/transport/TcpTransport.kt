package com.goim.kmp.transport

import com.goim.kmp.common.ioDispatcher
import com.goim.kmp.model.TransportKind
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 原始 TCP（`{host}:8081`），三端均用 ktor-network。
 * iOS 计划替换为 Network.framework（`NWConnection`），接口不变（docs/15 §15）。
 */
class TcpTransport(private val endpoint: IMEndpoint) : IMTransport {
    override val kind = TransportKind.TCP

    private var selector: SelectorManager? = null
    private var socket: Socket? = null
    private var readChannel: ByteReadChannel? = null
    private var writeChannel: ByteWriteChannel? = null
    private val writeLock = Mutex()

    override suspend fun open() {
        val sel = SelectorManager(ioDispatcher)
        selector = sel
        val s = aSocket(sel).tcp().connect(endpoint.host, endpoint.tcpPort)
        socket = s
        readChannel = s.openReadChannel()
        writeChannel = s.openWriteChannel(autoFlush = false)
    }

    override fun incoming(): Flow<ByteArray> = flow {
        val read = readChannel ?: throw TransportClosedException("tcp not connected")
        val buffer = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = read.readAvailable(buffer, 0, buffer.size)
                if (n < 0) break
                if (n > 0) emit(buffer.copyOf(n))
            }
        } catch (e: Exception) {
            throw TransportClosedException(e.message ?: "tcp closed", e)
        }
        throw TransportClosedException("tcp closed")
    }

    override suspend fun send(bytes: ByteArray) {
        val write = writeChannel ?: throw TransportClosedException("tcp not connected")
        writeLock.withLock {
            write.writeFully(bytes, 0, bytes.size)
            write.flush()
        }
    }

    override suspend fun close() {
        runCatching { socket?.close() }
        runCatching { selector?.close() }
        socket = null
        selector = null
        readChannel = null
        writeChannel = null
    }
}
