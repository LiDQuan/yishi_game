package com.lidquan.yishigame.capture

import android.graphics.Bitmap
import java.io.File

/** Developer-only recording. Images remain inside filesDir. */
fun saveCalibrationFrame(frame: ScreenFrameSnapshot, destination: File) {
    val pixels = IntArray(frame.width * frame.height)
    for (y in 0 until frame.height) for (x in 0 until frame.width) {
        val offset = y * frame.rowStride + x * frame.pixelStride
        val r = frame.rgba[offset].toInt() and 255
        val g = frame.rgba[offset + 1].toInt() and 255
        val b = frame.rgba[offset + 2].toInt() and 255
        pixels[y * frame.width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
    }
    val bitmap = Bitmap.createBitmap(pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
    try {
        destination.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    } finally {
        bitmap.recycle()
    }
}
