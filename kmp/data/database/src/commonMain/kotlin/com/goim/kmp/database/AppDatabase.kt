package com.goim.kmp.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RenameColumn
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.AutoMigrationSpec
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
        ProfileEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AppDatabase.NicknameMigration::class),
        // 个性签名、好友备注
        AutoMigration(from = 2, to = 3),
    ],
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun groupDao(): GroupDao
    abstract fun friendDao(): FriendDao
    abstract fun metaDao(): MetaDao
    abstract fun profileDao(): ProfileDao

    /** 1.3.0：username → nickname，新增头像列与资料缓存表。 */
    @RenameColumn(tableName = "friends", fromColumnName = "username", toColumnName = "nickname")
    @RenameColumn(tableName = "friend_requests", fromColumnName = "username", toColumnName = "nickname")
    class NicknameMigration : AutoMigrationSpec

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
