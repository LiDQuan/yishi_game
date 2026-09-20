package com.lidquan.yishigame.vision.page

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.vision.PageDetection
import com.lidquan.yishigame.vision.TemplateEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDetectorTest {
    private val rect = WindowBounds(0, 0, 1, 1)
    private fun evidence(id: String, confidence: Float) = TemplateEvidence(id, confidence, rect)

    @Test fun `required optional negative unknown and ambiguous remain explicit`() {
        val detector = PageDetector(listOf(
            PageDefinition("MAIN", 1, listOf(SignalRule("main", .8f)), listOf(SignalRule("common")), listOf(SignalRule("purchase", .8f)), .8f, .05f),
            PageDefinition("SETTINGS", 1, listOf(SignalRule("settings", .8f)), threshold = .8f, minimumMargin = .05f),
        ))
        assertTrue(detector.detect(emptyList()) is PageDetection.Unknown)
        assertTrue(detector.detect(listOf(evidence("main", .95f), evidence("purchase", .9f))) is PageDetection.Unknown)
        assertTrue(detector.detect(listOf(evidence("main", .91f), evidence("settings", .89f))) is PageDetection.Ambiguous)
        assertEquals("MAIN", (detector.detect(listOf(evidence("main", .95f))) as PageDetection.Matched).pageId)
    }

    @Test fun `stable page needs repeated matches and resets on viewport version`() {
        val tracker = StablePageTracker(3, 2)
        val match = PageDetection.Matched("MAIN", .95f, emptyList())
        assertNull(tracker.add(match, 1, 1))
        assertEquals("MAIN", tracker.add(match, 1, 2)?.pageId)
        assertNull(tracker.add(match, 2, 3))
    }
}
