package com.goim.kmp.database

import androidx.room.Room
import androidx.room.RoomDatabase
import com.goim.kmp.common.PlatformContext

actual fun databaseBuilder(context: PlatformContext, path: String): RoomDatabase.Builder<AppDatabase> =
    Room.databaseBuilder<AppDatabase>(context.applicationContext, path)
