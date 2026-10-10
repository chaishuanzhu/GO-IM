package com.goim.kmp.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 外发消息 ACK 超时重发（与 iOS `OutboundAckTracker` 同参数：3s × 3 次）。
 *
 * [resend] 以同一 clientSeq 重新写出，返回 false 表示写不出去（立即判失败）。超时用尽后回调 [onGiveUp]。
 */
class OutboundAckTracker(
    private val scope: CoroutineScope,
    private val timeoutMillis: Long = 3_000,
    private val maxRetries: Int = 3,
    private val resend: suspend (clientSeq: Long) -> Boolean,
    private val onGiveUp: suspend (clientSeq: Long) -> Unit,
) {
    private val lock = Mutex()
    private val pending = mutableMapOf<Long, Job>()

    suspend fun track(clientSeq: Long) {
        lock.withLock {
            pending.remove(clientSeq)?.cancel()
            pending[clientSeq] = scope.launch { watch(clientSeq) }
        }
    }

    /** 收到 ACK（或已判定终态）：停止计时。返回是否在跟踪中。 */
    suspend fun acknowledge(clientSeq: Long): Boolean = lock.withLock {
        val job = pending.remove(clientSeq) ?: return@withLock false
        job.cancel()
        true
    }

    suspend fun cancelAll() {
        lock.withLock {
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    suspend fun pendingCount(): Int = lock.withLock { pending.size }

    private suspend fun watch(clientSeq: Long) {
        var retriesDone = 0
        while (true) {
            delay(timeoutMillis)
            if (retriesDone >= maxRetries || !resend(clientSeq)) break
            retriesDone++
        }
        val stillPending = lock.withLock { pending.remove(clientSeq) != null }
        if (stillPending) onGiveUp(clientSeq)
    }
}
