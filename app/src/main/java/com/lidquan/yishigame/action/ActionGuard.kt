package com.lidquan.yishigame.action

import com.lidquan.yishigame.automation.AutomationState
import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.automation.WindowGate
import com.lidquan.yishigame.capture.ScreenCaptureState
import com.lidquan.yishigame.viewport.ScreenPoint
import com.lidquan.yishigame.vision.FreeAttemptState
import com.lidquan.yishigame.vision.StablePage

enum class ActionType {
    OPEN_SETTINGS, RETURN_CHARACTER_SELECT, SELECT_ROLE, ENTER_GAME,
    OPEN_DUNGEON,
    OPEN_AREA_MAP, OPEN_AREA_SWITCH, SELECT_AREA, OPEN_DUNGEON_LIST,
    SELECT_DUNGEON, START_DUNGEON_FREE, ENABLE_AUTO_BATTLE,
    RECONNECT_GAME, AUTO_SELL_INVENTORY,
    CLOSE_SAFE_DIALOG, PURCHASE,
}
enum class RiskLevel { SAFE, NORMAL, SENSITIVE, FORBIDDEN_AUTO }
enum class GuardDenyReason { POLICY_MISSING, AUTOMATION_NOT_RUNNING, ACCESSIBILITY_UNAVAILABLE, CAPTURE_INACTIVE, TARGET_NOT_VISIBLE, TARGET_NOT_ACTIVE, WINDOW_NOT_MATCHED, VIEWPORT_INVALID, PAGE_NOT_STABLE, PAGE_VIEWPORT_MISMATCH, PAGE_STALE, PAGE_NOT_ALLOWED, TARGET_NOT_CONFIRMED, TARGET_OUTSIDE_VIEWPORT, FREE_STATE_NOT_CONFIRMED, DAILY_ALREADY_SUCCESS, PURCHASE_SIGNAL_PRESENT, FORBIDDEN_AUTO }

data class ActionPolicy(
    val riskLevel: RiskLevel,
    val allowedPages: Set<String>,
    val requiredTargetId: String,
    val expectedPagesAfter: Set<String>,
)

object ActionPolicyRegistry {
    private val policies = mapOf(
        ActionType.OPEN_SETTINGS to ActionPolicy(RiskLevel.SAFE, setOf("MAIN"), "main.settings", setOf("SETTINGS")),
        ActionType.RETURN_CHARACTER_SELECT to ActionPolicy(RiskLevel.NORMAL, setOf("SETTINGS"), "settings.return_character", setOf("CHARACTER_SELECT")),
        ActionType.SELECT_ROLE to ActionPolicy(RiskLevel.NORMAL, setOf("CHARACTER_SELECT"), "character.role", setOf("CHARACTER_SELECT")),
        ActionType.ENTER_GAME to ActionPolicy(RiskLevel.NORMAL, setOf("CHARACTER_SELECT"), "character.enter", setOf("HOME", "LOADING")),
        ActionType.OPEN_DUNGEON to ActionPolicy(RiskLevel.NORMAL, setOf("DUNGEON_LIST"), "dungeon.switch_region", setOf("DUNGEON_LIST")),
        ActionType.OPEN_AREA_MAP to ActionPolicy(RiskLevel.NORMAL, setOf("HOME"), "home.dungeon", setOf("AREA_MAP")),
        ActionType.OPEN_AREA_SWITCH to ActionPolicy(RiskLevel.NORMAL, setOf("AREA_MAP"), "area.switch", setOf("AREA_PICKER")),
        ActionType.SELECT_AREA to ActionPolicy(RiskLevel.NORMAL, setOf("AREA_PICKER"), "area.target", setOf("AREA_MAP")),
        ActionType.OPEN_DUNGEON_LIST to ActionPolicy(RiskLevel.NORMAL, setOf("AREA_MAP"), "area.dungeon", setOf("DUNGEON_LIST")),
        ActionType.SELECT_DUNGEON to ActionPolicy(RiskLevel.NORMAL, setOf("DUNGEON_LIST"), "dungeon.candidate", setOf("DUNGEON_DETAIL")),
        ActionType.START_DUNGEON_FREE to ActionPolicy(RiskLevel.SENSITIVE, setOf("DUNGEON_DETAIL"), "dungeon.start_free", setOf("BATTLE", "LOADING")),
        ActionType.ENABLE_AUTO_BATTLE to ActionPolicy(RiskLevel.NORMAL, setOf("BATTLE"), "battle.auto", setOf("BATTLE")),
        ActionType.RECONNECT_GAME to ActionPolicy(RiskLevel.NORMAL, setOf("NETWORK_DISCONNECTED"), "network.reconnect",
            setOf("HOME", "AREA_MAP", "AREA_PICKER", "DUNGEON_LIST", "DUNGEON_DETAIL", "BATTLE")),
        ActionType.AUTO_SELL_INVENTORY to ActionPolicy(RiskLevel.SENSITIVE, setOf("INVENTORY_FULL"), "inventory.auto_sell",
            setOf("HOME", "AREA_MAP", "AREA_PICKER", "DUNGEON_LIST", "DUNGEON_DETAIL", "BATTLE")),
        ActionType.CLOSE_SAFE_DIALOG to ActionPolicy(RiskLevel.SAFE, setOf("SAFE_DIALOG"), "dialog.close", setOf("CHARACTER_SELECT", "HOME", "AREA_MAP")),
        ActionType.PURCHASE to ActionPolicy(RiskLevel.FORBIDDEN_AUTO, emptySet(), "purchase.confirm", emptySet()),
    )

