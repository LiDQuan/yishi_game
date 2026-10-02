package com.lidquan.yishigame.vision

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelFingerprintSamplerTest {
    @Test fun samplesDistributedStablePointsAndRejectsDifferentPage() {
        val viewport = WindowBounds(0, 0, 400, 400)
        val page = fingerprintPages.first()
        val frames = (1..30).map { requireNotNull(PixelFingerprintSampler.candidateFrame(frame(40, it.toLong()), viewport)) }
        val result = requireNotNull(PixelFingerprintSampler.sample(page, frames, viewport, "fixed", emptyList()))
        assertEquals(30, frames.size)
        assertEquals(20, result.fingerprint.points.size)
        assertEquals(1.0, result.selfMatchRate, 0.001)
        assertEquals(1.0, PixelFingerprintSampler.score(frame(40, 31), viewport, result.fingerprint)!!, 0.001)
        assertEquals(0.0, PixelFingerprintSampler.score(frame(100, 32), viewport, result.fingerprint)!!, 0.001)
        assertTrue(result.fingerprint.points.map { it.x * 5 / 400 to it.y * 4 / 400 }.toSet().size == 20)
        assertEquals(null, PixelFingerprintSampler.score(frame(40, 33), WindowBounds(0, 0, 399, 400), result.fingerprint))
    }

    private fun frame(color: Int, id: Long): ScreenFrameSnapshot {
        val rgba = ByteArray(400 * 400 * 4)
        for (i in rgba.indices step 4) {
            rgba[i] = color.toByte()
            rgba[i + 1] = color.toByte()
            rgba[i + 2] = color.toByte()
            rgba[i + 3] = 255.toByte()
        }
        return ScreenFrameSnapshot(id, 400, 400, 1600, 4, id, rgba)
    }
}
