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
        contentViewport = WindowBounds(0, 0, 100, 100),
        stablePage = StablePage("DUNGEON_DETAIL", .96f, 1_000, 1),
        currentViewportVersion = 1,
        now = 1_500,
        actionTargets = mapOf("dungeon.start_free" to WindowBounds(10, 10, 20, 20)),
        freeAttemptState = FreeAttemptState.AVAILABLE,
    )

    @Test fun `central policy enforces sensitive gates and cannot be weakened by caller`() {
        val intent = ActionIntent(ActionType.START_DUNGEON_FREE)
        assertEquals(RiskLevel.SENSITIVE, ActionPolicyRegistry.policy(intent.type)?.riskLevel)
        assertTrue(ActionGuard.plan(intent, safe) is GuardDecision.AllowDryRun)
        assertEquals(GuardDenyReason.FREE_STATE_NOT_CONFIRMED, (ActionGuard.plan(intent, safe.copy(freeAttemptState = FreeAttemptState.UNKNOWN)) as GuardDecision.Deny).reason)
        assertEquals(GuardDenyReason.PAGE_NOT_ALLOWED, (ActionGuard.plan(intent, safe.copy(stablePage = safe.stablePage?.copy(pageId = "SETTINGS"))) as GuardDecision.Deny).reason)
        assertEquals(setOf("BATTLE", "LOADING"), (ActionGuard.plan(intent, safe) as GuardDecision.AllowDryRun).plan.expectedPageAfter)
    }

    @Test fun `forbidden auto is always denied by registry`() {
        assertEquals(RiskLevel.FORBIDDEN_AUTO, ActionPolicyRegistry.policy(ActionType.PURCHASE)?.riskLevel)
        val decision = ActionGuard.plan(ActionIntent(ActionType.PURCHASE), safe) as GuardDecision.Deny
        assertEquals(GuardDenyReason.FORBIDDEN_AUTO, decision.reason)
    }

    @Test fun `stale mismatched missing and outside target are denied`() {
        val intent = ActionIntent(ActionType.START_DUNGEON_FREE)
        assertEquals(GuardDenyReason.PAGE_VIEWPORT_MISMATCH, (ActionGuard.plan(intent, safe.copy(currentViewportVersion = 2)) as GuardDecision.Deny).reason)
        assertEquals(GuardDenyReason.PAGE_STALE, (ActionGuard.plan(intent, safe.copy(now = 4_000)) as GuardDecision.Deny).reason)
        assertEquals(GuardDenyReason.TARGET_NOT_CONFIRMED, (ActionGuard.plan(intent, safe.copy(actionTargets = emptyMap())) as GuardDecision.Deny).reason)
        val outside = safe.copy(actionTargets = mapOf("dungeon.start_free" to WindowBounds(90, 90, 110, 110)))
        assertEquals(GuardDenyReason.TARGET_OUTSIDE_VIEWPORT, (ActionGuard.plan(intent, outside) as GuardDecision.Deny).reason)
    }
}
