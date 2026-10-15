package com.goim.kmp.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {
    /** 按 schemas/1.json 建出 1.2.x 的库，再用当前版本打开，验证好友昵称保留、新列取默认值。 */
    @Test
    fun migratesFromVersion1() = runBlocking {
        val path = File(createTempDir(), "goim.sqlite").path
        BundledSQLiteDriver().open(path).apply {
            V1_SCHEMA.forEach { execSQL(it) }
            execSQL("INSERT INTO friends (uid, username) VALUES ('bob', 'Bob')")
            execSQL("INSERT INTO friend_requests (from_uid, to_uid, status, created_at, username) VALUES ('carol', 'me', 'pending', 5, 'Carol')")
            execSQL("PRAGMA user_version = 1")
            close()
        }

        val db = Room.databaseBuilder<AppDatabase>(name = path)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val friend = db.friendDao().observeFriends().first().single()
        assertEquals(FriendEntity("bob", "Bob", ""), friend)
        val request = db.friendDao().pending("me").single()
        assertEquals("Carol", request.nickname)
        assertEquals("", request.avatarUrl)

        db.profileDao().upsert(listOf(ProfileEntity("bob", "Bobby", "https://h/avatar/1", 9)))
        assertEquals("Bobby", db.profileDao().observe("bob").first()?.nickname)
        db.close()
    }

    private companion object {
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'e305d929d2a98cbb8f03d53b966adf80')",
            "CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `server_msg_id` INTEGER, `client_seq` INTEGER NOT NULL, `conversation_id` TEXT NOT NULL, `from_uid` TEXT NOT NULL, `to_uid` TEXT NOT NULL, `chat_type` INTEGER NOT NULL, `msg_type` INTEGER NOT NULL, `content` TEXT NOT NULL, `timestamp_ms` INTEGER NOT NULL, `status` INTEGER NOT NULL, `is_outgoing` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_server_msg_id` ON `messages` (`server_msg_id`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_conversation_id` ON `messages` (`conversation_id`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_timestamp_ms` ON `messages` (`timestamp_ms`)",
            "CREATE INDEX IF NOT EXISTS `index_messages_client_seq` ON `messages` (`client_seq`)",
            "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `chat_type` INTEGER NOT NULL, `title` TEXT NOT NULL, `peer_or_group_id` TEXT NOT NULL, `last_msg_preview` TEXT NOT NULL DEFAULT '', `last_msg_at` INTEGER NOT NULL DEFAULT 0, `unread_count` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `groups` (`group_id` TEXT NOT NULL, `name` TEXT NOT NULL, `owner_uid` TEXT NOT NULL, `member_count` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`group_id`))",
            "CREATE TABLE IF NOT EXISTS `group_members` (`group_id` TEXT NOT NULL, `uid` TEXT NOT NULL, `role` TEXT NOT NULL DEFAULT 'member', PRIMARY KEY(`group_id`, `uid`))",
            "CREATE TABLE IF NOT EXISTS `friends` (`uid` TEXT NOT NULL, `username` TEXT NOT NULL, PRIMARY KEY(`uid`))",
            "CREATE TABLE IF NOT EXISTS `friend_requests` (`from_uid` TEXT NOT NULL, `to_uid` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `username` TEXT, PRIMARY KEY(`from_uid`, `to_uid`))",
            "CREATE TABLE IF NOT EXISTS `meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))",
        )
    }
}