    fun policy(type: ActionType): ActionPolicy? = policies[type]
}

data class ActionIntent(val type: ActionType)
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
    val contentViewport: WindowBounds?,
    val stablePage: StablePage?,
    val currentViewportVersion: Long,
    val now: Long,
    val maxStablePageAgeMs: Long = 2_000L,
    val actionTargets: Map<String, WindowBounds>,
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
        val policy = ActionPolicyRegistry.policy(intent.type) ?: return deny(GuardDenyReason.POLICY_MISSING)
        if (policy.riskLevel == RiskLevel.FORBIDDEN_AUTO) return deny(GuardDenyReason.FORBIDDEN_AUTO)
        if (context.automationState != AutomationState.RUNNING_PLACEHOLDER) return deny(GuardDenyReason.AUTOMATION_NOT_RUNNING)
        if (!context.accessibilityConnected) return deny(GuardDenyReason.ACCESSIBILITY_UNAVAILABLE)
        if (context.captureState !is ScreenCaptureState.Active) return deny(GuardDenyReason.CAPTURE_INACTIVE)
        if (!context.targetVisible) return deny(GuardDenyReason.TARGET_NOT_VISIBLE)
        if (!context.targetActive) return deny(GuardDenyReason.TARGET_NOT_ACTIVE)
        if (context.windowGate != WindowGate.MATCHED) return deny(GuardDenyReason.WINDOW_NOT_MATCHED)
        val viewport = context.contentViewport?.takeIf { it.isValid } ?: return deny(GuardDenyReason.VIEWPORT_INVALID)
        val page = context.stablePage ?: return deny(GuardDenyReason.PAGE_NOT_STABLE)
        if (page.viewportVersion != context.currentViewportVersion) return deny(GuardDenyReason.PAGE_VIEWPORT_MISMATCH)
        if (context.now < page.observedAt || context.now - page.observedAt > context.maxStablePageAgeMs) return deny(GuardDenyReason.PAGE_STALE)
        if (page.pageId !in policy.allowedPages) return deny(GuardDenyReason.PAGE_NOT_ALLOWED)
        val rect = context.actionTargets[policy.requiredTargetId] ?: return deny(GuardDenyReason.TARGET_NOT_CONFIRMED)
        if (!viewport.contains(rect)) return deny(GuardDenyReason.TARGET_OUTSIDE_VIEWPORT)
        if (policy.riskLevel == RiskLevel.SENSITIVE) {
            if (intent.type == ActionType.START_DUNGEON_FREE && context.freeAttemptState != FreeAttemptState.AVAILABLE) return deny(GuardDenyReason.FREE_STATE_NOT_CONFIRMED)
            if (context.dailyAlreadySuccess) return deny(GuardDenyReason.DAILY_ALREADY_SUCCESS)
            if (context.purchaseSignalPresent) return deny(GuardDenyReason.PURCHASE_SIGNAL_PRESENT)
        }
        return GuardDecision.AllowDryRun(
            ActionPlan(
                intent,
                rect,
                ScreenPoint((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2),
                page.pageId,
                policy.expectedPagesAfter,
                15_000L,
            ),
            listOf("ALL_PRECONDITIONS_CONFIRMED"),
        )
    }

    private fun WindowBounds.contains(other: WindowBounds): Boolean =
        other.isValid && other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}
