package com.lidquan.yishigame.vision

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import com.lidquan.yishigame.viewport.NormalizedRect
import com.lidquan.yishigame.vision.ocr.OcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfiguredVisionEngineTest {
    private val viewport = WindowBounds(0, 0, 100, 100)
    private val frame = ScreenFrameSnapshot(1, 100, 100, 400, 4, 1, ByteArray(40_000))
    private val free = OcrEvidence("ocr.text", "免费(1/1)", "免费(1/1)", .9f, WindowBounds(12, 12, 24, 20))

    @Test fun `free attempt only consumes explicit free semantic signal`() = runBlocking {
        assertEquals(FreeAttemptState.UNKNOWN, analyze(listOf(OcrSignalDefinition("page", full, "免费(1/1)")), emptyList(), listOf(listOf(free))).freeAttemptState)
        assertEquals(FreeAttemptState.AVAILABLE, analyze(emptyList(), listOf(SemanticOcrSignalDefinition("free", full, SemanticOcrSignalType.FREE_ATTEMPT)), listOf(listOf(free))).freeAttemptState)
        assertEquals(FreeAttemptState.UNKNOWN, analyze(emptyList(), listOf(SemanticOcrSignalDefinition("free", full, SemanticOcrSignalType.FREE_ATTEMPT)), listOf(emptyList())).freeAttemptState)
        assertEquals(FreeAttemptState.UNKNOWN, analyze(emptyList(), listOf(SemanticOcrSignalDefinition("generic", full, SemanticOcrSignalType.GENERIC)), listOf(listOf(free))).freeAttemptState)
    }

    @Test fun `action target uses actual OCR line rect instead of search roi`() = runBlocking {
        val result = analyze(
            listOf(OcrSignalDefinition("page.anchor", full, "切换区域", "real.target")),
            emptyList(),
            listOf(listOf(free.copy(text = "切换区域", normalizedText = "切换区域"))),
        )
        assertEquals(free.rect, result.actionTargets.single().rect)
    }

    private suspend fun analyze(
        pageSignals: List<OcrSignalDefinition>,
        semanticSignals: List<SemanticOcrSignalDefinition>,
        results: List<List<OcrEvidence>>,
    ): VisionAnalysis {
        var index = 0
        val ocr = OcrEngine { _, _ -> results.getOrElse(index++) { emptyList() } }
        return ConfiguredVisionEngine(
            ocr,
            { viewport },
            VisionConfiguration(1, "test", "test", pageSignals, emptyList(), semanticSignals),
        ).analyze(frame)
    }

    private companion object {
        val full = NormalizedRect(0f, 0f, 1f, 1f)
    }
}
