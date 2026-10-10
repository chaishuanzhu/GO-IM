package com.goim.kmp.transport

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/** 重连调度（与 iOS `ReconnectPolicy` 同算法）。 */
object ReconnectPolicy {
    const val INITIAL_DELAY_MS = 1_000L
    const val MAX_DELAY_MS = 30_000L
    const val JITTER_FRACTION = 0.2

    /** 指数退避 `1s → 2s → 4s → …` 封顶 30s，±20% 抖动。[attempt] 从 0 开始。 */
    fun delayMillis(attempt: Int, jitter: Double? = null): Long {
        val shift = min(max(0, attempt), 31)
        val base = min(INITIAL_DELAY_MS shl shift, MAX_DELAY_MS).let { if (it <= 0) MAX_DELAY_MS else it }
        val j = (jitter ?: Random.nextDouble(-JITTER_FRACTION, JITTER_FRACTION))
            .coerceIn(-JITTER_FRACTION, JITTER_FRACTION)
        return max(0L, (base * (1.0 + j)).roundToLong())
    }

    fun mayReconnect(intentionalDisconnect: Boolean, authExpired: Boolean, hasUser: Boolean): Boolean =
        !intentionalDisconnect && !authExpired && hasUser

    private val authMarkers = listOf(
        "401",
        "unauthorized",
        "invalid token",
        "missing token",
        "jwt",
        "auth expired",
        "notauthenticated",
        "login rejected",
        "login failed",
    )

    /** 应停止重连的鉴权类失败。 */
    fun isAuthFailure(message: String?): Boolean {
        val lower = message?.lowercase() ?: return false
        return authMarkers.any { it in lower }
    }
}
