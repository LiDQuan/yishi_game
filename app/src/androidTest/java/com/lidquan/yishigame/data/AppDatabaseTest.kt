package com.lidquan.yishigame.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun storesCoreEntitiesAndReadsDailyExecution() = runBlocking {
        val now = 1_700_000_000_000L
        database.roleDao().upsert(RoleEntity("role-1", "测试角色", createdAt = now, updatedAt = now))
        database.dungeonDao().upsert(
            DungeonEntity("dungeon-1", "测试副本", version = "fixture", riskLevel = "LOW", createdAt = now, updatedAt = now),
        )
        database.dailyExecutionDao().upsert(
            DailyExecutionEntity("execution-1", "2026-09-18", "role-1", "DUNGEON", "dungeon-1", "PENDING"),
        )
        database.errorEventDao().upsert(
            ErrorEventEntity("error-1", "session-1", now, "ERROR", errorCode = "TEST", message = "fixture"),
        )
        database.appSettingsDao().upsert(AppSettingsEntity())

        assertNotNull(database.roleDao().find("role-1"))
        assertNotNull(database.dungeonDao().find("dungeon-1"))
        assertNotNull(database.dailyExecutionDao().findDaily("2026-09-18", "role-1", "DUNGEON", "dungeon-1"))
        assertEquals(1, database.errorEventDao().observeRecent().first().size)
        assertEquals(1, database.appSettingsDao().get()?.id)
    }
}
