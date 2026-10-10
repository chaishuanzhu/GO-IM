package com.goim.kmp.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessagePreviewTest {

    private fun msg(type: MsgType, content: String) = Message(
        clientSeq = 1,
        conversationId = "dm:a_b",
        fromUid = "a",
        toUid = "b",
        chatType = ChatType.SINGLE,
        msgType = type,
        content = content,
        timestampMs = 0,
        status = MessageStatus.SENT,
        isOutgoing = false,
    )

    @Test
    fun previews() {
        assertEquals("hi", MessagePreview.listPreview(msg(MsgType.Text, "hi")))
        assertEquals("[图片]", MessagePreview.listPreview(msg(MsgType.Image, """{"file_id":"1"}""")))
        assertEquals("[语音] 4\"", MessagePreview.listPreview(msg(MsgType.Voice, """{"file_id":"1","duration":3.6}""")))
        assertEquals("[文件] a.pdf", MessagePreview.listPreview(msg(MsgType.File, """{"file_id":7,"name":"a.pdf"}""")))
        assertEquals("[表情]", MessagePreview.listPreview(msg(MsgType.Sticker, "{}")))
        assertEquals(MsgType.UNSUPPORTED_PLACEHOLDER, MessagePreview.listPreview(msg(MsgType.from(42), "x")))
    }

    @Test
    fun systemNoticeInTextMessage() {
        assertEquals("u1 加入了群聊", MessagePreview.listPreview(msg(MsgType.Text, """{"type":"member_joined","uid":"u1"}""")))
        assertNull(MessagePreview.systemNotice("plain"))
    }

    @Test
    fun sanitizeRawJson() {
        assertEquals("[视频] 5\"", MessagePreview.sanitizedListPreview("""{"file_id":"1","mime":"video/mp4","duration":5}"""))
        assertEquals("[图片]", MessagePreview.sanitizedListPreview("""{"file_id":"1","width":10}"""))
        assertEquals("not json", MessagePreview.sanitizedListPreview("not json"))
    }

    @Test
    fun conversationIds() {
        assertEquals("dm:alice_bob", ConversationId.dm("bob", "alice"))
        assertEquals("group:g_1", ConversationId.of("me", "g_1", ChatType.GROUP))
    }

    @Test
    fun fileMetaDimensionsFallBackToThumb() {
        val meta = MessagePreview.decodeFileMeta("""{"file_id":"f","thumb_width":200,"thumb_height":0,"size":12}""")!!
        assertEquals(200, meta.width)
        assertNull(meta.height)
        assertEquals(12, meta.size)
    }
}
