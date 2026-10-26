package com.goim.kmp.feature.chat

import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals

class TextDecodeTest {
    @Test
    fun decodesUtf8AndTruncatedTail() {
        val bytes = "你好，世界".toByteArray(Charsets.UTF_8)
        assertEquals("你好，世界", decodePreviewText(bytes))
        assertEquals("你好，世", decodePreviewText(bytes.copyOf(bytes.size - 1)))
    }

    @Test
    fun fallsBackToGb18030() {
        val bytes = "中文日志".toByteArray(Charset.forName("GB18030"))
        assertEquals("中文日志", decodePreviewText(bytes))
    }
}
