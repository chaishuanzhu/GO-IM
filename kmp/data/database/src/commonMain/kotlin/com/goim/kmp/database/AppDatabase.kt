package com.goim.kmp.database

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.goim.kmp.common.PlatformContext
import com.goim.kmp.common.ioDispatcher

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        GroupEntity::class,
        GroupMemberEntity::class,
        FriendEntity::class,
        FriendRequestEntity::class,
        MetaEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun groupDao(): GroupDao
    abstract fun friendDao(): FriendDao
    abstract fun metaDao(): MetaDao

    companion object {
        const val FILE_NAME = "goim.sqlite"
    }
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/** [path] 为数据库文件绝对路径（`Users/{uid}/db/goim.sqlite`）。 */
expect fun databaseBuilder(context: PlatformContext, path: String): RoomDatabase.Builder<AppDatabase>

fun buildDatabase(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(ioDispatcher)
        .build()
