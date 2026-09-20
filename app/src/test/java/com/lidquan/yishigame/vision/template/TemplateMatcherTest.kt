package com.lidquan.yishigame.vision.template

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import com.lidquan.yishigame.viewport.NormalizedRect
import com.lidquan.yishigame.vision.PageDetection
import com.lidquan.yishigame.vision.page.PageDefinition
import com.lidquan.yishigame.vision.page.PageDetector
import com.lidquan.yishigame.vision.page.SignalRule
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


    @Test fun `definition roi threshold and negative evidence feed page detector`() {
        val frame = ScreenFrameSnapshot(1, 2, 1, 8, 4, 1, byteArrayOf(1, 2, 3, 4, 9, 8, 7, 6))
        val viewport = WindowBounds(0, 0, 2, 1)
        val required = TemplateDefinition("required", 1, "PAGE", NormalizedRect(0f, 0f, .5f, 1f), .99f)
        val negative = TemplateDefinition("negative", 1, "PAGE", NormalizedRect(.5f, 0f, 1f, 1f), .99f, negative = true)
        val requiredEvidence = TemplateMatcher.match(required, frame, viewport, RgbaTemplate(1, 1, byteArrayOf(1, 2, 3, 4)))!!
        val negativeEvidence = TemplateMatcher.match(negative, frame, viewport, RgbaTemplate(1, 1, byteArrayOf(9, 8, 7, 6)))!!
        val detector = PageDetector(listOf(PageDefinition("PAGE", 1, listOf(SignalRule("required", .99f)), negative = listOf(SignalRule("negative", .99f)), threshold = .99f, minimumMargin = .01f)))
        assertEquals("PAGE", (detector.detect(listOf(requiredEvidence)) as PageDetection.Matched).pageId)
        assertTrue(detector.detect(listOf(requiredEvidence, negativeEvidence)) is PageDetection.Unknown)
    }
}
