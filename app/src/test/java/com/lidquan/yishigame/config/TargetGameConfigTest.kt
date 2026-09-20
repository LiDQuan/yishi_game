package com.lidquan.yishigame.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetGameConfigTest {
    @Test
    fun `accepts a confirmed Android package name`() {
        assertEquals("example.confirmed.game", TargetGameConfig.parse(" example.confirmed.game ").packageName)
    }

    @Test
    fun `does not invent or accept malformed package names`() {
        listOf(null, "", "game", "bad package", ".leading.dot", "trailing.dot.").forEach {
            assertNull(TargetGameConfig.parse(it).packageName)
        }
    }
}
