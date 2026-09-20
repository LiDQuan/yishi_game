package com.lidquan.yishigame.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFrameCopyTest {
    @Test
    fun `copies nonempty pixel bytes before the source image is closed`() {
        val frame = copyScreenFrame(
            width = 2,
            height = 1,
            rowStride = 8,
            pixelStride = 4,
            timestampNanos = 42,
            buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
        )

        assertTrue(frame.rgba.isNotEmpty())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), frame.rgba)
        assertEquals(42, frame.timestampNanos)
    }
}
