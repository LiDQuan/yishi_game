package com.lidquan.yishigame.action

import com.lidquan.yishigame.accessibility.AccessibilityController

data class ActionExecution(
    val decision: GuardDecision,
    val tapResult: Boolean? = null,
    val pageAfter: String? = null,
    val postconditionMet: Boolean = false,
    val durationMs: Long = 0,
)

/** The only production path from a business intent to a game tap. */
class ActionExecutor(
    private val controller: AccessibilityController,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        intent: ActionIntent,
        context: ActionContext,
        awaitPage: suspend (expected: Set<String>, timeoutMs: Long) -> String?,
    ): ActionExecution {
        val startedAt = clock()
        val decision = ActionGuard.plan(intent, context)
        if (decision !is GuardDecision.AllowDryRun) {
            return ActionExecution(decision = decision, durationMs = (clock() - startedAt).coerceAtLeast(0))
        }
        val result = runCatching {
            controller.tap(decision.plan.tapPoint.x.toFloat(), decision.plan.tapPoint.y.toFloat())
        }.getOrDefault(false)
        val pageAfter = if (result) awaitPage(decision.plan.expectedPageAfter, decision.plan.timeoutMs) else null
        return ActionExecution(
            decision = decision,
            tapResult = result,
            pageAfter = pageAfter,
            postconditionMet = pageAfter in decision.plan.expectedPageAfter,
            durationMs = (clock() - startedAt).coerceAtLeast(0),
        )
    }
}
