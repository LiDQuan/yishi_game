package com.lidquan.yishigame.vision

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PixelFingerprintStoreTest {
    @Test fun preservesCandidateMediansAndPageThreshold() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File.createTempFile("fingerprint-test", "", context.cacheDir).apply { delete(); mkdirs() }
        try {
            val medians = (0 until 192).map { FingerprintPoint(it % 12, it / 12, it, 30, 50) }
            val fingerprint = PixelFingerprint("HOME", "游戏主页", 12, 16, "test", 0.91, medians.take(20), medians)
            val store = PixelFingerprintStore(directory)
            store.save(fingerprint)
            assertEquals(fingerprint, store.loadAll().single())
        } finally {
            directory.deleteRecursively()
        }
    }
}
