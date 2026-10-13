package com.lidquan.yishigame.vision

import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

data class FingerprintPage(val pageId: String, val displayNameZh: String, val descriptionZh: String, val enterHintZh: String)

val fingerprintPages = listOf(
    FingerprintPage("HOME", "游戏主页", "本地图自动打小怪的常驻画面，显示技能栏和底部导航；地图小怪进度也属于主页", "请回到日常挂机打地图小怪的画面，保持窗口位置不变"),
    FingerprintPage("AREA_MAP", "区域地图界面", "显示当前区域和地下城入口的地图页面", "请从游戏主页进入区域地图"),
    FingerprintPage("AREA_PICKER", "区域选择界面", "显示多个区域名称供选择的页面", "请从区域地图点击“切换区域”"),
    FingerprintPage("DUNGEON_LIST", "地下城列表界面", "列出可选地下城的页面", "请从区域地图点击“地下城”"),
    FingerprintPage("DUNGEON_DETAIL", "地下城详情界面", "显示免费次数和“前往”按钮的页面", "请在地下城列表中打开一个副本详情"),
    FingerprintPage("BATTLE", "副本战斗界面", "进入地下城副本后的战斗画面；与主页的地图小怪区分", "仅在你自行进入免费副本后采集，不要为采样购买次数"),
)

data class FingerprintPoint(val x: Int, val y: Int, val r: Int, val g: Int, val b: Int, val tolerance: Int = 12)
data class PixelFingerprint(
    val pageId: String, val displayNameZh: String, val referenceWidth: Int, val referenceHeight: Int,
    val viewportProfileId: String, val requiredMatchRate: Double, val points: List<FingerprintPoint>,
    val candidateMedians: List<FingerprintPoint> = emptyList(),
)
data class FingerprintSample(val candidateCount: Int, val fingerprint: PixelFingerprint, val selfMatchRate: Double)
data class PixelCandidateFrame(val frameId: Long, val colors: List<IntArray>)

/** Coordinates are relative to the confirmed content viewport; no full frame is persisted. */
object PixelFingerprintSampler {
    fun candidateFrame(frame: ScreenFrameSnapshot, viewport: WindowBounds): PixelCandidateFrame? {
        if (!viewport.isValid || !fits(frame, viewport)) return null
        return PixelCandidateFrame(frame.frameId, candidateCoordinates(viewport).map { (x, y) -> rgb(frame, viewport.left + x, viewport.top + y) })
    }

    fun sample(page: FingerprintPage, frames: List<PixelCandidateFrame>, viewport: WindowBounds,
               profileId: String, others: List<PixelFingerprint>): FingerprintSample? {
        if (frames.size < 30 || frames.map { it.frameId }.distinct().size != frames.size || !viewport.isValid || frames.any { it.colors.size != 192 }) return null
        val candidates = mutableListOf<Pair<FingerprintPoint, Double>>()
        val medians = mutableListOf<FingerprintPoint>()
        val otherColors = others.filter { it.referenceWidth == viewport.width && it.referenceHeight == viewport.height && it.viewportProfileId == profileId }
            .map { other -> other.candidateMedians.associateBy { it.x to it.y } }
        for ((index, location) in candidateCoordinates(viewport).withIndex()) {
            val (x, y) = location
            val colors = frames.map { it.colors[index] }
            val median = IntArray(3) { c -> colors.map { it[c] }.sorted()[colors.size / 2] }
            val maxDelta = colors.maxOf { color -> (0..2).maxOf { c -> abs(color[c] - median[c]) } }
            val point = FingerprintPoint(x, y, median[0], median[1], median[2])
            medians += point
            if (maxDelta > point.tolerance) continue
            val distinct = otherColors.mapNotNull { it[x to y] }
                .minOfOrNull { other -> (abs(other.r - point.r) + abs(other.g - point.g) + abs(other.b - point.b)).toDouble() / 3 } ?: 128.0
            candidates += point to (distinct - maxDelta)
        }
        val points = candidates.groupBy { (point, _) -> point.x * 5 / viewport.width to point.y * 4 / viewport.height }
            .values.map { cell -> cell.maxBy { it.second }.first }
        val fingerprint = PixelFingerprint(page.pageId, page.displayNameZh, viewport.width, viewport.height, profileId, 0.85, points.take(30), medians)
        val self = frames.map { scoreCandidate(it, viewport, fingerprint) }.average()
        return FingerprintSample(candidates.size, fingerprint, self)
    }

