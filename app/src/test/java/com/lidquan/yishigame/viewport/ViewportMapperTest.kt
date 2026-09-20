package com.lidquan.yishigame.viewport

import com.lidquan.yishigame.automation.WindowBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewportMapperTest {
    @Test fun `normalized roi follows viewport position and size`() {
        val roi = NormalizedRect(.25f, .2f, .75f, .8f)
        assertEquals(WindowBounds(150, 90, 250, 210), ViewportMapper.toScreen(roi, WindowBounds(100, 50, 300, 250)))
        assertEquals(WindowBounds(300, 180, 500, 420), ViewportMapper.toScreen(roi, WindowBounds(200, 100, 600, 500)))
    }


    @Test fun `content viewport remains separate from decorated window`() {
        val geometry = WindowGeometry(
            windowBounds = WindowBounds(90, 0, 1130, 1740),
            contentViewport = WindowBounds(100, 30, 1120, 1730),
            profileVersion = 1,
        )
        assertTrue(geometry.isConfirmed)
        assertEquals(
            WindowBounds(100, 30, 610, 880),
            ViewportMapper.toScreen(NormalizedRect(0f, 0f, .5f, .5f), geometry.contentViewport),
        )
    }
}
