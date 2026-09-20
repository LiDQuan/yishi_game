package com.lidquan.yishigame.automation

data class WindowBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isValid: Boolean get() = width > 0 && height > 0
}

enum class WindowGate { NO_BASELINE, MATCHED, CHANGED, UNAVAILABLE }

class WindowMonitor {
    private var acceptedBounds: WindowBounds? = null
    private var lastGate: WindowGate? = null
    var version: Long = 0
        private set

    fun observe(bounds: WindowBounds?): WindowGate {
        val gate = when {
            bounds?.isValid != true -> WindowGate.UNAVAILABLE
            acceptedBounds == null -> WindowGate.NO_BASELINE
            bounds == acceptedBounds -> WindowGate.MATCHED
            else -> WindowGate.CHANGED
        }
        if (gate != lastGate && gate in setOf(WindowGate.CHANGED, WindowGate.UNAVAILABLE)) version += 1
        lastGate = gate
        return gate
    }

    fun accept(bounds: WindowBounds?): WindowGate {
        if (bounds?.isValid != true) return WindowGate.UNAVAILABLE
        acceptedBounds = bounds
        version += 1
        lastGate = WindowGate.MATCHED
        return WindowGate.MATCHED
    }

    fun clear() {
        acceptedBounds = null
        version += 1
        lastGate = null
    }
}
