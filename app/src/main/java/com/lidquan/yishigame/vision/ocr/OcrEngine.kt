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
    evidences.any { Regex("免费\\(1/1\\)").containsMatchIn(it.normalizedText) } -> FreeAttemptState.AVAILABLE
    else -> FreeAttemptState.UNKNOWN
}

fun interface OcrEngine {
    suspend fun recognize(frame: ScreenFrameSnapshot, roi: WindowBounds): List<OcrEvidence>
}

object NoOpOcrEngine : OcrEngine {
    override suspend fun recognize(frame: ScreenFrameSnapshot, roi: WindowBounds): List<OcrEvidence> = emptyList()
}
