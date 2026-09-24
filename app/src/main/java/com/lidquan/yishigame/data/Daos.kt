package com.lidquan.yishigame.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RoleDao {
    @Upsert suspend fun upsert(role: RoleEntity)
    @Query("SELECT * FROM roles ORDER BY sortOrder, id") fun observeAll(): Flow<List<RoleEntity>>
    @Query("SELECT * FROM roles WHERE id = :id") suspend fun find(id: String): RoleEntity?
    @Query("DELETE FROM roles WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface DungeonDao {
    @Upsert suspend fun upsert(dungeon: DungeonEntity)
    @Query("SELECT * FROM dungeons ORDER BY name") fun observeAll(): Flow<List<DungeonEntity>>
    @Query("SELECT * FROM dungeons WHERE id = :id") suspend fun find(id: String): DungeonEntity?
    @Query("DELETE FROM dungeons WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface DailyExecutionDao {
    @Upsert suspend fun upsert(execution: DailyExecutionEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(execution: DailyExecutionEntity): Long
    @Query("SELECT * FROM daily_executions WHERE gameDayKey = :gameDayKey ORDER BY startedAt")
    fun observeForDay(gameDayKey: String): Flow<List<DailyExecutionEntity>>
    @Query("SELECT * FROM daily_executions WHERE gameDayKey = :gameDayKey AND roleId = :roleId AND actionType = :actionType AND targetId = :targetId LIMIT 1")
    suspend fun findDaily(gameDayKey: String, roleId: String, actionType: String, targetId: String): DailyExecutionEntity?

    @Transaction
    suspend fun getOrCreateDailyExecution(execution: DailyExecutionEntity): DailyExecutionEntity {
        if (insertIfAbsent(execution) != -1L) return execution
        return requireNotNull(findDaily(execution.gameDayKey, execution.roleId, execution.actionType, execution.targetId))
    }
}

@Dao
interface ErrorEventDao {
    @Upsert suspend fun upsert(event: ErrorEventEntity)
    @Query("SELECT * FROM error_events ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ErrorEventEntity>>
}

@Dao
interface AppSettingsDao {
    @Upsert suspend fun upsert(settings: AppSettingsEntity)
    @Query("SELECT * FROM app_settings WHERE id = 1") fun observe(): Flow<AppSettingsEntity?>
    @Query("SELECT * FROM app_settings WHERE id = 1") suspend fun get(): AppSettingsEntity?
}
