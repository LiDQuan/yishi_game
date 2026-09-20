package com.lidquan.yishigame.vision

import com.lidquan.yishigame.capture.FrameStore
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import com.lidquan.yishigame.vision.page.StablePageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VisionAnalysis(
    val detection: PageDetection,
    val freeAttemptState: FreeAttemptState,
    val templateDurationMs: Long = 0,
    val ocrDurationMs: Long = 0,
    val pageDetectorDurationMs: Long = 0,
    val actionTargets: List<ActionTargetEvidence> = emptyList(),
)

class VisionWorker(
    private val frameStore: FrameStore,
    private val analyze: suspend (ScreenFrameSnapshot) -> VisionAnalysis,
    private val framesPerSecond: Int = 2,
) {
    init { require(framesPerSecond in 1..10) }

    private val mutableMetrics = MutableStateFlow(VisionMetrics())
    val metrics: StateFlow<VisionMetrics> = mutableMetrics.asStateFlow()
    private val stableTracker = StablePageTracker()
    private val recentDurations = ArrayDeque<Long>()
    private var job: Job? = null

    fun start(scope: CoroutineScope, viewportVersion: () -> Long) {
        if (job?.isActive == true) return
        job = scope.launch {
            var lastFrameId = 0L
            var processed = 0L
            val started = System.nanoTime()
            while (isActive) {
                val frame = frameStore.latestSnapshot()
                if (frame != null && frame.frameId != lastFrameId) {
                    val before = System.nanoTime()
                    val result = withContext(Dispatchers.Default) { analyze(frame) }
                    val duration = (System.nanoTime() - before) / 1_000_000L
                    processed++
                    val seconds = (System.nanoTime() - started).coerceAtLeast(1L) / 1_000_000_000f
                    val stable = stableTracker.add(result.detection, viewportVersion(), System.currentTimeMillis())
                    recentDurations.addLast(duration)
                    while (recentDurations.size > 100) recentDurations.removeFirst()
                    val sorted = recentDurations.sorted()
                    val p95 = sorted[((sorted.size - 1) * 0.95f).toInt()]
                    mutableMetrics.value = VisionMetrics(
                        frameId = frame.frameId,
                        visionFps = processed / seconds,
                        lastVisionDurationMs = duration,
                        p95VisionDurationMs = p95,
                        templateDurationMs = result.templateDurationMs,
                        ocrDurationMs = result.ocrDurationMs,
                        pageDetectorDurationMs = result.pageDetectorDurationMs,
                        pageDetection = result.detection,
                        stablePage = stable,
                        freeAttemptState = result.freeAttemptState,
                        actionTargets = result.actionTargets.associate { it.id to it.rect },
                    )
                    lastFrameId = frame.frameId
                }
                delay(1_000L / framesPerSecond)
            }
        }
    }

    fun invalidate() {
        stableTracker.clear()
        recentDurations.clear()
        mutableMetrics.value = VisionMetrics()
    }

    fun stop() {
        job?.cancel()
        job = null
        invalidate()
    }
}
