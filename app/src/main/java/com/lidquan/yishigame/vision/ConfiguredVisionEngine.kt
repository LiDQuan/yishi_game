package com.lidquan.yishigame.vision

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import com.lidquan.yishigame.viewport.NormalizedRect
import com.lidquan.yishigame.viewport.ViewportMapper
import com.lidquan.yishigame.vision.ocr.OcrEngine
import com.lidquan.yishigame.vision.ocr.freeAttemptState
import com.lidquan.yishigame.vision.page.PageDefinition
import com.lidquan.yishigame.vision.page.PageDetector
import com.lidquan.yishigame.vision.page.SignalRule
import com.lidquan.yishigame.vision.template.RgbaTemplate
import com.lidquan.yishigame.vision.template.TemplateDefinition
import com.lidquan.yishigame.vision.template.TemplateMatcher

data class OcrSignalDefinition(
    val id: String,
    val roi: NormalizedRect,
    val expectedText: String,
)

enum class SemanticOcrSignalType { FREE_ATTEMPT }

data class SemanticOcrSignalDefinition(
    val id: String,
    val roi: NormalizedRect,
    val type: SemanticOcrSignalType,
)

data class TemplateSignalDefinition(
    val definition: TemplateDefinition,
    val template: RgbaTemplate,
)

data class VisionConfiguration(
    val schemaVersion: Int,
    val templateSetVersion: String,
    val pageDefinitionVersion: String,
    val ocrSignals: List<OcrSignalDefinition>,
    val pages: List<PageDefinition>,
    val semanticOcrSignals: List<SemanticOcrSignalDefinition> = emptyList(),
    val templateSignals: List<TemplateSignalDefinition> = emptyList(),
)

class ConfiguredVisionEngine(
    private val ocr: OcrEngine,
    private val viewport: () -> WindowBounds?,
    private val configuration: VisionConfiguration = initialVisionConfiguration(),
) : AutoCloseable {
    private val detector = PageDetector(configuration.pages)

    suspend fun analyze(frame: ScreenFrameSnapshot): VisionAnalysis {
        val currentViewport = viewport()?.takeIf { it.isValid && it.right <= frame.width && it.bottom <= frame.height }
            ?: return VisionAnalysis(PageDetection.Unknown(emptyList()), FreeAttemptState.UNKNOWN)
        val rawOcr = mutableListOf<OcrEvidence>()
        var ocrNanos = 0L
        val evidence = configuration.ocrSignals.mapNotNull { signal ->
            val roi = ViewportMapper.toScreen(signal.roi, currentViewport)
            val ocrStarted = System.nanoTime()
            val lines = runCatching { ocr.recognize(frame, roi) }.getOrElse { emptyList() }
            ocrNanos += System.nanoTime() - ocrStarted
            rawOcr += lines
            val normalized = lines.joinToString(separator = "") { it.normalizedText }
            if (signal.expectedText in normalized) {
                OcrEvidence(signal.id, normalized, normalized, 0.9f, roi)
            } else {
                null
            }
        }.toMutableList<VisionEvidence>()
        configuration.semanticOcrSignals.forEach { signal ->
            val roi = ViewportMapper.toScreen(signal.roi, currentViewport)
            val started = System.nanoTime()
            rawOcr += runCatching { ocr.recognize(frame, roi) }.getOrElse { emptyList() }
            ocrNanos += System.nanoTime() - started
        }
        var templateNanos = 0L
        configuration.templateSignals.forEach { signal ->
            val started = System.nanoTime()
            TemplateMatcher.match(signal.definition, frame, currentViewport, signal.template)?.let(evidence::add)
            templateNanos += System.nanoTime() - started
        }
        val freeState = freeAttemptState(rawOcr)
        val detectorStarted = System.nanoTime()
        val detection = detector.detect(evidence)
        val detectorMs = (System.nanoTime() - detectorStarted) / 1_000_000L
        return VisionAnalysis(
            detection = detection,
            freeAttemptState = freeState,
            templateDurationMs = templateNanos / 1_000_000L,
            ocrDurationMs = ocrNanos / 1_000_000L,
            pageDetectorDurationMs = detectorMs,
        )
    }

    override fun close() {
        (ocr as? AutoCloseable)?.close()
    }
}

fun initialVisionConfiguration(): VisionConfiguration {
    val signals = listOf(
        OcrSignalDefinition("settings.title", NormalizedRect(0.25f, 0.05f, 0.75f, 0.15f), "设置"),
        OcrSignalDefinition("settings.anchor", NormalizedRect(0.15f, 0.47f, 0.85f, 0.56f), "自动回收"),
        OcrSignalDefinition("character.title", NormalizedRect(0.27f, 0.13f, 0.65f, 0.22f), "选择角色"),
        OcrSignalDefinition("character.anchor", NormalizedRect(0.28f, 0.42f, 0.62f, 0.52f), "进入游戏"),
        OcrSignalDefinition("dungeon.title", NormalizedRect(0.23f, 0.29f, 0.78f, 0.38f), "地下城"),
        OcrSignalDefinition("dungeon.anchor", NormalizedRect(0.32f, 0.64f, 0.68f, 0.73f), "切换区域"),
    )
    fun page(id: String, prefix: String) = PageDefinition(
        pageId = id,
        version = 1,
        required = listOf(SignalRule("$prefix.title", 0.8f), SignalRule("$prefix.anchor", 0.8f)),
        threshold = 0.85f,
        minimumMargin = 0.1f,
    )
    return VisionConfiguration(
        schemaVersion = 1,
        templateSetVersion = "m1-local-private-v1",
        pageDefinitionVersion = "m1-pages-v1",
        ocrSignals = signals,
        pages = listOf(page("SETTINGS", "settings"), page("CHARACTER_SELECT", "character"), page("DUNGEON_LIST", "dungeon")),
    )
}
