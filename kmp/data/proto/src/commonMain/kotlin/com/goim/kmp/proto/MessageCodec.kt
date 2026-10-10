package com.goim.kmp.proto

import im.Message

/** `im.Message` 的 protobuf 二进制编解码；WS 帧体与 TCP 长度帧载荷共用。 */
object MessageCodec {
    fun encode(message: Message): ByteArray = Message.ADAPTER.encode(message)

    fun decode(bytes: ByteArray): Message = Message.ADAPTER.decode(bytes)
}
