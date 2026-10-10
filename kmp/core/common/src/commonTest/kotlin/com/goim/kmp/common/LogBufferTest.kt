package com.goim.kmp.common

import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogBufferTest {
    @Test
    fun keepsMostRecentEntriesWithIncreasingIds() {
        val buffer = LogBuffer(capacity = 3)
        repeat(5) { buffer.log(Severity.Info, "m$it", "T", null) }
        val entries = buffer.entries.value
        assertEquals(listOf("m2", "m3", "m4"), entries.map { it.message })
        assertEquals(listOf(3L, 4L, 5L), entries.map { it.id })
    }

    @Test
    fun appendsThrowableAndClears() {
        val buffer = LogBuffer()
        buffer.log(Severity.Error, "boom", "IM", IllegalStateException("bad state"))
        val entry = buffer.entries.value.single()
        assertEquals("IM", entry.tag)
        assertTrue("bad state" in entry.message)
        buffer.clear()
        assertTrue(buffer.entries.value.isEmpty())
    }
}
