package com.lidquan.yishigame.vision.page

import com.lidquan.yishigame.vision.PageCandidate
import com.lidquan.yishigame.vision.PageDetection
import com.lidquan.yishigame.vision.StablePage
import com.lidquan.yishigame.vision.VisionEvidence

data class SignalRule(val id: String, val minimumConfidence: Float = 0f)

data class PageDefinition(
    val pageId: String,
    val version: Int,
    val required: List<SignalRule>,
    val optional: List<SignalRule> = emptyList(),
    val negative: List<SignalRule> = emptyList(),
    val threshold: Float,
    val minimumMargin: Float,
)

class PageDetector(private val definitions: List<PageDefinition>) {
    fun detect(evidences: List<VisionEvidence>, candidates: Set<String>? = null): PageDetection {
        val byId = evidences.associateBy { it.id }
        val scored = definitions.asSequence()
            .filter { candidates == null || it.pageId in candidates }
            .mapNotNull { definition ->
                if (definition.negative.any { rule -> byId[rule.id]?.confidence.orZero() >= rule.minimumConfidence }) return@mapNotNull null
                val required = definition.required.map { rule -> byId[rule.id]?.takeIf { it.confidence >= rule.minimumConfidence } ?: return@mapNotNull null }
                val optional = definition.optional.mapNotNull { rule -> byId[rule.id]?.takeIf { it.confidence >= rule.minimumConfidence } }
                val used = required + optional
                val confidence = used.map { it.confidence }.average().toFloat()
                PageCandidate(definition.pageId, confidence, used)
            }
            .sortedByDescending { it.confidence }
            .toList()
        val best = scored.firstOrNull() ?: return PageDetection.Unknown(evidences)
        val definition = definitions.first { it.pageId == best.pageId }
        if (best.confidence < definition.threshold) return PageDetection.Unknown(evidences)
        val second = scored.getOrNull(1)
        if (second != null && best.confidence - second.confidence < definition.minimumMargin) {
            return PageDetection.Ambiguous(listOf(best, second))
        }
        return PageDetection.Matched(best.pageId, best.confidence, best.evidences)
    }
}

private fun Float?.orZero(): Float = this ?: 0f

class StablePageTracker(private val windowSize: Int = 3, private val requiredMatches: Int = 2) {
    init {
        require(windowSize > 0 && requiredMatches in 1..windowSize)
    }

    private val history = ArrayDeque<PageDetection>()
    private var version: Long? = null

    fun add(detection: PageDetection, viewportVersion: Long, observedAt: Long): StablePage? {
        if (version != viewportVersion) {
            history.clear()
            version = viewportVersion
        }
        history.addLast(detection)
        while (history.size > windowSize) history.removeFirst()
        val latest = history.lastOrNull() as? PageDetection.Matched ?: return null
        val samePage = history.filterIsInstance<PageDetection.Matched>().filter { it.pageId == latest.pageId }
        if (samePage.size < requiredMatches) return null
        return StablePage(
            latest.pageId,
            samePage.map { it.confidence }.average().toFloat(),
            observedAt,
            viewportVersion,
            latest.evidences.associate { it.id to it.rect },
        )
    }

    fun clear() {
        history.clear()
        version = null
    }
}
