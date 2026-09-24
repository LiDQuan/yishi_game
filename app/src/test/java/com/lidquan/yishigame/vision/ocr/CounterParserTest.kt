package com.lidquan.yishigame.vision.ocr

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.vision.FreeAttemptState
import com.lidquan.yishigame.vision.OcrEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CounterParserTest {
    @Test fun `free counter accepts arbitrary valid total`() {
        assertEquals(CounterValue(2, 2), parseCounter("免费（2 / 2）", "免费"))
        assertEquals(CounterValue(0, 3), parseCounter("免费(0/3)", "免费"))
        assertNull(parseCounter("免费(4/3)", "免费"))
    }

    @Test fun `free state is available exhausted or unknown`() {
        fun evidence(text: String) = OcrEvidence("free", text, normalizeOcrText(text), .9f, WindowBounds(0, 0, 1, 1))
        assertEquals(FreeAttemptState.AVAILABLE, freeAttemptState(listOf(evidence("免费(2/2)"))))
        assertEquals(FreeAttemptState.EXHAUSTED, freeAttemptState(listOf(evidence("免费(0/2)"))))
        assertEquals(FreeAttemptState.UNKNOWN, freeAttemptState(listOf(evidence("前往"))))
    }

    @Test fun `progress total is never fixed to five`() {
        assertEquals(CounterValue(4, 4), parseCounter("4/4"))
        assertEquals(CounterValue(5, 5), parseCounter("进度 5/5"))
    }
}
