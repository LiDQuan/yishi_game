package com.lidquan.yishigame.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        RoleEntity::class,
        DungeonEntity::class,
        DailyExecutionEntity::class,
        ErrorEventEntity::class,
        AppSettingsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun roleDao(): RoleDao
    abstract fun dungeonDao(): DungeonDao
    abstract fun dailyExecutionDao(): DailyExecutionDao
    abstract fun errorEventDao(): ErrorEventDao
    abstract fun appSettingsDao(): AppSettingsDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "yishi-assistant.db",
            ).build().also { instance = it }
        }
    }
}
