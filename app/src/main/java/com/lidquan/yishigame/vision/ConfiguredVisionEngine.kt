package com.lidquan.yishigame.vision

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import com.lidquan.yishigame.viewport.NormalizedRect
import com.lidquan.yishigame.viewport.ViewportMapper
import com.lidquan.yishigame.vision.ocr.OcrEngine
import com.lidquan.yishigame.vision.ocr.freeAttemptState
import com.lidquan.yishigame.vision.ocr.parseCounter
import com.lidquan.yishigame.vision.ocr.CounterValue
import com.lidquan.yishigame.automation.AutoBattleState
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
    val actionTargetId: String? = null,
)

enum class SemanticOcrSignalType { FREE_ATTEMPT, PROGRESS, GENERIC }

data class SemanticOcrSignalDefinition(
    val id: String,
    val roi: NormalizedRect,
    val type: SemanticOcrSignalType,
)

data class RegexOcrSignalDefinition(
    val id: String,
    val roi: NormalizedRect,
    val pattern: Regex,
    val actionTargetId: String? = null,
    val actionTargetRoi: NormalizedRect? = null,
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
    val regexOcrSignals: List<RegexOcrSignalDefinition> = emptyList(),
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
        val freeAttemptOcr = mutableListOf<OcrEvidence>()
        val actionTargets = mutableListOf<ActionTargetEvidence>()
        var progress: CounterValue? = null
        var currentMap: String? = null
        var currentDungeon: String? = null
        var ocrNanos = 0L
        val ocrStarted = System.nanoTime()
        val allOcrLines = runCatching { ocr.recognize(frame, currentViewport) }.getOrElse { emptyList() }
        ocrNanos += System.nanoTime() - ocrStarted
        fun linesIn(roi: WindowBounds) = allOcrLines.filter { line ->
            val centerX = (line.rect.left + line.rect.right) / 2
            val centerY = (line.rect.top + line.rect.bottom) / 2
            centerX in roi.left..roi.right && centerY in roi.top..roi.bottom
        }
        val evidence = configuration.ocrSignals.mapNotNull { signal ->
            val roi = ViewportMapper.toScreen(signal.roi, currentViewport)
            val lines = linesIn(roi)
            val normalized = lines.joinToString(separator = "") { it.normalizedText }
            if (signal.expectedText in normalized) {
                if (signal.id.startsWith("map.name.")) currentMap = signal.expectedText
                if (signal.id.startsWith("detail.name.")) currentDungeon = signal.expectedText
                val matchingLine = lines.firstOrNull { signal.expectedText in it.normalizedText }
                if (matchingLine != null && signal.actionTargetId != null) {
                    actionTargets += ActionTargetEvidence(signal.actionTargetId, matchingLine.confidence, matchingLine.rect)
                }
                OcrEvidence(signal.id, normalized, normalized, 0.9f, matchingLine?.rect ?: roi)
            } else {
                null
            }
        }.toMutableList<VisionEvidence>()
        configuration.semanticOcrSignals.forEach { signal ->
            val roi = ViewportMapper.toScreen(signal.roi, currentViewport)
            val lines = linesIn(roi)
            if (signal.type == SemanticOcrSignalType.FREE_ATTEMPT) freeAttemptOcr += lines
            if (signal.type == SemanticOcrSignalType.PROGRESS) {
                progress = lines.asSequence().mapNotNull { parseCounter(it.normalizedText) }.firstOrNull()
            }
        }
        configuration.regexOcrSignals.forEach { signal ->
            val roi = ViewportMapper.toScreen(signal.roi, currentViewport)
            val lines = linesIn(roi)
            val match = lines.firstOrNull { signal.pattern.containsMatchIn(it.normalizedText) }
            if (match != null) {
                evidence += OcrEvidence(signal.id, match.text, match.normalizedText, match.confidence, match.rect)
                if (signal.actionTargetId != null) {
                    actionTargets += ActionTargetEvidence(
                        signal.actionTargetId,
                        match.confidence,
                        signal.actionTargetRoi?.let { ViewportMapper.toScreen(it, currentViewport) } ?: match.rect,
                    )
                }
            }
        }
        var templateNanos = 0L
        configuration.templateSignals.forEach { signal ->
            val started = System.nanoTime()
            TemplateMatcher.match(signal.definition, frame, currentViewport, signal.template)?.let(evidence::add)
            templateNanos += System.nanoTime() - started
        }
        val freeState = freeAttemptState(freeAttemptOcr)
        // The native auto-battle control sits on the mid-screen divider, not in the top progress bar.
        val autoRect = ViewportMapper.toScreen(NormalizedRect(0.47f, 0.30f, 0.54f, 0.38f), currentViewport)
        val autoState = detectAutoBattle(frame, autoRect)
        if (autoState == AutoBattleState.ON || autoState == AutoBattleState.OFF) {
            actionTargets += ActionTargetEvidence("battle.auto", 0.85f, autoRect)
        }
        val detectorStarted = System.nanoTime()
        val detection = detector.detect(evidence)
        val detectorMs = (System.nanoTime() - detectorStarted) / 1_000_000L
        return VisionAnalysis(
            detection = detection,
            freeAttemptState = freeState,
            templateDurationMs = templateNanos / 1_000_000L,
            ocrDurationMs = ocrNanos / 1_000_000L,
            pageDetectorDurationMs = detectorMs,
            actionTargets = actionTargets,
            progress = progress,
            autoBattleState = autoState,
            currentMap = currentMap,
            currentDungeon = currentDungeon,
            evidenceIds = evidence.map { it.id },
        )
    }

    override fun close() {
        (ocr as? AutoCloseable)?.close()
    }
}

