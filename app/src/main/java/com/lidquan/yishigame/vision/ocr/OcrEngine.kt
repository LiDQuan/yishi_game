package com.lidquan.yishigame.vision.ocr

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import com.lidquan.yishigame.vision.FreeAttemptState
import com.lidquan.yishigame.vision.OcrEvidence

fun normalizeOcrText(value: String): String = value
    .trim()
    .replace('（', '(')
    .replace('）', ')')
    .replace(Regex("\\s+"), "")

fun freeAttemptState(evidences: List<OcrEvidence>): FreeAttemptState = when {
    evidences.mapNotNull { parseCounter(it.normalizedText, "免费") }.any { it.current > 0 } -> FreeAttemptState.AVAILABLE
    evidences.mapNotNull { parseCounter(it.normalizedText, "免费") }.any { it.current == 0 } -> FreeAttemptState.EXHAUSTED
    else -> FreeAttemptState.UNKNOWN
}

data class CounterValue(val current: Int, val total: Int)

fun parseCounter(raw: String, prefix: String? = null): CounterValue? {
    val normalized = normalizeOcrText(raw)
    val pattern = if (prefix == null) Regex("(?:^|[^0-9])(\\d{1,3})/(\\d{1,3})(?:$|[^0-9])")
    else Regex("${Regex.escape(prefix)}\\(?(\\d{1,3})/(\\d{1,3})\\)?")
    val match = pattern.find(normalized) ?: return null
    val current = match.groupValues[1].toIntOrNull() ?: return null
    val total = match.groupValues[2].toIntOrNull() ?: return null
    return CounterValue(current, total).takeIf { total > 0 && current in 0..total }
}

fun interface OcrEngine {
    suspend fun recognize(frame: ScreenFrameSnapshot, roi: WindowBounds): List<OcrEvidence>
}

object NoOpOcrEngine : OcrEngine {
    override suspend fun recognize(frame: ScreenFrameSnapshot, roi: WindowBounds): List<OcrEvidence> = emptyList()
}
