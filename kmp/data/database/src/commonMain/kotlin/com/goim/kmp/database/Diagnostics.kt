package com.goim.kmp.database

import androidx.room.execSQL
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection

/** 开发者工具：各表行数。 */
suspend fun AppDatabase.tableCounts(): List<Pair<String, Long>> = useReaderConnection { connection ->
    DIAGNOSTIC_TABLES.map { table ->
        table to connection.usePrepared("SELECT COUNT(*) FROM `$table`") { stmt ->
            if (stmt.step()) stmt.getLong(0) else 0L
        }
    }
}

/**
 * 导出一份一致的数据库快照到 [targetPath]（`VACUUM INTO`，含 WAL 中未落盘的数据）。
 * 目标文件必须不存在。
 */
suspend fun AppDatabase.exportSnapshot(targetPath: String) {
    useWriterConnection { connection ->
        connection.execSQL("VACUUM INTO '${targetPath.replace("'", "''")}'")
    }
}

private val DIAGNOSTIC_TABLES = listOf("messages", "conversations", "groups", "group_members", "friends", "friend_requests", "meta")
