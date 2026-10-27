package com.goim.kmp.repository

import co.touchlab.kermit.Logger
import com.goim.kmp.domain.PresenceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 在线状态：界面订阅的 UID 按引用计数登记；连接在线时每 [intervalMillis] 批量查询一次 `/presence`，
 * 出现新 UID 时立即补查。断线期间保留最后一次结果。
 */
class PresenceRepositoryImpl(
    private val fetch: suspend (Collection<String>) -> Set<String>,
    private val connected: Flow<Boolean>,
    scope: CoroutineScope,
    private val intervalMillis: Long = 30_000L,
    private val batchWindowMillis: Long = 50,
) : PresenceRepository {
    private val log = Logger.withTag("Presence")
    private val watchers = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val online = MutableStateFlow<Set<String>>(emptySet())
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { pump() }
    }

    override fun observe(uid: String): Flow<Boolean> = flow {
        if (uid.isEmpty()) {
            emit(false)
            return@flow
        }
        watch(uid, 1)
        try {
            emitAll(online.map { uid in it }.distinctUntilChanged())
        } finally {
            watch(uid, -1)
        }
    }

    private fun watch(uid: String, delta: Int) {
        val before = watchers.value[uid] ?: 0
        watchers.update { m ->
            val n = (m[uid] ?: 0) + delta
            if (n <= 0) m - uid else m + (uid to n)
        }
        if (before == 0 && delta > 0) wake.trySend(Unit)
    }

    private suspend fun pump() {
        connected.distinctUntilChanged().collectLatest { up ->
            if (!up) return@collectLatest
            while (true) {
                delay(batchWindowMillis)
                wake.tryReceive()
                refresh()
                withTimeoutOrNull(intervalMillis) { wake.receive() }
            }
        }
    }

    private suspend fun refresh() {
        val uids = watchers.value.keys
        if (uids.isEmpty()) return
        try {
            online.value = uids.chunked(MAX_BATCH).flatMapTo(HashSet()) { fetch(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w { "presence for ${uids.size} users failed: ${e.message}" }
        }
    }

    private companion object {
        const val MAX_BATCH = 500
    }
}
