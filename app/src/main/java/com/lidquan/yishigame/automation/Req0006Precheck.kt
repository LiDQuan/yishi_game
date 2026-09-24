package com.lidquan.yishigame.automation

enum class Req0006PrecheckFailure {
    ACCESSIBILITY_UNAVAILABLE,
    CAPTURE_INACTIVE,
    GAME_WINDOW_MISSING,
    ASSISTANT_WINDOW_MISSING,
    WINDOW_NOT_MATCHED,
    CONTENT_VIEWPORT_UNCONFIRMED,
    LAYOUT_NOT_GAME_LEFT_ASSISTANT_RIGHT,
    GAME_PAGE_NOT_STABLE,
    GAME_NOT_AT_HOME,
}

data class Req0006PrecheckInput(
    val accessibilityEnabled: Boolean,
    val captureActive: Boolean,
    val gameWindow: WindowBounds?,
    val assistantWindow: WindowBounds?,
    val windowGate: WindowGate,
    val contentViewport: WindowBounds?,
    val stablePageId: String?,
)

sealed interface Req0006PrecheckResult {
    data object Ready : Req0006PrecheckResult
    data class Failed(val reason: Req0006PrecheckFailure) : Req0006PrecheckResult
}

object Req0006Precheck {
    private const val dividerTolerancePx = 24

    fun evaluate(input: Req0006PrecheckInput): Req0006PrecheckResult {
        if (!input.accessibilityEnabled) return failed(Req0006PrecheckFailure.ACCESSIBILITY_UNAVAILABLE)
        if (!input.captureActive) return failed(Req0006PrecheckFailure.CAPTURE_INACTIVE)
        val game = input.gameWindow?.takeIf { it.isValid }
            ?: return failed(Req0006PrecheckFailure.GAME_WINDOW_MISSING)
        val assistant = input.assistantWindow?.takeIf { it.isValid }
            ?: return failed(Req0006PrecheckFailure.ASSISTANT_WINDOW_MISSING)
        if (input.windowGate != WindowGate.MATCHED) return failed(Req0006PrecheckFailure.WINDOW_NOT_MATCHED)
        if (input.contentViewport?.isValid != true) return failed(Req0006PrecheckFailure.CONTENT_VIEWPORT_UNCONFIRMED)
        if (!isGameLeftAssistantRight(game, assistant)) {
            return failed(Req0006PrecheckFailure.LAYOUT_NOT_GAME_LEFT_ASSISTANT_RIGHT)
        }
        val page = input.stablePageId ?: return failed(Req0006PrecheckFailure.GAME_PAGE_NOT_STABLE)
        if (page != "HOME") return failed(Req0006PrecheckFailure.GAME_NOT_AT_HOME)
        return Req0006PrecheckResult.Ready
    }

    internal fun isGameLeftAssistantRight(game: WindowBounds, assistant: WindowBounds): Boolean {
        val gameCenterX = (game.left + game.right) / 2
        val assistantCenterX = (assistant.left + assistant.right) / 2
        val horizontalOrderOk = gameCenterX < assistantCenterX
        val sideBySideOk = game.right <= assistant.left + dividerTolerancePx
        val verticalOverlap = minOf(game.bottom, assistant.bottom) - maxOf(game.top, assistant.top)
        val minHeight = minOf(game.bottom - game.top, assistant.bottom - assistant.top)
        val sameRow = minHeight > 0 && verticalOverlap * 2 >= minHeight
        return horizontalOrderOk && sideBySideOk && sameRow
    }

    private fun failed(reason: Req0006PrecheckFailure) = Req0006PrecheckResult.Failed(reason)
}
