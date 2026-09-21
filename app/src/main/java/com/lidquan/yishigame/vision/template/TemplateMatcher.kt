package com.lidquan.yishigame.vision.template

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import com.lidquan.yishigame.viewport.NormalizedRect
import com.lidquan.yishigame.viewport.ViewportMapper
import com.lidquan.yishigame.vision.TemplateEvidence

data class TemplateDefinition(
    val id: String,
    val version: Int,
    val pageId: String,
    val roi: NormalizedRect,
    val threshold: Float,
    val scalePolicy: ScalePolicy = ScalePolicy.FIXED,
)

enum class ScalePolicy { FIXED }

data class RgbaTemplate(val width: Int, val height: Int, val rgba: ByteArray)

object TemplateMatcher {
    fun match(
        definition: TemplateDefinition,
        frame: ScreenFrameSnapshot,
        viewport: WindowBounds,
        template: RgbaTemplate,
    ): TemplateEvidence? {
        require(definition.scalePolicy == ScalePolicy.FIXED)
        val rect = ViewportMapper.toScreen(definition.roi, viewport)
        val evidence = compare(definition.id, crop(frame, rect), template, rect) ?: return null
        return evidence.takeIf { it.confidence >= definition.threshold }
    }

    fun compare(id: String, candidate: RgbaTemplate, template: RgbaTemplate, rect: WindowBounds): TemplateEvidence? {
        if (candidate.width != template.width || candidate.height != template.height || candidate.rgba.size != template.rgba.size) return null
        if (candidate.rgba.isEmpty()) return null
        var difference = 0L
        for (index in candidate.rgba.indices) {
            difference += kotlin.math.abs((candidate.rgba[index].toInt() and 0xff) - (template.rgba[index].toInt() and 0xff))
        }
        val confidence = 1f - difference.toFloat() / (candidate.rgba.size * 255f)
        return TemplateEvidence(id, confidence.coerceIn(0f, 1f), rect)
    }

    fun crop(frame: ScreenFrameSnapshot, rect: WindowBounds): RgbaTemplate {
        require(rect.left >= 0 && rect.top >= 0 && rect.right <= frame.width && rect.bottom <= frame.height && rect.isValid)
        require(frame.pixelStride >= 4 && frame.rowStride >= frame.width * frame.pixelStride)
        val output = ByteArray(rect.width * rect.height * 4)
        var target = 0
        for (y in rect.top until rect.bottom) {
            var source = y * frame.rowStride + rect.left * frame.pixelStride
            repeat(rect.width) {
                frame.rgba.copyInto(output, target, source, source + 4)
                source += frame.pixelStride
                target += 4
            }
        }
        return RgbaTemplate(rect.width, rect.height, output)
    }
}
