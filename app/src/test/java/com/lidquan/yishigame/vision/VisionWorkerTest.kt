package com.lidquan.yishigame.vision

import com.lidquan.yishigame.capture.LatestFrameStore
import java.nio.ByteBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class VisionWorkerTest {
    @Test fun `invalidate discards OCR already in flight and requires new frames`() = runBlocking {
        val store = LatestFrameStore()
        fun publish() = store.publish(1, 1, 4, 4, System.nanoTime(), ByteBuffer.wrap(ByteArray(4)))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val worker = VisionWorker(store, {
            calls++
            if (calls == 1) { entered.complete(Unit); release.await() }
            VisionAnalysis(PageDetection.Matched("AREA_MAP", .9f, emptyList()), FreeAttemptState.UNKNOWN)
        }, framesPerSecond = 10)
        try {
            publish()
            worker.start(this) { 1L }
            withTimeout(2_000) { entered.await() }
            worker.invalidate()
            release.complete(Unit)
            delay(250)
            assertEquals(0L, worker.metrics.value.frameId)
            publish()
            withTimeout(2_000) { while (worker.metrics.value.frameId == 0L) delay(10) }
            assertNull(worker.metrics.value.stablePage)
            publish()
            withTimeout(2_000) { while (worker.metrics.value.stablePage == null) delay(10) }
            assertEquals("AREA_MAP", worker.metrics.value.stablePage?.pageId)
        } finally { worker.stop() }
    }

    @Test fun `invalidate keeps worker alive for stop then restart lifecycle`() = runBlocking {
        val store = LatestFrameStore()
        val worker = VisionWorker(
            store,
            analyze = { VisionAnalysis(PageDetection.Matched("SETTINGS", .95f, emptyList()), FreeAttemptState.UNKNOWN) },
            framesPerSecond = 10,
        )
        worker.start(this) { 1 }
        try {
            publish(store, 1)
            delay(150)
            publish(store, 2)
            delay(150)
            assertEquals("SETTINGS", worker.metrics.value.stablePage?.pageId)

            worker.invalidate()
            assertNull(worker.metrics.value.stablePage)
            publish(store, 3)
            delay(150)
            publish(store, 4)
            delay(150)
            assertEquals("SETTINGS", worker.metrics.value.stablePage?.pageId)
        } finally {
            worker.stop()
        }
    }

    private fun publish(store: LatestFrameStore, timestamp: Long) {
        store.publish(1, 1, 4, 4, timestamp, ByteBuffer.wrap(byteArrayOf(0, 0, 0, 0)))
    }
}
