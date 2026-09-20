package com.lidquan.yishigame.vision

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import com.lidquan.yishigame.vision.ocr.MlKitChineseOcrEngine
import com.lidquan.yishigame.action.ActionContext
import com.lidquan.yishigame.action.ActionGuard
import com.lidquan.yishigame.action.ActionIntent
import com.lidquan.yishigame.action.ActionType
import com.lidquan.yishigame.action.GuardDecision
import com.lidquan.yishigame.action.RiskLevel
import com.lidquan.yishigame.automation.AutomationState
import com.lidquan.yishigame.automation.WindowGate
import com.lidquan.yishigame.capture.ScreenCaptureState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class RealVisionFixtureTest {
    @Test
    fun recognizesThreePrivateRealPagesWithoutGameInput() = runBlocking {
        val fixtureDir = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "vision-fixtures")
        val fixtures = mapOf(
            "settings.png" to "SETTINGS",
            "character-select.png" to "CHARACTER_SELECT",
            "dungeon.png" to "DUNGEON_LIST",
        )
        assumeTrue("Private fixtures and metadata are intentionally absent from the repository", fixtures.keys.all {
            File(fixtureDir, it).isFile && File(fixtureDir, "$it.json").isFile
        })
        fixtures.forEach { (name, expectedPage) ->
            val viewport = metadata(File(fixtureDir, "$name.json"))
            val engine = ConfiguredVisionEngine(ocr = MlKitChineseOcrEngine(), viewport = { viewport })
            try {
                val analysis = engine.analyze(frame(File(fixtureDir, name)))
                val detection = analysis.detection as PageDetection.Matched
                assertEquals(expectedPage, detection.pageId)
                if (expectedPage == "DUNGEON_LIST") assertDungeonDryRun(detection, analysis, viewport)
            } finally {
                engine.close()
            }
        }
    }

    private fun assertDungeonDryRun(detection: PageDetection.Matched, analysis: VisionAnalysis, viewport: WindowBounds) {
        val anchor = detection.evidences.first { it.id == "dungeon.anchor" }.rect
        val stable = StablePage(detection.pageId, detection.confidence, 1_000, 7, detection.evidences.associate { it.id to it.rect })
        val context = ActionContext(
            automationState = AutomationState.RUNNING_PLACEHOLDER,
            accessibilityConnected = true,
            captureState = ScreenCaptureState.Active(1, 1, 1),
            targetVisible = true,
            targetActive = true,
            windowGate = WindowGate.MATCHED,
            viewportValid = viewport.isValid,
            stablePage = stable,
            currentViewportVersion = 7,
            now = 1_100,
            allowedPages = setOf("DUNGEON_LIST"),
            targetRect = anchor,
            expectedPagesAfter = setOf("DUNGEON_LIST"),
            freeAttemptState = analysis.freeAttemptState,
        )
        val intent = ActionIntent(ActionType.OPEN_DUNGEON, "dungeon.anchor", RiskLevel.NORMAL)
        assertTrue(ActionGuard.plan(intent, context) is GuardDecision.AllowDryRun)
        assertEquals(com.lidquan.yishigame.action.GuardDenyReason.TARGET_NOT_ACTIVE, (ActionGuard.plan(intent, context.copy(targetActive = false)) as GuardDecision.Deny).reason)
        assertEquals(com.lidquan.yishigame.action.GuardDenyReason.WINDOW_NOT_MATCHED, (ActionGuard.plan(intent, context.copy(windowGate = WindowGate.CHANGED)) as GuardDecision.Deny).reason)
        assertEquals(com.lidquan.yishigame.action.GuardDenyReason.TARGET_NOT_CONFIRMED, (ActionGuard.plan(intent, context.copy(targetRect = null)) as GuardDecision.Deny).reason)
    }

    private fun metadata(file: File): WindowBounds {
        val root = JSONObject(file.readText())
        val window = bounds(root.getJSONObject("windowBounds"))
        val content = bounds(root.getJSONObject("contentViewport"))
        require(root.getLong("profileVersion") > 0 && content.left >= window.left && content.top >= window.top && content.right <= window.right && content.bottom <= window.bottom)
        return content
    }

    private fun bounds(json: JSONObject) = WindowBounds(json.getInt("left"), json.getInt("top"), json.getInt("right"), json.getInt("bottom"))

    private fun frame(file: File): ScreenFrameSnapshot {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val rgba = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { index, color ->
            val offset = index * 4
            rgba[offset] = (color shr 16).toByte()
            rgba[offset + 1] = (color shr 8).toByte()
            rgba[offset + 2] = color.toByte()
            rgba[offset + 3] = (color ushr 24).toByte()
        }
        bitmap.recycle()
        return ScreenFrameSnapshot(1, width, height, width * 4, 4, 1, rgba)
    }
}
