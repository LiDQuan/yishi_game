package com.lidquan.yishigame.action

import com.lidquan.yishigame.automation.AutomationState
import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.automation.WindowGate
import com.lidquan.yishigame.capture.ScreenCaptureState
import com.lidquan.yishigame.viewport.ScreenPoint
import com.lidquan.yishigame.vision.FreeAttemptState
import com.lidquan.yishigame.vision.StablePage

enum class ActionType { OPEN_SETTINGS, RETURN_CHARACTER_SELECT, SELECT_ROLE, OPEN_DUNGEON, START_DUNGEON_FREE, CLOSE_SAFE_DIALOG, PURCHASE }
enum class RiskLevel { SAFE, NORMAL, SENSITIVE, FORBIDDEN_AUTO }
enum class GuardDenyReason { AUTOMATION_NOT_RUNNING, ACCESSIBILITY_UNAVAILABLE, CAPTURE_INACTIVE, TARGET_NOT_VISIBLE, TARGET_NOT_ACTIVE, WINDOW_NOT_MATCHED, VIEWPORT_INVALID, PAGE_NOT_STABLE, PAGE_NOT_ALLOWED, TARGET_NOT_CONFIRMED, POSTCONDITION_MISSING, FREE_STATE_NOT_CONFIRMED, DAILY_ALREADY_SUCCESS, PURCHASE_SIGNAL_PRESENT, FORBIDDEN_AUTO }

data class ActionIntent(val type: ActionType, val targetId: String? = null, val riskLevel: RiskLevel)
data class ActionPlan(
    val intent: ActionIntent,
    val targetRect: WindowBounds,
    val tapPoint: ScreenPoint,
    val expectedPageBefore: String,
    val expectedPageAfter: Set<String>,
    val timeoutMs: Long,
)

data class ActionContext(
    val automationState: AutomationState,
    val accessibilityConnected: Boolean,
    val captureState: ScreenCaptureState,
    val targetVisible: Boolean,
    val targetActive: Boolean,
    val windowGate: WindowGate,
    val viewportValid: Boolean,
    val stablePage: StablePage?,
    val allowedPages: Set<String>,
    val targetRect: WindowBounds?,
    val expectedPagesAfter: Set<String>,
    val freeAttemptState: FreeAttemptState = FreeAttemptState.UNKNOWN,
    val dailyAlreadySuccess: Boolean = false,
    val purchaseSignalPresent: Boolean = false,
)

sealed interface GuardDecision {
    data class AllowDryRun(val plan: ActionPlan, val evidences: List<String>) : GuardDecision
    data class Deny(val reason: GuardDenyReason, val evidences: List<String>) : GuardDecision
}

object ActionGuard {
    fun plan(intent: ActionIntent, context: ActionContext): GuardDecision {
        fun deny(reason: GuardDenyReason) = GuardDecision.Deny(reason, listOf(reason.name))
        if (intent.riskLevel == RiskLevel.FORBIDDEN_AUTO || intent.type == ActionType.PURCHASE) return deny(GuardDenyReason.FORBIDDEN_AUTO)
        if (context.automationState != AutomationState.RUNNING_PLACEHOLDER) return deny(GuardDenyReason.AUTOMATION_NOT_RUNNING)
        if (!context.accessibilityConnected) return deny(GuardDenyReason.ACCESSIBILITY_UNAVAILABLE)
        if (context.captureState !is ScreenCaptureState.Active) return deny(GuardDenyReason.CAPTURE_INACTIVE)
        if (!context.targetVisible) return deny(GuardDenyReason.TARGET_NOT_VISIBLE)
        if (!context.targetActive) return deny(GuardDenyReason.TARGET_NOT_ACTIVE)
        if (context.windowGate != WindowGate.MATCHED) return deny(GuardDenyReason.WINDOW_NOT_MATCHED)
        if (!context.viewportValid) return deny(GuardDenyReason.VIEWPORT_INVALID)
        val page = context.stablePage ?: return deny(GuardDenyReason.PAGE_NOT_STABLE)
        if (page.pageId !in context.allowedPages) return deny(GuardDenyReason.PAGE_NOT_ALLOWED)
        val rect = context.targetRect ?: return deny(GuardDenyReason.TARGET_NOT_CONFIRMED)
        if (context.expectedPagesAfter.isEmpty()) return deny(GuardDenyReason.POSTCONDITION_MISSING)
        if (intent.riskLevel == RiskLevel.SENSITIVE) {
            if (context.freeAttemptState != FreeAttemptState.AVAILABLE) return deny(GuardDenyReason.FREE_STATE_NOT_CONFIRMED)
            if (context.dailyAlreadySuccess) return deny(GuardDenyReason.DAILY_ALREADY_SUCCESS)
            if (context.purchaseSignalPresent) return deny(GuardDenyReason.PURCHASE_SIGNAL_PRESENT)
        }
        val plan = ActionPlan(
            intent,
            rect,
            ScreenPoint((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2),
            page.pageId,
            context.expectedPagesAfter,
            5_000L,
        )
        return GuardDecision.AllowDryRun(plan, listOf("ALL_PRECONDITIONS_CONFIRMED", "DRY_RUN_ONLY"))
    }
}
