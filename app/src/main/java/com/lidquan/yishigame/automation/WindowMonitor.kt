package com.lidquan.yishigame.automation

data class WindowBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isValid: Boolean get() = width > 0 && height > 0
}

enum class WindowChange { FIRST_SAMPLE, UNCHANGED, CHANGED, UNAVAILABLE }

class WindowMonitor {
    private var lastBounds: WindowBounds? = null

    fun observe(bounds: WindowBounds?): WindowChange {
        if (bounds?.isValid != true) return WindowChange.UNAVAILABLE
        val previous = lastBounds
        lastBounds = bounds
        return when {
            previous == null -> WindowChange.FIRST_SAMPLE
            previous == bounds -> WindowChange.UNCHANGED
            else -> WindowChange.CHANGED
        }
    }

    fun clear() {
        lastBounds = null
    }
}
