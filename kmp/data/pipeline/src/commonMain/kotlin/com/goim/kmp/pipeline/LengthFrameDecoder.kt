package com.goim.kmp.pipeline

/** TCP 长度帧：`[4 字节大端长度][protobuf]`。 */
object LengthFrame {
    const val HEADER_SIZE = 4

    /** 与服务端 gnet 读上限同量级；超过视为流错乱。 */
    const val MAX_FRAME_SIZE = 16 * 1024 * 1024

    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        val n = payload.size
        out[0] = (n ushr 24).toByte()
        out[1] = (n ushr 16).toByte()
        out[2] = (n ushr 8).toByte()
        out[3] = n.toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }
}

/** 粘包 / 拆包：累积字节，产出完整帧载荷。 */
class LengthFrameDecoder {
    private var buffer = ByteArray(0)

    fun feed(chunk: ByteArray): List<ByteArray> {
        buffer = if (buffer.isEmpty()) chunk else buffer + chunk
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= LengthFrame.HEADER_SIZE) {
            val length = ((buffer[offset].toInt() and 0xff) shl 24) or
                ((buffer[offset + 1].toInt() and 0xff) shl 16) or
                ((buffer[offset + 2].toInt() and 0xff) shl 8) or
                (buffer[offset + 3].toInt() and 0xff)
            if (length < 0 || length > LengthFrame.MAX_FRAME_SIZE) {
                buffer = ByteArray(0)
                throw IllegalStateException("invalid frame length $length")
            }
            val end = offset + LengthFrame.HEADER_SIZE + length
            if (end > buffer.size) break
            frames += buffer.copyOfRange(offset + LengthFrame.HEADER_SIZE, end)
            offset = end
        }
        buffer = if (offset == 0) buffer else buffer.copyOfRange(offset, buffer.size)
        return frames
    }
}
