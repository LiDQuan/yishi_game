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
        automationState = AutomationState.RUNNING_PLACEHOLDER,
        accessibilityConnected = true,
        captureState = ScreenCaptureState.Active(1, 1, 1),
        targetVisible = true,
        targetActive = true,
        windowGate = WindowGate.MATCHED,
        viewportValid = true,
        stablePage = StablePage("DUNGEON_DETAIL", .96f, 1_000, 1),
        currentViewportVersion = 1,
        now = 1_500,
        allowedPages = setOf("DUNGEON_DETAIL"),
        targetRect = WindowBounds(10, 10, 20, 20),
        expectedPagesAfter = setOf("LOADING"),
        freeAttemptState = FreeAttemptState.AVAILABLE,
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

    @Test fun `stale or mismatched page is denied`() {
        val intent = ActionIntent(ActionType.START_DUNGEON_FREE, riskLevel = RiskLevel.SENSITIVE)
        assertEquals(GuardDenyReason.PAGE_VIEWPORT_MISMATCH, (ActionGuard.plan(intent, safe.copy(currentViewportVersion = 2)) as GuardDecision.Deny).reason)
        assertEquals(GuardDenyReason.PAGE_STALE, (ActionGuard.plan(intent, safe.copy(now = 4_000)) as GuardDecision.Deny).reason)
    }
}
