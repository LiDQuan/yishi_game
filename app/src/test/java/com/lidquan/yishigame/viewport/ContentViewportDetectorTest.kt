package com.lidquan.yishigame.viewport

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentViewportDetectorTest {
    @Test fun `excludes black side bars`() {
        val width = 1000
        val height = 600
        val bytes = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 300 until 700) {
            val offset = (y * width + x) * 4
            bytes[offset] = 80
            bytes[offset + 1] = 60
            bytes[offset + 2] = 40
            bytes[offset + 3] = -1
        }
        val frame = ScreenFrameSnapshot(1, width, height, width * 4, 4, 1, bytes)

        val viewport = ContentViewportDetector.detect(frame, WindowBounds(0, 0, width, height))

        assertEquals(WindowBounds(300, 0, 700, 600), viewport)
    }

    @Test fun `fails closed for an all black frame`() {
        val frame = ScreenFrameSnapshot(1, 800, 600, 3200, 4, 1, ByteArray(800 * 600 * 4))
        assertNull(ContentViewportDetector.detect(frame, WindowBounds(0, 0, 800, 600)))
    }
}
