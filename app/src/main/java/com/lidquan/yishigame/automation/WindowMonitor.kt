package com.lidquan.yishigame.automation

data class WindowBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isValid: Boolean get() = width > 0 && height > 0
}

enum class WindowGate { NO_BASELINE, MATCHED, CHANGED, UNAVAILABLE }

class WindowMonitor {
    private var acceptedBounds: WindowBounds? = null

    fun observe(bounds: WindowBounds?): WindowGate {
        if (bounds?.isValid != true) return WindowGate.UNAVAILABLE
        val baseline = acceptedBounds ?: return WindowGate.NO_BASELINE
        return if (bounds == baseline) WindowGate.MATCHED else WindowGate.CHANGED
    }

    fun accept(bounds: WindowBounds?): WindowGate {
        if (bounds?.isValid != true) return WindowGate.UNAVAILABLE
        acceptedBounds = bounds
        return WindowGate.MATCHED
    }

    fun clear() {
        acceptedBounds = null
    }
}
