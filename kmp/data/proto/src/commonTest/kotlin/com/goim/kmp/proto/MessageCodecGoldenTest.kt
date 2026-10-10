package com.goim.kmp.proto

import im.Message
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 黄金字节由 Go 服务端 `api/proto/message.pb.go` 以 `proto.MarshalOptions{Deterministic: true}` 编码得到；
 * 修改样本时需同步用 Go 重新生成。
 */
class MessageCodecGoldenTest {

    private val samples = listOf(
        Message(
            seq = 42,
            msg_id = 1234567890123456789,
            cmd = Cmd.CHAT,
            from = "alice",
            to = "bob",
            chat_type = ChatType.SINGLE,
            msg_type = MsgType.TEXT,
            content = "你好, GO-IM",
            timestamp = 1760000000000,
            need_ack = true,
        ) to "082a109582a6efc79e84911118012205616c6963652a03626f6230013801420de4bda0e5a5bd2c20474f2d494d488080b3c19c335001",
        Message(cmd = Cmd.HEARTBEAT) to "1806",
        Message(cmd = Cmd.LOGIN, content = "eyJhbGciOiJIUzI1NiJ9.e30.sig") to
            "1803421c65794a68624763694f694a49557a49314e694a392e6533302e736967",
        Message(seq = -1, cmd = Cmd.ACK) to "08ffffffffffffffffff011802",
    )

    @Test
    fun encodeMatchesGoServer() {
        for ((message, hex) in samples) {
            assertEquals(hex, MessageCodec.encode(message).toHex(), "cmd=${message.cmd}")
        }
    }

    @Test
    fun decodeGoServerBytes() {
        for ((message, hex) in samples) {
            assertEquals(message, MessageCodec.decode(hex.hexToBytes()), "cmd=${message.cmd}")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
