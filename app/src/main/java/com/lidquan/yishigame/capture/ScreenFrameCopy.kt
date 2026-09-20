package com.lidquan.yishigame.capture

import java.nio.ByteBuffer

internal fun copyScreenFrame(
    width: Int,
    height: Int,
    rowStride: Int,
    pixelStride: Int,
    timestampNanos: Long,
    buffer: ByteBuffer,
): ScreenFrame {
    val rgba = ByteArray(buffer.remaining())
    buffer.get(rgba)
    return ScreenFrame(width, height, rowStride, pixelStride, timestampNanos, rgba)
}
