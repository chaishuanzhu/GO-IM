package com.goim.kmp.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsTest {
    private fun open(path: String) = Room.databaseBuilder<AppDatabase>(name = path)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

    @Test
    fun countsAndSnapshotIncludeUncheckpointedRows() = runBlocking {
        val dir = createTempDir()
        val db = open(File(dir, "goim.sqlite").path)
        db.metaDao().set(MetaEntity("k", "v"))
        db.messageDao().upsertMerged(
            MessageEntity(
                id = "1", serverMsgId = 1, clientSeq = 0, conversationId = "dm:a_b", fromUid = "a", toUid = "b",
                chatType = 1, msgType = 1, content = "hi", timestampMs = 1, status = 1, isOutgoing = true,
            ),
        )
        val counts = db.tableCounts().toMap()
        assertEquals(1L, counts["messages"])
        assertEquals(1L, counts["meta"])
        assertEquals(0L, counts["friends"])

        val snapshot = File(dir, "it's a copy.sqlite")
        db.exportSnapshot(snapshot.path)
        db.close()
        assertTrue(snapshot.length() > 0)

        val copy = open(snapshot.path)
        assertEquals(1L, copy.tableCounts().toMap()["messages"])
        copy.close()
        dir.deleteRecursively()
        Unit
    }

    @Suppress("DEPRECATION")
    private fun createTempDir(): File = kotlin.io.createTempDir("goim-diag")
}
