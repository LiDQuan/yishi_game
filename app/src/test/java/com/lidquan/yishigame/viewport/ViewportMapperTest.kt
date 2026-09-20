package com.lidquan.yishigame.viewport

import com.lidquan.yishigame.automation.WindowBounds
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewportMapperTest {
    @Test fun `normalized roi follows viewport position and size`() {
        val roi = NormalizedRect(.25f, .2f, .75f, .8f)
        assertEquals(WindowBounds(150, 90, 250, 210), ViewportMapper.toScreen(roi, WindowBounds(100, 50, 300, 250)))
        assertEquals(WindowBounds(300, 180, 500, 420), ViewportMapper.toScreen(roi, WindowBounds(200, 100, 600, 500)))
    }
}
