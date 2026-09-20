package com.lidquan.yishigame.action

import com.lidquan.yishigame.automation.AutomationState
import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.automation.WindowGate
import com.lidquan.yishigame.capture.ScreenCaptureState
import com.lidquan.yishigame.vision.FreeAttemptState
import com.lidquan.yishigame.vision.StablePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGuardTest {
    private val safe = ActionContext(
        AutomationState.RUNNING_PLACEHOLDER, true, ScreenCaptureState.Active(1, 1, 1), true, true,
        WindowGate.MATCHED, true, StablePage("DUNGEON_DETAIL", .96f, 1, 1), setOf("DUNGEON_DETAIL"),
        WindowBounds(10, 10, 20, 20), setOf("LOADING"), FreeAttemptState.AVAILABLE,
    )

    @Test fun `guard only allows dry run when every gate is confirmed`() {
        val intent = ActionIntent(ActionType.START_DUNGEON_FREE, riskLevel = RiskLevel.SENSITIVE)
        assertTrue(ActionGuard.plan(intent, safe) is GuardDecision.AllowDryRun)
        assertEquals(GuardDenyReason.TARGET_NOT_ACTIVE, (ActionGuard.plan(intent, safe.copy(targetActive = false)) as GuardDecision.Deny).reason)
        assertEquals(GuardDenyReason.WINDOW_NOT_MATCHED, (ActionGuard.plan(intent, safe.copy(windowGate = WindowGate.CHANGED)) as GuardDecision.Deny).reason)
        assertEquals(GuardDenyReason.CAPTURE_INACTIVE, (ActionGuard.plan(intent, safe.copy(captureState = ScreenCaptureState.Stopped("test"))) as GuardDecision.Deny).reason)
        assertEquals(GuardDenyReason.FREE_STATE_NOT_CONFIRMED, (ActionGuard.plan(intent, safe.copy(freeAttemptState = FreeAttemptState.UNKNOWN)) as GuardDecision.Deny).reason)
    }

    @Test fun `forbidden auto is always denied`() {
        val decision = ActionGuard.plan(ActionIntent(ActionType.PURCHASE, riskLevel = RiskLevel.FORBIDDEN_AUTO), safe) as GuardDecision.Deny
        assertEquals(GuardDenyReason.FORBIDDEN_AUTO, decision.reason)
    }
}
