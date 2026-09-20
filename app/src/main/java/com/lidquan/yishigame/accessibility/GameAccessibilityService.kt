package com.lidquan.yishigame.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.lidquan.yishigame.automation.WindowBounds

class GameAccessibilityService : AccessibilityService(), AccessibilityController {
    override fun onServiceConnected() {
        instance = this
        mutableConnected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { mutableLastEventPackage.value = it }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        mutableConnected.value = false
        super.onDestroy()
    }

    override fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(),
            null,
            null,
        )
    }

    override fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build(),
            null,
            null,
        )
    }

    override fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    override fun queryWindows(): List<AccessibleWindow> = windows.mapNotNull { window ->
        val rect = Rect()
        window.getBoundsInScreen(rect)
        val packageName = window.root?.packageName?.toString()
        if (rect.isEmpty) null else AccessibleWindow(
            packageName = packageName,
            bounds = WindowBounds(rect.left, rect.top, rect.right, rect.bottom),
            isActive = window.isActive,
            isFocused = window.isFocused,
            type = window.type,
            layer = window.layer,
        )
    }

    override fun activeWindowPackage(): String? = rootInActiveWindow?.packageName?.toString()

    companion object {
        @Volatile
        var instance: GameAccessibilityService? = null
            private set

        private val mutableConnected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

        private val mutableLastEventPackage = MutableStateFlow<String?>(null)
        val lastEventPackage: StateFlow<String?> = mutableLastEventPackage.asStateFlow()
    }
}
