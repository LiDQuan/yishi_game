package com.lidquan.yishigame.vision

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import com.lidquan.yishigame.viewport.NormalizedRect
import com.lidquan.yishigame.vision.ocr.OcrEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun `recovery requires explicit dialog and exact action text`() = runBlocking {
        val config = initialVisionConfiguration()
        suspend fun detect(vararg texts: String): VisionAnalysis {
            val lines = texts.mapIndexed { index, value ->
                OcrEvidence("ocr.text", value, value, null, WindowBounds(20, 25 + index * 10, 80, 30 + index * 10))
            }
            return ConfiguredVisionEngine(OcrEngine { _, _ -> lines }, { viewport }, config).analyze(frame)
        }
        assertEquals("NETWORK_DISCONNECTED", (detect("服务器连接中断", "重新连接").detection as PageDetection.Matched).pageId)
        assertEquals("INVENTORY_FULL", (detect("背包已满", "自动出售").detection as PageDetection.Matched).pageId)
        assertEquals("NETWORK_DISCONNECTED", (detect("服务器连接中断", "确定").detection as PageDetection.Matched).pageId)
        assertEquals("NETWORK_DISCONNECTED", (detect("网络错误").detection as PageDetection.Matched).pageId)
        assertTrue(detect("网络错误", "确定").actionTargets.any { it.id == "network.reconnect" })
        assertTrue(detect("背包已满", "出售").actionTargets.none { it.id == "inventory.auto_sell" })
    }

    @Test fun `local OCR without confidence recognizes counters and separates home from battle`() = runBlocking {
        fun line(text: String, rect: WindowBounds) = OcrEvidence("ocr.text", text, text, null, rect)
        val counter = line("1/5", WindowBounds(40, 7, 60, 12))
        val settings = line("设置", WindowBounds(80, 10, 90, 16))
        val home = line("领取任务", WindowBounds(5, 18, 30, 22))
        suspend fun detect(lines: List<OcrEvidence>) = ConfiguredVisionEngine(
            OcrEngine { _, _ -> lines }, { viewport }, initialVisionConfiguration(),
        ).analyze(frame)
        assertEquals("BATTLE", (detect(listOf(counter, settings)).detection as PageDetection.Matched).pageId)
        assertEquals("HOME", (detect(listOf(counter, settings, home)).detection as PageDetection.Matched).pageId)
        val area = detect(listOf(counter, settings,
            line("切换区域", WindowBounds(10, 82, 30, 88)),
            line("地下城", WindowBounds(75, 82, 95, 88))))
        assertEquals("AREA_MAP", (area.detection as PageDetection.Matched).pageId)
        val badge = line("2", WindowBounds(36, 31, 40, 35))
        val title = line("地下城", WindowBounds(40, 15, 60, 19))
        val list = detect(listOf(badge, title))
        assertEquals("DUNGEON_LIST", (list.detection as PageDetection.Matched).pageId)
        assertTrue(list.actionTargets.any { it.id == "dungeon.candidate" })
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
