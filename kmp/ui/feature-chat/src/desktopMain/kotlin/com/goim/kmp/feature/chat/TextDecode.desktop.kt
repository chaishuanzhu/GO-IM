package com.goim.kmp.feature.chat

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal fun readTextPreviewJvm(file: File, maxBytes: Int): TextPreviewContent? = runCatching {
    val length = file.length()
    val bytes = file.inputStream().use { input ->
        val buffer = ByteArray(minOf(length, maxBytes.toLong()).toInt())
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        if (read == buffer.size) buffer else buffer.copyOf(read)
    }
    TextPreviewContent(decodePreviewText(bytes), truncated = length > bytes.size)
}.getOrNull()

/** 优先严格 UTF-8（容忍截断造成的末尾半个字符），否则按 GB18030 解码。 */
internal fun decodePreviewText(bytes: ByteArray): String {
    for (drop in 0..minOf(3, bytes.size)) {
        strictDecode(bytes, bytes.size - drop, Charsets.UTF_8)?.let { return it }
    }
    return String(bytes, Charset.forName("GB18030"))
}

private fun strictDecode(bytes: ByteArray, length: Int, charset: Charset): String? = try {
    charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, 0, length))
        .toString()
} catch (_: CharacterCodingException) {
    null
}
