package com.lidquan.yishigame.capture

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FrameMetadata(
    val frameId: Long = 0,
    val timestampNanos: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val captureFps: Float = 0f,
)

data class ScreenFrameSnapshot(
    val frameId: Long,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val pixelStride: Int,
    val timestampNanos: Long,
    val rgba: ByteArray,
)

interface FrameStore {
    val metadata: StateFlow<FrameMetadata>
    fun latestSnapshot(): ScreenFrameSnapshot?
}

class LatestFrameStore : FrameStore {
    private val nextId = AtomicLong()
    private val mutableMetadata = MutableStateFlow(FrameMetadata())
    override val metadata: StateFlow<FrameMetadata> = mutableMetadata.asStateFlow()
    private var bytes = ByteArray(0)
    private var snapshotHeader: ScreenFrameSnapshot? = null
    private var firstTimestamp = 0L

    @Synchronized
    fun publish(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestampNanos: Long,
        source: ByteBuffer,
    ) {
        val size = source.remaining()
        if (bytes.size != size) bytes = ByteArray(size)
        source.get(bytes)
        val id = nextId.incrementAndGet()
        if (firstTimestamp == 0L) firstTimestamp = timestampNanos
        val elapsedSeconds = (timestampNanos - firstTimestamp).coerceAtLeast(1L) / 1_000_000_000f
        snapshotHeader = ScreenFrameSnapshot(id, width, height, rowStride, pixelStride, timestampNanos, ByteArray(0))
        mutableMetadata.value = FrameMetadata(id, timestampNanos, width, height, (id - 1) / elapsedSeconds)
    }

    @Synchronized
    override fun latestSnapshot(): ScreenFrameSnapshot? = snapshotHeader?.copy(rgba = bytes.copyOf())

    @Synchronized
    fun clear() {
        bytes = ByteArray(0)
        snapshotHeader = null
        firstTimestamp = 0L
        mutableMetadata.value = FrameMetadata()
    }
}
