package com.lidquan.yishigame.accessibility

import com.lidquan.yishigame.automation.WindowBounds

data class AccessibleWindow(
    val packageName: String?,
    val bounds: WindowBounds,
)

interface AccessibilityController {
    fun tap(x: Float, y: Float): Boolean
    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean
    fun back(): Boolean
    fun queryWindows(): List<AccessibleWindow>
}
