package com.lidquan.yishigame.viewport

import com.lidquan.yishigame.automation.WindowBounds

data class NormalizedRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }
}

data class ScreenPoint(val x: Int, val y: Int)

data class WindowGeometry(
    val windowBounds: WindowBounds,
    val contentViewport: WindowBounds,
    val profileVersion: Long,
) {
    val isConfirmed: Boolean get() =
        profileVersion > 0 && windowBounds.isValid && contentViewport.isValid &&
            contentViewport.left >= windowBounds.left && contentViewport.top >= windowBounds.top &&
            contentViewport.right <= windowBounds.right && contentViewport.bottom <= windowBounds.bottom
}

object ViewportMapper {
    fun toScreen(rect: NormalizedRect, viewport: WindowBounds): WindowBounds {
        require(viewport.isValid)
        return WindowBounds(
            viewport.left + (viewport.width * rect.left).toInt(),
            viewport.top + (viewport.height * rect.top).toInt(),
            viewport.left + (viewport.width * rect.right).toInt(),
            viewport.top + (viewport.height * rect.bottom).toInt(),
        )
    }

    fun pointToScreen(x: Float, y: Float, viewport: WindowBounds): ScreenPoint {
        require(x in 0f..1f && y in 0f..1f && viewport.isValid)
        return ScreenPoint(viewport.left + (viewport.width * x).toInt(), viewport.top + (viewport.height * y).toInt())
    }
}
