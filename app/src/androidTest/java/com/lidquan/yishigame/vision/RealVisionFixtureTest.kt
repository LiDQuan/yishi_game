package com.lidquan.yishigame.vision

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.capture.ScreenFrameSnapshot
import com.lidquan.yishigame.vision.ocr.MlKitChineseOcrEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

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
        assumeTrue("Private fixtures are intentionally absent from the repository", fixtures.keys.all { File(fixtureDir, it).isFile })
        val engine = ConfiguredVisionEngine(
            ocr = MlKitChineseOcrEngine(),
            viewport = { WindowBounds(99, 7, 1119, 1717) },
        )
        try {
            fixtures.forEach { (name, expectedPage) ->
                val detection = engine.analyze(frame(File(fixtureDir, name))).detection
                assertEquals(expectedPage, (detection as PageDetection.Matched).pageId)
            }
        } finally {
            engine.close()
        }
    }

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
