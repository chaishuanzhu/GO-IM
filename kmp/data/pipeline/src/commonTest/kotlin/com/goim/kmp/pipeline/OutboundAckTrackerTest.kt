package com.goim.kmp.pipeline

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboundAckTrackerTest {

    @Test
    fun ackStopsRetries() = runTest {
        val resent = mutableListOf<Long>()
        val failed = mutableListOf<Long>()
        val tracker = OutboundAckTracker(
            scope = backgroundScope,
            resend = { resent += it; true },
            onGiveUp = { failed += it },
        )
        tracker.track(1)
        advanceTimeBy(3_500)
        assertEquals(listOf(1L), resent)
        assertTrue(tracker.acknowledge(1))
        advanceTimeBy(20_000)
        assertEquals(listOf(1L), resent)
        assertTrue(failed.isEmpty())
    }

    @Test
    fun givesUpAfterThreeRetries() = runTest {
        val resent = mutableListOf<Long>()
        val failed = mutableListOf<Long>()
        val tracker = OutboundAckTracker(
            scope = backgroundScope,
            resend = { resent += it; true },
            onGiveUp = { failed += it },
        )
        tracker.track(42)
        advanceTimeBy(11_000)
        assertEquals(listOf(42L, 42L, 42L), resent)
        assertTrue(failed.isEmpty())
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(listOf(42L), failed)
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun failedResendGivesUpImmediately() = runTest {
        val failed = mutableListOf<Long>()
        val tracker = OutboundAckTracker(
            scope = backgroundScope,
            resend = { false },
            onGiveUp = { failed += it },
        )
        tracker.track(7)
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(listOf(7L), failed)
    }
}
