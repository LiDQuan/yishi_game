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
        assertEquals(192, result.fingerprint.candidateMedians.size)
        assertEquals(1.0, result.selfMatchRate, 0.001)
        assertEquals(1.0, PixelFingerprintSampler.score(frame(40, 31), viewport, result.fingerprint)!!, 0.001)
        assertEquals(0.0, PixelFingerprintSampler.score(frame(100, 32), viewport, result.fingerprint)!!, 0.001)
        assertTrue(result.fingerprint.points.map { it.x * 5 / 400 to it.y * 4 / 400 }.toSet().size == 20)
        assertEquals(null, PixelFingerprintSampler.score(frame(40, 33), WindowBounds(0, 0, 399, 400), result.fingerprint))
    }

    @Test fun comparesOtherPagesAtExactlyTheSameCoordinate() {
        val viewport = WindowBounds(0, 0, 400, 400)
        val frames = (1..30).map { requireNotNull(PixelFingerprintSampler.candidateFrame(frame(40, it.toLong()), viewport)) }
        val baseline = requireNotNull(PixelFingerprintSampler.sample(fingerprintPages[0], frames, viewport, "fixed", emptyList())).fingerprint
        val medians = baseline.candidateMedians.mapIndexed { index, point -> if (index == 1) point.copy(r = 200, g = 200, b = 200) else point }
        val other = baseline.copy(pageId = "AREA_MAP", candidateMedians = medians)
        val sampled = requireNotNull(PixelFingerprintSampler.sample(fingerprintPages[0], frames, viewport, "fixed", listOf(other)))
        assertTrue(sampled.fingerprint.points.any { it.x == 50 && it.y == 12 })
        val misleadingNearby = other.copy(points = other.points.map { it.copy(r = 200, g = 200, b = 200) }, candidateMedians = baseline.candidateMedians)
        val sameColors = requireNotNull(PixelFingerprintSampler.sample(fingerprintPages[0], frames, viewport, "fixed", listOf(misleadingNearby)))
        assertEquals(baseline.points, sameColors.fingerprint.points)
        val wrongProfile = requireNotNull(PixelFingerprintSampler.sample(fingerprintPages[0], frames, viewport, "fixed", listOf(other.copy(viewportProfileId = "different"))))
        assertEquals(baseline.points, wrongProfile.fingerprint.points)
    }

    @Test fun honorsEachPageThresholdAndRequiresDistinctRealFrames() {
        val viewport = WindowBounds(0, 0, 400, 400)
        val frames = (1..30).map { requireNotNull(PixelFingerprintSampler.candidateFrame(frame(40, it.toLong()), viewport)) }
        val fingerprint = requireNotNull(PixelFingerprintSampler.sample(fingerprintPages[0], frames, viewport, "fixed", emptyList())).fingerprint
        assertEquals(null, PixelFingerprintSampler.recognizedPage(listOf(fingerprint.copy(requiredMatchRate = 0.90)), mapOf("HOME" to 0.88)))
        assertEquals("HOME", PixelFingerprintSampler.recognizedPage(listOf(fingerprint.copy(requiredMatchRate = 0.90)), mapOf("HOME" to 0.91)))
        assertEquals(null, PixelFingerprintSampler.sample(fingerprintPages[0], List(30) { frames.first() }, viewport, "fixed", emptyList()))
        assertEquals(null, PixelFingerprintSampler.sample(fingerprintPages[0], frames.take(29), viewport, "fixed", emptyList()))
        assertEquals(6, fingerprintPages.size)
        assertTrue(fingerprintPages.none { it.pageId == "BAG_FULL" })
        val animated = frames.mapIndexed { index, frame -> frame.copy(colors = frame.colors.map { IntArray(3) { if (index % 2 == 0) 40 else 100 } }) }
        val rejected = requireNotNull(PixelFingerprintSampler.sample(fingerprintPages[0], animated, viewport, "fixed", emptyList()))
        assertEquals(0, rejected.candidateCount)
        assertTrue(rejected.fingerprint.points.isEmpty())
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
