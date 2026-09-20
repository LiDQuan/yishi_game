package com.lidquan.yishigame.vision.template

import com.lidquan.yishigame.automation.WindowBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateMatcherTest {
    @Test fun `template confidence supports per-template threshold`() {
        val rect = WindowBounds(0, 0, 1, 1)
        val template = RgbaTemplate(1, 1, byteArrayOf(0, 10, 20, 30))
        val exact = TemplateMatcher.compare("anchor", template, template, rect)!!
        val different = TemplateMatcher.compare("anchor", RgbaTemplate(1, 1, byteArrayOf(100, 110, 120, 127)), template, rect)!!
        assertEquals(1f, exact.confidence)
        assertTrue(different.confidence < .9f)
    }
}
