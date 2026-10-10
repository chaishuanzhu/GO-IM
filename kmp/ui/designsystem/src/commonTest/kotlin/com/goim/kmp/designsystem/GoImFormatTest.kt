package com.goim.kmp.designsystem

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class GoImFormatTest {
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime(y, mo, d, h, mi).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private val now = ms(2026, 10, 9, 15, 0)

    @Test
    fun messageTime() {
        assertEquals("09:05", GoImFormat.messageTime(ms(2026, 10, 9, 9, 5), now))
        assertEquals("昨天 23:59", GoImFormat.messageTime(ms(2026, 10, 8, 23, 59), now))
        assertEquals("3/4 08:00", GoImFormat.messageTime(ms(2026, 3, 4, 8, 0), now))
        assertEquals("2025/12/31", GoImFormat.messageTime(ms(2025, 12, 31, 8, 0), now))
    }

    @Test
    fun conversationTime() {
        assertEquals("", GoImFormat.conversationTime(0, now))
        assertEquals("14:30", GoImFormat.conversationTime(ms(2026, 10, 9, 14, 30), now))
        assertEquals("昨天", GoImFormat.conversationTime(ms(2026, 10, 8, 1, 0), now))
        assertEquals("1/2", GoImFormat.conversationTime(ms(2026, 1, 2, 1, 0), now))
    }

    @Test
    fun badgesAndMonogram() {
        assertEquals("99+", GoImFormat.unreadBadge(120))
        assertEquals("7", GoImFormat.unreadBadge(7))
        assertEquals("B", GoImFormat.monogram(" bob"))
        assertEquals("?", GoImFormat.monogram(""))
    }

    @Test
    fun apiUrlValidation() {
        assertTrue(isValidApiBaseUrl("https://im.chaisz.com"))
        assertTrue(isValidApiBaseUrl("http://192.168.1.5:8080/"))
        assertFalse(isValidApiBaseUrl("im.chaisz.com"))
        assertFalse(isValidApiBaseUrl("ftp://x"))
        assertFalse(isValidApiBaseUrl("http://"))
    }
}
