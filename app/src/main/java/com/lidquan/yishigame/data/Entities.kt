package com.lidquan.yishigame.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "roles")
data class RoleEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val classId: String? = null,
    val level: Int? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val nativeAutoPreference: String = "AUTO_DETECT",
    val battleStrategyId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "dungeons")
data class DungeonEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean = true,
    val version: String,
    val riskLevel: String,
    val templateSetId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "daily_executions",
    indices = [Index(value = ["gameDayKey", "roleId", "actionType", "targetId"], unique = true)],
)
data class DailyExecutionEntity(
    @PrimaryKey val id: String,
    val gameDayKey: String,
    val roleId: String,
    val actionType: String,
    val targetId: String,
    val status: String,
    val attemptCount: Int = 0,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val resultCode: String? = null,
    val errorCode: String? = null,
    val metadataJson: String? = null,
)

@Entity(tableName = "error_events")
data class ErrorEventEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val timestamp: Long,
    val state: String,
    val roleId: String? = null,
    val workType: String? = null,
    val targetId: String? = null,
    val pageId: String? = null,
    val pageConfidence: Double? = null,
    val lastAction: String? = null,
    val retryCount: Int = 0,
    val errorCode: String,
    val message: String,
    val localScreenshotRef: String? = null,
    val contextJson: String? = null,
    val resolvedAt: Long? = null,
    val resolution: String? = null,
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val standardViewportProfileId: String? = null,
    val ocrThreshold: Double = 0.80,
    val templateThreshold: Double = 0.85,
    val automationMode: String = "AUTO_DETECT",
    val logLevel: String = "INFO",
    val retentionDays: Int = 14,
)
