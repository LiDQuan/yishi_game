package com.lidquan.yishigame.vision.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import com.lidquan.yishigame.vision.OcrEvidence
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class MlKitChineseOcrEngine : OcrEngine, AutoCloseable {
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override suspend fun recognize(frame: ScreenFrameSnapshot, roi: WindowBounds): List<OcrEvidence> {
        val bitmap = roiBitmap(frame, roi)
        return try {
            val result = suspendCancellableCoroutine { continuation ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
            result.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val bounds = line.boundingBox ?: return@mapNotNull null
                    val text = line.text
                    OcrEvidence(
                        id = "ocr.text",
                        text = text,
                        normalizedText = normalizeOcrText(text),
                        reportedConfidence = null,
                        rect = WindowBounds(bounds.left + roi.left, bounds.top + roi.top, bounds.right + roi.left, bounds.bottom + roi.top),
                    )
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() = recognizer.close()

    private fun roiBitmap(frame: ScreenFrameSnapshot, roi: WindowBounds): Bitmap {
        require(roi.left >= 0 && roi.top >= 0 && roi.right <= frame.width && roi.bottom <= frame.height && roi.isValid)
        val pixels = IntArray(roi.width * roi.height)
        var target = 0
        for (y in roi.top until roi.bottom) {
            var source = y * frame.rowStride + roi.left * frame.pixelStride
            repeat(roi.width) {
                val r = frame.rgba[source].toInt() and 0xff
                val g = frame.rgba[source + 1].toInt() and 0xff
                val b = frame.rgba[source + 2].toInt() and 0xff
                val a = frame.rgba[source + 3].toInt() and 0xff
                pixels[target++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                source += frame.pixelStride
            }
        }
        return Bitmap.createBitmap(pixels, roi.width, roi.height, Bitmap.Config.ARGB_8888)
    }
}
