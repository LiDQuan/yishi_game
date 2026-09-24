package com.lidquan.yishigame.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Req0006PrecheckTest {
    private val game = WindowBounds(0, 0, 1600, 1200)
    private val assistant = WindowBounds(1600, 0, 2560, 1200)
    private val viewport = WindowBounds(20, 20, 1580, 1180)

    private fun input(
        gameWindow: WindowBounds? = game,
        assistantWindow: WindowBounds? = assistant,
        stablePageId: String? = "HOME",
        windowGate: WindowGate = WindowGate.MATCHED,
    ) = Req0006PrecheckInput(
        accessibilityEnabled = true,
        captureActive = true,
        gameWindow = gameWindow,
        assistantWindow = assistantWindow,
        windowGate = windowGate,
        contentViewport = viewport,
        stablePageId = stablePageId,
    )

    @Test
    fun ready_whenGameIsLeftAssistantIsRightAndHomeIsStable() {
        assertTrue(Req0006Precheck.evaluate(input()) is Req0006PrecheckResult.Ready)
    }

    @Test
    fun blocks_whenAssistantWindowIsMissing() {
        val result = Req0006Precheck.evaluate(input(assistantWindow = null))
        assertEquals(
            Req0006PrecheckFailure.ASSISTANT_WINDOW_MISSING,
            (result as Req0006PrecheckResult.Failed).reason,
        )
    }

    @Test
    fun blocks_whenLayoutIsReversed() {
        val result = Req0006Precheck.evaluate(
            input(
                gameWindow = WindowBounds(1600, 0, 2560, 1200),
                assistantWindow = WindowBounds(0, 0, 1600, 1200),
            ),
        )
        assertEquals(
            Req0006PrecheckFailure.LAYOUT_NOT_GAME_LEFT_ASSISTANT_RIGHT,
            (result as Req0006PrecheckResult.Failed).reason,
        )
    }

    @Test
    fun blocks_whenGameIsNotAtStableHome() {
        val result = Req0006Precheck.evaluate(input(stablePageId = "AREA_MAP"))
        assertEquals(
            Req0006PrecheckFailure.GAME_NOT_AT_HOME,
            (result as Req0006PrecheckResult.Failed).reason,
        )
    }
}
