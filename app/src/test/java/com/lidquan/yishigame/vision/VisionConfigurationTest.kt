package com.lidquan.yishigame.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionConfigurationTest {
    @Test
    fun configurationIsVersionedAndDefinesThreePagesWithTwoSignalsEach() {
        val configuration = initialVisionConfiguration()

        assertEquals(1, configuration.schemaVersion)
        assertTrue(configuration.templateSetVersion.isNotBlank())
        assertTrue(configuration.pageDefinitionVersion.isNotBlank())
        assertEquals(setOf("SETTINGS", "CHARACTER_SELECT", "DUNGEON_LIST"), configuration.pages.map { it.pageId }.toSet())
        assertTrue(configuration.pages.all { it.required.size >= 2 })
    }
}
