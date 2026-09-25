package com.lidquan.yishigame.action

import com.lidquan.yishigame.accessibility.AccessibilityController
import com.lidquan.yishigame.accessibility.AccessibleWindow
import com.lidquan.yishigame.automation.AutomationState
import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.automation.WindowGate
import com.lidquan.yishigame.capture.ScreenCaptureState
import com.lidquan.yishigame.vision.FreeAttemptState
import com.lidquan.yishigame.vision.StablePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.test.runTest

class ActionExecutorTest {
    private class FakeController : AccessibilityController {
        var taps = 0
        override fun tap(x: Float, y: Float) = true.also { taps++ }
        override fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) = false
        override fun back() = false
        override fun queryWindows(): List<AccessibleWindow> = emptyList()
        override fun activeWindowPackage(): String? = null
    }

    @Test fun `executor taps only after guard allow and verifies postcondition`() = runTest {
        val controller = FakeController()
        val executor = ActionExecutor(controller) { 1_500 }
        val events = mutableListOf<String>()
        val context = ActionContext(
            AutomationState.RUNNING_PLACEHOLDER, true, ScreenCaptureState.Active(1, 1, 1), true, true,
            WindowGate.MATCHED, WindowBounds(0, 0, 100, 100), StablePage("DUNGEON_DETAIL", .9f, 1_000, 1),
            1, 1_500, actionTargets = mapOf("dungeon.start_free" to WindowBounds(10, 10, 20, 20)),
            freeAttemptState = FreeAttemptState.AVAILABLE,
        )
        val allowed = executor.execute(ActionIntent(ActionType.START_DUNGEON_FREE), context,
            onGuardDecision = { events += if (it is GuardDecision.AllowDryRun) "ALLOW" else "DENY" },
            onTapDispatched = { _, accepted -> events += "DISPATCH:$accepted" },
        ) { _, _ -> "BATTLE" }
        assertEquals(true, allowed.tapResult)
        assertEquals(true, allowed.postconditionMet)
        assertEquals(1, controller.taps)
        assertEquals(listOf("ALLOW", "DISPATCH:true"), events)
        assertNull(executor.execute(ActionIntent(ActionType.START_DUNGEON_FREE), context.copy(freeAttemptState = FreeAttemptState.UNKNOWN)) { _, _ -> "BATTLE" }.tapResult)
        assertEquals(1, controller.taps)
    }
}
