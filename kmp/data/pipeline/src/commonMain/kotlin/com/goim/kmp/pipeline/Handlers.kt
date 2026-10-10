package com.goim.kmp.pipeline

import co.touchlab.kermit.Logger
import com.goim.kmp.proto.MessageCodec
import im.Message as WireMessage

class LoggingHandler(private val logger: Logger = Logger.withTag("IM")) : InboundHandler, OutboundHandler {
    override val name = "logging"

    override suspend fun channelRead(ctx: HandlerContext, msg: Any) {
        logger.d { "[IN] ${describe(msg)}" }
        ctx.fireChannelRead(msg)
    }

    override suspend fun write(ctx: HandlerContext, msg: Any) {
        logger.d { "[OUT] ${describe(msg)}" }
        ctx.write(msg)
    }

    private fun describe(msg: Any): String = when (msg) {
        is WireMessage -> format(msg)
        is ByteArray -> {
            val wire = runCatching { MessageCodec.decode(msg) }.getOrNull()
                ?: msg.takeIf { it.size > LengthFrame.HEADER_SIZE }?.let {
                    runCatching { MessageCodec.decode(it.copyOfRange(LengthFrame.HEADER_SIZE, it.size)) }.getOrNull()
                }
            if (wire != null) "Data(${msg.size}B) → ${format(wire)}" else "Data(${msg.size}B)"
        }
        else -> msg::class.simpleName ?: "?"
    }

    private fun format(w: WireMessage): String = buildList {
        add("cmd=${w.cmd}")
        if (w.seq != 0L) add("seq=${w.seq}")
        if (w.msg_id != 0L) add("msgId=${w.msg_id}")
        if (w.from.isNotEmpty()) add("from=${w.from}")
        if (w.to.isNotEmpty()) add("to=${w.to}")
        if (w.chat_type != 0) add("chatType=${w.chat_type}")
        if (w.msg_type != 0) add("msgType=${w.msg_type}")
        if (w.need_ack) add("needAck")
        if (w.timestamp != 0L) add("ts=${w.timestamp}")
        if (w.content.isNotEmpty()) add("content=${w.content.take(200)}")
    }.joinToString(" ")
}

/** 仅 TCP 安装：拆包。 */
class LengthFrameInboundHandler : InboundHandler {
    override val name = "length-frame-in"
    private val decoder = LengthFrameDecoder()

    override suspend fun channelRead(ctx: HandlerContext, msg: Any) {
        if (msg !is ByteArray) return ctx.fireChannelRead(msg)
        for (frame in decoder.feed(msg)) ctx.fireChannelRead(frame)
    }
}

/** 仅 TCP 安装：加 4 字节长度头。 */
class LengthFrameOutboundHandler : OutboundHandler {
    override val name = "length-frame-out"

    override suspend fun write(ctx: HandlerContext, msg: Any) {
        ctx.write(if (msg is ByteArray) LengthFrame.encode(msg) else msg)
    }
}

class ProtobufDecodeHandler : InboundHandler {
    override val name = "protobuf-decode"

    override suspend fun channelRead(ctx: HandlerContext, msg: Any) {
        ctx.fireChannelRead(if (msg is ByteArray) MessageCodec.decode(msg) else msg)
    }
}

class ProtobufEncodeHandler : OutboundHandler {
    override val name = "protobuf-encode"

    override suspend fun write(ctx: HandlerContext, msg: Any) {
        ctx.write(if (msg is WireMessage) MessageCodec.encode(msg) else msg)
    }
}
