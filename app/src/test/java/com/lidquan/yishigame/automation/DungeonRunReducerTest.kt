package com.lidquan.yishigame.automation

import com.lidquan.yishigame.vision.FreeAttemptState
import com.lidquan.yishigame.vision.ocr.CounterValue
import org.junit.Assert.assertEquals
import org.junit.Test

class DungeonRunReducerTest {
    @Test fun `only total progress followed by home succeeds`() {
        var status = DungeonRunStatus(DungeonBusinessState.BATTLE)
        status = DungeonRunReducer.observe(status, DungeonObservation("BATTLE", progress = CounterValue(3, 4), autoBattleState = AutoBattleState.ON))
        assertEquals(DungeonBusinessState.BATTLE, status.state)
        status = DungeonRunReducer.observe(status, DungeonObservation("BATTLE", progress = CounterValue(4, 4), autoBattleState = AutoBattleState.ON))
        assertEquals(DungeonBusinessState.WAIT_HOME, status.state)
        status = DungeonRunReducer.observe(status, DungeonObservation("HOME"))
        assertEquals(DungeonBusinessState.SUCCESS, status.state)
    }

    @Test fun `early return and missing auto battle fail closed`() {
        val early = DungeonRunReducer.observe(
            DungeonRunStatus(DungeonBusinessState.BATTLE, CounterValue(3, 5)), DungeonObservation("HOME"),
        )
        assertEquals("DUNGEON_INCOMPLETE", early.errorCode)
        val missing = DungeonRunReducer.observe(
            DungeonRunStatus(DungeonBusinessState.BATTLE), DungeonObservation("BATTLE", autoBattleState = AutoBattleState.MISSING),
        )
        assertEquals("MANUAL_BATTLE_REQUIRED", missing.errorCode)
    }

    @Test fun `unknown free count never enters dungeon`() {
        val result = DungeonRunReducer.observe(
            DungeonRunStatus(DungeonBusinessState.DUNGEON_DETAIL), DungeonObservation("DUNGEON_DETAIL", FreeAttemptState.UNKNOWN),
        )
        assertEquals("FREE_ATTEMPT_UNCONFIRMED", result.errorCode)
    }
}
