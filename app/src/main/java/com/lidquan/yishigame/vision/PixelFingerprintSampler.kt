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
    FingerprintPage("NETWORK_DISCONNECTED", "服务器断开连接提示界面", "显示服务器断线或网络错误提示的弹窗", "请在该提示自然出现时停留"),
    FingerprintPage("BAG_FULL", "背包已满提示界面", "显示背包已满和自动出售按钮的提示", "请在该提示自然出现时停留"),
    FingerprintPage("CHARACTER_SELECT", "角色选择界面", "显示可选择角色的页面", "请从游戏入口进入角色选择"),
    FingerprintPage("SETTINGS", "设置界面", "显示游戏设置选项的页面", "请从游戏主页打开设置"),
)

data class FingerprintPoint(val x: Int, val y: Int, val r: Int, val g: Int, val b: Int, val tolerance: Int = 12)
data class PixelFingerprint(
    val pageId: String, val displayNameZh: String, val referenceWidth: Int, val referenceHeight: Int,
    val viewportProfileId: String, val requiredMatchRate: Double, val points: List<FingerprintPoint>,
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
        if (frames.size < 25 || !viewport.isValid || frames.any { it.colors.size != 192 }) return null
        val candidates = mutableListOf<Pair<FingerprintPoint, Double>>()
        for ((index, location) in candidateCoordinates(viewport).withIndex()) {
            val (x, y) = location
            val colors = frames.map { it.colors[index] }
            val median = IntArray(3) { c -> colors.map { it[c] }.sorted()[colors.size / 2] }
            val maxDelta = colors.maxOf { color -> (0..2).maxOf { c -> abs(color[c] - median[c]) } }
            if (maxDelta > 12) continue
            val point = FingerprintPoint(x, y, median[0], median[1], median[2])
            val distinct = others.filter { it.referenceWidth == viewport.width && it.referenceHeight == viewport.height && it.viewportProfileId == profileId }
                .mapNotNull { other -> other.points.minByOrNull { abs(it.x - x) + abs(it.y - y) } }
                .minOfOrNull { other -> (abs(other.r - point.r) + abs(other.g - point.g) + abs(other.b - point.b)).toDouble() / 3 } ?: 128.0
            candidates += point to (distinct - maxDelta)
        }
        val points = candidates.groupBy { (point, _) -> point.x * 5 / viewport.width to point.y * 4 / viewport.height }
            .values.map { cell -> cell.maxBy { it.second }.first }
        if (points.size < 20) return null
        val fingerprint = PixelFingerprint(page.pageId, page.displayNameZh, viewport.width, viewport.height, profileId, 0.85, points.take(30))
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
            .put("points", JSONArray().apply { value.points.forEach { p -> put(JSONObject().put("x", p.x).put("y", p.y).put("r", p.r).put("g", p.g).put("b", p.b).put("tolerance", p.tolerance)) } })
        File(directory, "${value.pageId}.json").writeText(json.toString())
    }

    fun loadAll(): List<PixelFingerprint> = directory.listFiles { file -> file.extension == "json" }.orEmpty().mapNotNull { file ->
        runCatching {
            val json = JSONObject(file.readText())
            val points = json.getJSONArray("points")
            PixelFingerprint(json.getString("pageId"), json.getString("displayNameZh"), json.getInt("referenceWidth"),
                json.getInt("referenceHeight"), json.getString("viewportProfileId"), json.getDouble("requiredMatchRate"),
                (0 until points.length()).map { i -> points.getJSONObject(i).let { FingerprintPoint(it.getInt("x"), it.getInt("y"), it.getInt("r"), it.getInt("g"), it.getInt("b"), it.getInt("tolerance")) } })
        }.getOrNull()
    }
}