private fun detectAutoBattle(frame: ScreenFrameSnapshot, rect: WindowBounds): AutoBattleState {
    var brightNeutral = 0
    for (y in rect.top until rect.bottom step 3) for (x in rect.left until rect.right step 3) {
        val offset = y * frame.rowStride + x * frame.pixelStride
        if (offset + 2 >= frame.rgba.size) continue
        val r = frame.rgba[offset].toInt() and 0xff
        val g = frame.rgba[offset + 1].toInt() and 0xff
        val b = frame.rgba[offset + 2].toInt() and 0xff
        if (r > 175 && g > 165 && b > 120 && maxOf(r, g, b) - minOf(r, g, b) < 70) brightNeutral++
    }
    return when {
        // A bright control can be observed in the provided ON frame. OFF and absent
        // are not safely separable from background without another live sample.
        brightNeutral >= 20 -> AutoBattleState.ON
        else -> AutoBattleState.UNKNOWN
    }
}

fun initialVisionConfiguration(): VisionConfiguration {
    val signals = listOf(
        OcrSignalDefinition("settings.title", NormalizedRect(0.25f, 0.05f, 0.75f, 0.15f), "设置"),
        OcrSignalDefinition("settings.anchor", NormalizedRect(0.15f, 0.47f, 0.85f, 0.56f), "自动回收"),
        OcrSignalDefinition("character.title", NormalizedRect(0.27f, 0.13f, 0.65f, 0.22f), "选择角色"),
        OcrSignalDefinition("character.anchor", NormalizedRect(0.30f, 0.25f, 0.70f, 0.36f), "进入游戏", actionTargetId = "character.enter"),
        OcrSignalDefinition("dialog.anchor", NormalizedRect(0.25f, 0.45f, 0.75f, 0.70f), "确定", actionTargetId = "dialog.close"),
        OcrSignalDefinition("home.title", NormalizedRect(0.00f, 0.05f, 0.42f, 0.28f), "领取任务"),
        OcrSignalDefinition("home.anchor", NormalizedRect(0.62f, 0.02f, 1.00f, 0.28f), "设置"),
        OcrSignalDefinition("area.title", NormalizedRect(0.00f, 0.78f, 0.55f, 1.00f), "切换区域", actionTargetId = "area.switch"),
        OcrSignalDefinition("area.anchor", NormalizedRect(0.55f, 0.70f, 1.00f, 1.00f), "地下城", actionTargetId = "area.dungeon"),
        OcrSignalDefinition("area_picker.title", NormalizedRect(0.25f, 0.20f, 0.78f, 0.82f), "亡灵之地"),
        OcrSignalDefinition("area_picker.anchor", NormalizedRect(0.25f, 0.20f, 0.78f, 0.82f), "亡灵之地", actionTargetId = "area.target"),
        OcrSignalDefinition("map.name.undead", NormalizedRect(0.25f, 0.08f, 0.75f, 0.25f), "亡灵之地"),
        OcrSignalDefinition("map.name.east", NormalizedRect(0.25f, 0.08f, 0.75f, 0.25f), "东部大陆"),
        OcrSignalDefinition("map.name.void", NormalizedRect(0.25f, 0.08f, 0.75f, 0.25f), "虚空领域"),
        OcrSignalDefinition("map.name.frozen", NormalizedRect(0.25f, 0.08f, 0.75f, 0.25f), "冰封大陆"),
        OcrSignalDefinition("map.name.element", NormalizedRect(0.25f, 0.08f, 0.75f, 0.25f), "元素之地"),
        OcrSignalDefinition("map.name.mist", NormalizedRect(0.25f, 0.08f, 0.75f, 0.25f), "迷雾大陆"),
        OcrSignalDefinition("map.name.shadow", NormalizedRect(0.25f, 0.08f, 0.75f, 0.25f), "暗影大陆"),
        OcrSignalDefinition("map.name.legion", NormalizedRect(0.25f, 0.08f, 0.75f, 0.25f), "军团领域"),
        OcrSignalDefinition("map.name.storm", NormalizedRect(0.25f, 0.08f, 0.75f, 0.25f), "风暴群岛"),
        OcrSignalDefinition("dungeon.title", NormalizedRect(0.15f, 0.00f, 0.85f, 0.30f), "地下城"),
        OcrSignalDefinition("detail.title", NormalizedRect(0.15f, 0.35f, 0.85f, 0.80f), "免费"),
        OcrSignalDefinition("detail.anchor", NormalizedRect(0.20f, 0.62f, 0.80f, 0.92f), "前往", actionTargetId = "dungeon.start_free"),
        OcrSignalDefinition("battle.anchor", NormalizedRect(0.50f, 0.00f, 1.00f, 0.25f), "设置"),
    )
    fun page(id: String, prefix: String, negative: List<SignalRule> = emptyList()) = PageDefinition(
        pageId = id,
        version = 1,
        required = listOf(SignalRule("$prefix.title", 0.8f), SignalRule("$prefix.anchor", 0.8f)),
        negative = negative,
        threshold = 0.85f,
        minimumMargin = 0.1f,
    )
    return VisionConfiguration(
        schemaVersion = 1,
        templateSetVersion = "m1-local-private-v1",
        pageDefinitionVersion = "m1-pages-v1",
        ocrSignals = signals,
        pages = listOf(
            page("SETTINGS", "settings"),
            page("CHARACTER_SELECT", "character"),
            page("SAFE_DIALOG", "dialog"),
            page("HOME", "home"),
            page("AREA_MAP", "area", negative = listOf(SignalRule("area_picker.anchor", 0.8f))),
            page("AREA_PICKER", "area_picker"),
            PageDefinition("DUNGEON_LIST", 1,
                required = listOf(SignalRule("dungeon.title", 0.8f), SignalRule("dungeon.badge.first", 0.8f)),
                threshold = 0.85f, minimumMargin = 0.1f),
            page("DUNGEON_DETAIL", "detail"),
            page("BATTLE", "battle"),
        ),
        semanticOcrSignals = listOf(
            SemanticOcrSignalDefinition("detail.free", NormalizedRect(0.20f, 0.52f, 0.80f, 0.85f), SemanticOcrSignalType.FREE_ATTEMPT),
            SemanticOcrSignalDefinition("battle.progress", NormalizedRect(0.25f, 0.00f, 0.75f, 0.20f), SemanticOcrSignalType.PROGRESS),
        ),
        regexOcrSignals = listOf(
            RegexOcrSignalDefinition("dialog.title", NormalizedRect(0.20f, 0.25f, 0.80f, 0.60f), Regex("更新|网络错误")),
            RegexOcrSignalDefinition("home.dungeon", NormalizedRect(0.20f, 0.00f, 0.80f, 0.16f), Regex("(?:^|[^0-9])\\d{1,2}/\\d{1,2}(?:$|[^0-9])"), "home.dungeon"),
            RegexOcrSignalDefinition(
                "dungeon.badge.first", NormalizedRect(0.34f, 0.30f, 0.42f, 0.38f),
                Regex("^[1-9]$"), "dungeon.candidate",
                NormalizedRect(0.23f, 0.32f, 0.39f, 0.40f),
            ),
            RegexOcrSignalDefinition("battle.title", NormalizedRect(0.20f, 0.00f, 0.80f, 0.18f), Regex("(?:^|[^0-9])\\d{1,2}/\\d{1,2}(?:$|[^0-9])")),
        ),
    )
}
