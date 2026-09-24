package com.lidquan.yishigame.viewport

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot

/** Detects the rendered game surface and excludes black letterbox bars. */
object ContentViewportDetector {
    fun detect(frame: ScreenFrameSnapshot, window: WindowBounds): WindowBounds? {
        if (!window.isValid || window.right > frame.width || window.bottom > frame.height) return null
        val sampleRows = intArrayOf(0, window.height / 4, window.height / 2, window.height * 3 / 4, window.height - 1)
            .map { window.top + it.coerceIn(0, window.height - 1) }
        val activeColumns = BooleanArray(window.width)
        for (localX in activeColumns.indices step sampleStep) {
            val x = window.left + localX
            activeColumns[localX] = sampleRows.any { y -> !isNearBlack(frame, x, y) }
        }
        val first = activeColumns.indices.firstOrNull { activeColumns[it] } ?: return null
        val last = activeColumns.indices.lastOrNull { activeColumns[it] } ?: return null
        val left = window.left + (first / sampleStep) * sampleStep
        val right = (window.left + ((last / sampleStep) + 1) * sampleStep).coerceAtMost(window.right)
        return WindowBounds(left, window.top, right, window.bottom).takeIf {
            it.isValid && it.width >= minimumWidth && it.height >= minimumHeight
        }
    }

    private fun isNearBlack(frame: ScreenFrameSnapshot, x: Int, y: Int): Boolean {
        val offset = y * frame.rowStride + x * frame.pixelStride
        if (offset < 0 || offset + 2 >= frame.rgba.size) return true
        return (frame.rgba[offset].toInt() and 0xff) <= blackThreshold &&
            (frame.rgba[offset + 1].toInt() and 0xff) <= blackThreshold &&
            (frame.rgba[offset + 2].toInt() and 0xff) <= blackThreshold
    }

    private const val sampleStep = 4
    private const val blackThreshold = 12
    private const val minimumWidth = 320
    private const val minimumHeight = 480
}