    fun scoreCandidate(frame: PixelCandidateFrame, viewport: WindowBounds, fingerprint: PixelFingerprint): Double {
        if (viewport.width != fingerprint.referenceWidth || viewport.height != fingerprint.referenceHeight || fingerprint.points.isEmpty()) return 0.0
        val lookup = candidateCoordinates(viewport).withIndex().associate { it.value to it.index }
        return fingerprint.points.count { p ->
            val color = lookup[p.x to p.y]?.let { frame.colors[it] } ?: return@count false
            abs(color[0] - p.r) <= p.tolerance && abs(color[1] - p.g) <= p.tolerance && abs(color[2] - p.b) <= p.tolerance
        }.toDouble() / fingerprint.points.size
    }

    private fun candidateCoordinates(viewport: WindowBounds): List<Pair<Int, Int>> =
        (0 until 16).flatMap { row -> (0 until 12).map { col ->
            ((col + 0.5) * viewport.width / 12).toInt().coerceIn(0, viewport.width - 1) to
                ((row + 0.5) * viewport.height / 16).toInt().coerceIn(0, viewport.height - 1)
        } }

    fun score(frame: ScreenFrameSnapshot, viewport: WindowBounds, fingerprint: PixelFingerprint): Double? {
        if (!fits(frame, viewport) || viewport.width != fingerprint.referenceWidth || viewport.height != fingerprint.referenceHeight || fingerprint.points.isEmpty()) return null
        return fingerprint.points.count { p ->
            val color = rgb(frame, viewport.left + p.x, viewport.top + p.y)
            abs(color[0] - p.r) <= p.tolerance && abs(color[1] - p.g) <= p.tolerance && abs(color[2] - p.b) <= p.tolerance
        }.toDouble() / fingerprint.points.size
    }

    fun recognizedPage(fingerprints: List<PixelFingerprint>, scores: Map<String, Double>): String? {
        val best = fingerprints.filter { it.pageId in scores }.maxByOrNull { scores.getValue(it.pageId) } ?: return null
        return best.pageId.takeIf { scores.getValue(it) >= best.requiredMatchRate }
    }

    private fun fits(frame: ScreenFrameSnapshot, viewport: WindowBounds) =
        viewport.left >= 0 && viewport.top >= 0 && viewport.right <= frame.width && viewport.bottom <= frame.height

    private fun rgb(frame: ScreenFrameSnapshot, x: Int, y: Int): IntArray {
        val offset = y * frame.rowStride + x * frame.pixelStride
        return intArrayOf(frame.rgba[offset].toInt() and 255, frame.rgba[offset + 1].toInt() and 255, frame.rgba[offset + 2].toInt() and 255)
    }
}

class PixelFingerprintStore(private val directory: File) {
    init { directory.mkdirs() }

    fun save(value: PixelFingerprint) {
        val json = JSONObject().put("pageId", value.pageId).put("displayNameZh", value.displayNameZh)
            .put("referenceWidth", value.referenceWidth).put("referenceHeight", value.referenceHeight)
            .put("viewportProfileId", value.viewportProfileId).put("requiredMatchRate", value.requiredMatchRate)
            .put("points", encodePoints(value.points))
            .put("candidateMedians", encodePoints(value.candidateMedians))
        val temporary = File(directory, "${value.pageId}.tmp")
        temporary.writeText(json.toString())
        check(temporary.renameTo(File(directory, "${value.pageId}.json")))
    }

    fun loadAll(): List<PixelFingerprint> = directory.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull { file ->
        runCatching {
            val json = JSONObject(file.readText())
            PixelFingerprint(json.getString("pageId"), json.getString("displayNameZh"), json.getInt("referenceWidth"),
                json.getInt("referenceHeight"), json.getString("viewportProfileId"), json.getDouble("requiredMatchRate"),
                decodePoints(json.getJSONArray("points")), decodePoints(json.optJSONArray("candidateMedians") ?: JSONArray()))
        }.getOrNull()
    }

    private fun encodePoints(points: List<FingerprintPoint>) = JSONArray().apply {
        points.forEach { p -> put(JSONObject().put("x", p.x).put("y", p.y).put("r", p.r).put("g", p.g).put("b", p.b).put("tolerance", p.tolerance)) }
    }

    private fun decodePoints(points: JSONArray) = (0 until points.length()).map { i ->
        points.getJSONObject(i).let { FingerprintPoint(it.getInt("x"), it.getInt("y"), it.getInt("r"), it.getInt("g"), it.getInt("b"), it.getInt("tolerance")) }
    }
}
