package com.lidquan.yishigame.vision.ocr

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.vision.FreeAttemptState
import com.lidquan.yishigame.vision.OcrEvidence
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrEngineTest {
    @Test fun `normalizes Chinese punctuation and confirms only exact free attempt`() {
        val rect = WindowBounds(0, 0, 1, 1)
        val normalized = normalizeOcrText(" 免费 （ 1/1 ） ")
        assertEquals("免费(1/1)", normalized)
        assertEquals(FreeAttemptState.AVAILABLE, freeAttemptState(listOf(OcrEvidence("free", "", normalized, .9f, rect))))
        assertEquals(FreeAttemptState.UNKNOWN, freeAttemptState(emptyList()))
        assertEquals(FreeAttemptState.UNKNOWN, freeAttemptState(listOf(OcrEvidence("free", "免费", "免费", .9f, rect))))
    }
}
