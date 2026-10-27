package com.goim.kmp.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PresenceRepositoryImplTest {
    @Test
    fun pollsWatchedUidsWhileConnected() = runTest {
        val online = MutableStateFlow(setOf("bob"))
        val queries = mutableListOf<Set<String>>()
        val connected = MutableStateFlow(false)
        val repo = PresenceRepositoryImpl(
            fetch = { uids -> queries += uids.toSet(); online.value.intersect(uids.toSet()) },
            connected = connected,
            scope = backgroundScope,
            intervalMillis = 1_000,
        )
        val bob = mutableListOf<Boolean>()
        val carol = mutableListOf<Boolean>()
        backgroundScope.launch { repo.observe("bob").collect { bob += it } }
        backgroundScope.launch { repo.observe("carol").collect { carol += it } }
        advanceTimeBy(500)
        runCurrent()
        assertEquals(emptyList(), queries, "no polling while disconnected")

        connected.value = true
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(setOf("bob", "carol")), queries)
        assertEquals(listOf(false, true), bob)
        assertEquals(listOf(false), carol)

        online.value = setOf("carol")
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, queries.size)
        assertEquals(listOf(false, true, false), bob)
        assertEquals(listOf(false, true), carol)
    }

    @Test
    fun newWatcherTriggersImmediateFetch() = runTest {
        val queries = mutableListOf<Set<String>>()
        val repo = PresenceRepositoryImpl(
            fetch = { uids -> queries += uids.toSet(); uids.toSet() },
            connected = MutableStateFlow(true),
            scope = backgroundScope,
            intervalMillis = 60_000,
        )
        backgroundScope.launch { repo.observe("bob").collect {} }
        advanceTimeBy(100)
        runCurrent()
        val job = backgroundScope.launch { repo.observe("dave").collect {} }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(setOf("bob"), setOf("bob", "dave")), queries)

        job.cancel()
        advanceTimeBy(60_100)
        runCurrent()
        assertEquals(setOf("bob"), queries.last())
    }
}
