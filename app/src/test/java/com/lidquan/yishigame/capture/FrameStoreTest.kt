package com.lidquan.yishigame.capture

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStoreTest {
    @Test fun `snapshot is isolated from reused capture buffer`() {
        val store = LatestFrameStore()
        store.publish(1, 1, 4, 4, 1, ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)))
        val first = store.latestSnapshot()!!
        store.publish(1, 1, 4, 4, 2, ByteBuffer.wrap(byteArrayOf(5, 6, 7, 8)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), first.rgba)
        assertArrayEquals(byteArrayOf(5, 6, 7, 8), store.latestSnapshot()!!.rgba)
        assertEquals(2, store.metadata.value.frameId)
    }
}
