package com.goim.kmp.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaTest {
    @Test
    fun fileContentOmitsNonPositiveDimensions() {
        val json = FileMeta("123", "a.png", 88, "image/png", width = 64, height = 0, thumbWidth = null, duration = 0).toContentJson()
        assertEquals("""{"file_id":"123","name":"a.png","size":88,"mime":"image/png","width":64}""", json)
        val decoded = MessagePreview.decodeFileMeta(json)!!
        assertEquals("123", decoded.fileId)
        assertEquals(64, decoded.width)
        assertNull(decoded.height)
    }

    @Test
    fun fileContentRoundTripsThumbAndDuration() {
        val meta = FileMeta("9", "v.mp4", 1024, "video/mp4", 640, 360, thumbWidth = 200, thumbHeight = 112, duration = 7)
        val decoded = MessagePreview.decodeFileMeta(meta.toContentJson())!!
        assertEquals(meta.copy(), decoded)
    }

    @Test
    fun stickerContentUsesSnakeCaseAndKeepsUrl() {
        val ref = StickerRef("capoo", "001-file_1437", "gif", 240, 240, "https://cdn/capoo/001-file_1437.gif")
        val json = ref.toContentJson()
        assertTrue(json.contains("\"pack_id\":\"capoo\"") && json.contains("\"sticker_id\":\"001-file_1437\""), json)
        assertEquals(ref, StickerRef.decode(json))
        assertTrue(ref.isAnimated)
        // iOS 发出的最简 content（无 url / 尺寸）也能解析
        assertEquals(StickerRef("doge", "001"), StickerRef.decode("""{"pack_id":"doge","sticker_id":"001"}"""))
        assertNull(StickerRef.decode("not json"))
    }

    @Test
    fun stickerItemDerivesFormatFromFileName() {
        assertEquals("jpg", StickerItem("tuzi", "001", "001.JPG").asRef().format)
        assertEquals("png", StickerItem("x", "y", "noext").asRef().format)
    }

    @Test
    fun localAttachmentDetection() {
        fun msg(type: MsgType, content: String) = Message(
            clientSeq = 1, conversationId = "dm:a_b", fromUid = "a", toUid = "b", chatType = ChatType.SINGLE,
            msgType = type, content = content, timestampMs = 0, status = MessageStatus.SENDING, isOutgoing = true,
        )
        assertTrue(msg(MsgType.Image, FileMeta("local:ABC", "a.jpg", 1, "image/jpeg").toContentJson()).hasLocalAttachment)
        assertFalse(msg(MsgType.Image, FileMeta("42", "a.jpg", 1, "image/jpeg").toContentJson()).hasLocalAttachment)
        assertFalse(msg(MsgType.Text, "local:ABC").hasLocalAttachment)
    }

    @Test
    fun mimeFromFileName() {
        assertEquals("image/jpeg", MimeTypes.fromFileName("IMG.JPEG"))
        assertEquals("audio/mp4", MimeTypes.fromFileName("voice.m4a"))
        assertEquals("application/pdf", MimeTypes.fromFileName("a.b.pdf"))
        assertEquals(MimeTypes.DEFAULT, MimeTypes.fromFileName("README"))
    }
}
