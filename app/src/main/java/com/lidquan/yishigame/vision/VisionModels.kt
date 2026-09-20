package com.lidquan.yishigame.vision

import com.lidquan.yishigame.automation.WindowBounds

sealed interface VisionEvidence {
    val id: String
    val confidence: Float
    val rect: WindowBounds
}

data class TemplateEvidence(
    override val id: String,
    override val confidence: Float,
    override val rect: WindowBounds,
) : VisionEvidence

data class OcrEvidence(
    override val id: String,
    val text: String,
    val normalizedText: String,
    val reportedConfidence: Float?,
    override val rect: WindowBounds,
) : VisionEvidence {
    override val confidence: Float get() = reportedConfidence ?: 0.5f
}

enum class FreeAttemptState { AVAILABLE, EXHAUSTED, UNKNOWN }

data class PageCandidate(val pageId: String, val confidence: Float, val evidences: List<VisionEvidence>)

sealed interface PageDetection {
    data class Matched(val pageId: String, val confidence: Float, val evidences: List<VisionEvidence>) : PageDetection
    data class Ambiguous(val candidates: List<PageCandidate>) : PageDetection
    data class Unknown(val evidences: List<VisionEvidence>) : PageDetection
}

data class StablePage(
    val pageId: String,
    val confidence: Float,
    val observedAt: Long,
    val viewportVersion: Long,
)

data class VisionMetrics(
    val frameId: Long = 0,
    val visionFps: Float = 0f,
    val lastVisionDurationMs: Long = 0,
    val p95VisionDurationMs: Long = 0,
    val templateDurationMs: Long = 0,
    val ocrDurationMs: Long = 0,
    val pageDetectorDurationMs: Long = 0,
    val pageDetection: PageDetection = PageDetection.Unknown(emptyList()),
    val stablePage: StablePage? = null,
    val freeAttemptState: FreeAttemptState = FreeAttemptState.UNKNOWN,
)
