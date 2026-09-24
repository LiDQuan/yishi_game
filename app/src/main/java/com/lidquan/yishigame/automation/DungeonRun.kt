package com.lidquan.yishigame.automation

import com.lidquan.yishigame.vision.FreeAttemptState
import com.lidquan.yishigame.vision.ocr.CounterValue

enum class DungeonBusinessState {
    HOME, AREA_MAP, AREA_PICKER, DUNGEON_LIST, DUNGEON_DETAIL, BATTLE, WAIT_HOME, SUCCESS, FAILED,
}

enum class AutoBattleState { ON, OFF, MISSING, UNKNOWN }

data class DungeonObservation(
    val page: String,
    val freeAttemptState: FreeAttemptState = FreeAttemptState.UNKNOWN,
    val progress: CounterValue? = null,
    val autoBattleState: AutoBattleState = AutoBattleState.UNKNOWN,
)

data class DungeonRunStatus(
    val state: DungeonBusinessState = DungeonBusinessState.HOME,
    val lastProgress: CounterValue? = null,
    val errorCode: String? = null,
)

object DungeonRunReducer {
    fun observe(status: DungeonRunStatus, observation: DungeonObservation): DungeonRunStatus = when {
        status.state == DungeonBusinessState.DUNGEON_DETAIL && observation.freeAttemptState != FreeAttemptState.AVAILABLE ->
            status.copy(state = DungeonBusinessState.FAILED, errorCode = "FREE_ATTEMPT_UNCONFIRMED")
        status.state == DungeonBusinessState.BATTLE && observation.autoBattleState == AutoBattleState.MISSING ->
            status.copy(state = DungeonBusinessState.FAILED, errorCode = "MANUAL_BATTLE_REQUIRED")
        status.state == DungeonBusinessState.BATTLE && observation.progress != null -> {
            val progress = observation.progress
            if (progress.current == progress.total) status.copy(state = DungeonBusinessState.WAIT_HOME, lastProgress = progress)
            else status.copy(lastProgress = progress)
        }
        status.state in setOf(DungeonBusinessState.BATTLE, DungeonBusinessState.WAIT_HOME) && observation.page == "HOME" -> {
            val complete = status.lastProgress?.let { it.current == it.total } == true
            if (complete) status.copy(state = DungeonBusinessState.SUCCESS)
            else status.copy(state = DungeonBusinessState.FAILED, errorCode = "DUNGEON_INCOMPLETE")
        }
        else -> status
    }
}
