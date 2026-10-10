package com.goim.kmp.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReconnectPolicyTest {

    @Test
    fun exponentialBackoffCapped() {
        assertEquals(1_000, ReconnectPolicy.delayMillis(0, jitter = 0.0))
        assertEquals(2_000, ReconnectPolicy.delayMillis(1, jitter = 0.0))
        assertEquals(16_000, ReconnectPolicy.delayMillis(4, jitter = 0.0))
        assertEquals(30_000, ReconnectPolicy.delayMillis(5, jitter = 0.0))
        assertEquals(30_000, ReconnectPolicy.delayMillis(100, jitter = 0.0))
    }

    @Test
    fun jitterIsClamped() {
        assertEquals(1_200, ReconnectPolicy.delayMillis(0, jitter = 0.9))
        assertEquals(800, ReconnectPolicy.delayMillis(0, jitter = -0.9))
        repeat(50) {
            val d = ReconnectPolicy.delayMillis(2)
            assertTrue(d in 3_200..4_800)
        }
    }

    @Test
    fun authFailureMarkers() {
        assertTrue(ReconnectPolicy.isAuthFailure("Expected status code 101 but was 401"))
        assertTrue(ReconnectPolicy.isAuthFailure("invalid token: token is expired"))
        assertFalse(ReconnectPolicy.isAuthFailure("Connection refused"))
        assertFalse(ReconnectPolicy.isAuthFailure(null))
    }

    @Test
    fun endpointFromBaseUrl() {
        val https = IMEndpoint.from("https://im.chaisz.com", "t")
        assertEquals("im.chaisz.com", https.host)
        assertEquals(443, https.httpPort)
        assertEquals(8081, https.tcpPort)
        assertTrue(https.useTls)

        val local = IMEndpoint.from("http://192.168.1.5:8080", "t")
        assertEquals(8080, local.httpPort)
        assertFalse(local.useTls)
    }

    @Test
    fun mayReconnect() {
        assertTrue(ReconnectPolicy.mayReconnect(intentionalDisconnect = false, authExpired = false, hasUser = true))
        assertFalse(ReconnectPolicy.mayReconnect(intentionalDisconnect = true, authExpired = false, hasUser = true))
        assertFalse(ReconnectPolicy.mayReconnect(intentionalDisconnect = false, authExpired = true, hasUser = true))
    }
}
