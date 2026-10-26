package com.goim.kmp.feature.chat

import com.goim.kmp.model.FileMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilePreviewTest {
    private fun meta(name: String, mime: String) = FileMeta(fileId = "f1", name = name, size = 1, mime = mime)

    @Test
    fun detectsPreviewKind() {
        assertEquals(FilePreviewKind.Pdf, filePreviewKind(meta("report.PDF", "application/octet-stream")))
        assertEquals(FilePreviewKind.Pdf, filePreviewKind(meta("download", "application/pdf")))
        assertEquals(FilePreviewKind.Text, filePreviewKind(meta("notes.md", "application/octet-stream")))
        assertEquals(FilePreviewKind.Text, filePreviewKind(meta("readme", "text/plain; charset=utf-8")))
        assertEquals(FilePreviewKind.Code, filePreviewKind(meta("main.go", "text/plain; charset=utf-8")))
        assertEquals(FilePreviewKind.Code, filePreviewKind(meta("data", "application/json")))
        assertNull(filePreviewKind(meta("song.mp3", "audio/mpeg")))
        assertNull(filePreviewKind(meta("sheet.xlsx", "application/zip")))
    }

    @Test
    fun splitsLongLines() {
        val lines = splitPreviewLines("\uFEFFa\n" + "b".repeat(5), maxLineChars = 2)
        assertEquals(listOf("a", "bb", "bb", "b"), lines)
    }
}
