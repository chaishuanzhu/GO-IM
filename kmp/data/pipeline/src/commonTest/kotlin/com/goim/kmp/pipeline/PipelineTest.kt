package com.goim.kmp.pipeline

import com.goim.kmp.domain.InboundEvent
import com.goim.kmp.model.ChatType
import com.goim.kmp.model.MsgType
import com.goim.kmp.proto.Cmd
import com.goim.kmp.proto.MessageCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import im.Message as WireMessage

class PipelineTest {

    @Test
    fun lengthFrameDecoderHandlesStickyAndSplitPackets() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(9)
        val stream = LengthFrame.encode(a) + LengthFrame.encode(b)
        val decoder = LengthFrameDecoder()

        assertTrue(decoder.feed(stream.copyOfRange(0, 2)).isEmpty())
        val first = decoder.feed(stream.copyOfRange(2, 8))
        assertEquals(1, first.size)
        assertContentEquals(a, first[0])
        val second = decoder.feed(stream.copyOfRange(8, stream.size))
        assertContentEquals(b, second.single())

        assertEquals(2, LengthFrameDecoder().feed(stream).size)
    }

    @Test
    fun lengthFrameDecoderRejectsGarbageLength() {
        assertFailsWith<IllegalStateException> {
            LengthFrameDecoder().feed(byteArrayOf(0x7f, -1, -1, -1))
        }
    }

    @Test
    fun outboundRunsInReverseOrderAndAddsFrame() = runTest {
        val written = mutableListOf<ByteArray>()
        val pipeline = tcpPipeline(selfUid = "alice", events = mutableListOf()) { written += it }
        val wire = WireMessage(cmd = Cmd.HEARTBEAT, seq = 7)

        pipeline.write(wire)

        val frame = written.single()
        assertContentEquals(LengthFrame.encode(MessageCodec.encode(wire)), frame)
    }

    @Test
    fun inboundTcpRoundTripDispatchesEvents() = runTest {
        val events = mutableListOf<InboundEvent>()
        var loginResp = 0
        var pongs = 0
        val pipeline = tcpPipeline(
            selfUid = "alice",
            events = events,
            onLoginResp = { loginResp++ },
            onHeartbeat = { pongs++ },
        ) {}

        val chat = WireMessage(
            cmd = Cmd.CHAT, seq = 11, msg_id = 99, from = "bob", to = "alice",
            chat_type = 1, msg_type = 1, content = "hi", timestamp = 1000,
        )
        val bytes = listOf(
            WireMessage(cmd = Cmd.LOGIN_RESP),
            chat,
            WireMessage(cmd = Cmd.ACK, seq = 5, msg_id = 77),
            WireMessage(cmd = Cmd.HEARTBEAT),
            WireMessage(cmd = Cmd.HISTORY, seq = 3),
            WireMessage(cmd = Cmd.UNREAD_COUNT, content = """{"uid":"alice","counts":{"bob":2,"g_1":5}}"""),
            WireMessage(cmd = Cmd.KICK),
        ).fold(ByteArray(0)) { acc, m -> acc + LengthFrame.encode(MessageCodec.encode(m)) }

        // 任意切分喂入
        bytes.toList().chunked(5).forEach { pipeline.fireChannelRead(it.toByteArray()) }

        assertEquals(1, loginResp)
        assertEquals(1, pongs)
        assertEquals(5, events.size)
        val received = assertIs<InboundEvent.MessageReceived>(events[0]).message
        assertEquals("dm:alice_bob", received.conversationId)
        assertEquals(99L, received.serverMsgId)
        assertEquals(MsgType.Text, received.msgType)
        assertEquals(ChatType.SINGLE, received.chatType)
        assertEquals(false, received.isOutgoing)
        assertEquals(InboundEvent.Ack(5, 77), events[1])
        assertEquals(InboundEvent.HistoryFinished(3), events[2])
        assertEquals(InboundEvent.Unread(mapOf("bob" to 2, "g_1" to 5)), events[3])
        assertEquals(InboundEvent.Kick, events[4])
    }

    @Test
    fun groupAndSelfMessagesMapConversation() = runTest {
        val events = mutableListOf<InboundEvent>()
        val pipeline = IMPipeline {}
            .addLast(ProtobufDecodeHandler())
            .addLast(CmdDispatchHandler("alice", onEvent = { events += it }))

        pipeline.fireChannelRead(MessageCodec.encode(WireMessage(cmd = Cmd.CHAT, from = "bob", to = "g_9", chat_type = 2)))
        pipeline.fireChannelRead(MessageCodec.encode(WireMessage(cmd = Cmd.CHAT, from = "alice", to = "bob", chat_type = 1)))

        val group = assertIs<InboundEvent.MessageReceived>(events[0]).message
        assertEquals("group:g_9", group.conversationId)
        val self = assertIs<InboundEvent.MessageReceived>(events[1]).message
        assertEquals("dm:alice_bob", self.conversationId)
        assertTrue(self.isOutgoing)
    }

    @Test
    fun unreadCountsParsing() {
        assertEquals(mapOf("a" to 1), CmdDispatchHandler.parseUnreadCounts("""{"counts":{"a":1.0}}"""))
        assertEquals(null, CmdDispatchHandler.parseUnreadCounts("not json"))
        assertEquals(null, CmdDispatchHandler.parseUnreadCounts("""{"uid":"x"}"""))
    }

    private fun tcpPipeline(
        selfUid: String,
        events: MutableList<InboundEvent>,
        onLoginResp: () -> Unit = {},
        onHeartbeat: () -> Unit = {},
        writer: suspend (ByteArray) -> Unit,
    ) = IMPipeline(writer)
        .addLast(LoggingHandler())
        .addLast(LengthFrameInboundHandler())
        .addLast(ProtobufDecodeHandler())
        .addLast(CmdDispatchHandler(selfUid, onEvent = { events += it }, onLoginResp = onLoginResp, onHeartbeat = onHeartbeat))
        .addLast(LengthFrameOutboundHandler())
        .addLast(ProtobufEncodeHandler())
}
