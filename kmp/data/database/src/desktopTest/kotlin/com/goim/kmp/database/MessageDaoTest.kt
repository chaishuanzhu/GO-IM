package com.goim.kmp.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageDaoTest {
    private lateinit var db: AppDatabase
    private val dao get() = db.messageDao()

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>()
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun msg(
        id: String,
        sid: Long? = null,
        seq: Long = 0,
        ts: Long = 1,
        outgoing: Boolean = false,
        status: Int = 1,
        content: String = "x",
    ) = MessageEntity(
        id = id, serverMsgId = sid, clientSeq = seq, conversationId = "dm:a_b",
        fromUid = if (outgoing) "a" else "b", toUid = if (outgoing) "b" else "a",
        chatType = 1, msgType = 1, content = content, timestampMs = ts, status = status, isOutgoing = outgoing,
    )

    @Test
    fun sameServerMsgIdIsIdempotent() = runTest {
        dao.upsertMerged(msg("1", sid = 100, content = "first"))
        dao.upsertMerged(msg("2", sid = 100, content = "again"))
        assertEquals(1, dao.count("dm:a_b"))
        assertEquals("again", dao.byServerMsgId(100)!!.content)
        assertEquals("1", dao.byServerMsgId(100)!!.id)
    }

    @Test
    fun echoOfOutgoingMergesByClientSeq() = runTest {
        dao.upsertMerged(msg("local", seq = 55, outgoing = true, status = 0))
        // 服务端回显（自己发出的消息从另一端同步回来）
        dao.upsertMerged(msg("remote", sid = 900, seq = 55, outgoing = true, status = 1))
        assertEquals(1, dao.count("dm:a_b"))
        val row = dao.byId("local")!!
        assertEquals(900L, row.serverMsgId)
        assertEquals(1, row.status)
    }

    @Test
    fun ackDropsDuplicateInboundRow() = runTest {
        dao.upsertMerged(msg("local", seq = 7, outgoing = true, status = 0))
        dao.upsertMerged(msg("dup", sid = 42, seq = 0, outgoing = false))
        dao.markStatusMerged(clientSeq = 7, status = 1, serverMsgId = 42)
        assertEquals(1, dao.count("dm:a_b"))
        assertNull(dao.byId("dup"))
        assertEquals(42L, dao.byId("local")!!.serverMsgId)
        assertEquals(1, dao.byId("local")!!.status)
    }

    @Test
    fun markStatusWithoutServerIdKeepsExisting() = runTest {
        dao.upsertMerged(msg("local", sid = 5, seq = 9, outgoing = true, status = 0))
        dao.markStatusMerged(clientSeq = 9, status = 2, serverMsgId = null)
        val row = dao.byId("local")!!
        assertEquals(5L, row.serverMsgId)
        assertEquals(2, row.status)
        assertEquals(listOf("local"), dao.outgoingByStatus(2).map { it.id })
    }

    @Test
    fun latestWindowAndPaging() = runTest {
        (1..10).forEach { dao.upsertMerged(msg("m$it", sid = it.toLong(), ts = it.toLong())) }
        assertEquals(listOf("m10", "m9", "m8"), dao.observeLatest("dm:a_b", 3).first().map { it.id })
        assertEquals(listOf("m4", "m3"), dao.page("dm:a_b", beforeMs = 5, limit = 2).map { it.id })
        assertEquals(1L, dao.oldestTimestamp("dm:a_b"))
    }

    @Test
    fun deleteConversationRemovesMessages() = runTest {
        dao.upsertMerged(msg("m1", sid = 1))
        db.conversationDao().upsert(
            ConversationEntity("dm:a_b", 1, "b", "b", "x", 1, 3),
        )
        db.conversationDao().delete("dm:a_b")
        assertEquals(0, dao.count("dm:a_b"))
        assertNull(db.conversationDao().byId("dm:a_b"))
        assertTrue(db.conversationDao().observeAll().first().isEmpty())
    }
}
