package com.lidquan.yishigame.vision

import com.lidquan.yishigame.capture.LatestFrameStore
import java.nio.ByteBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VisionWorkerTest {
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
