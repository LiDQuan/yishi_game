package com.lidquan.yishigame.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionConfigurationTest {
    @Test
    fun configurationIsVersionedAndDefinesRequiredPagesWithTwoSignalsEach() {
        val configuration = initialVisionConfiguration()

        assertEquals(1, configuration.schemaVersion)
        assertTrue(configuration.templateSetVersion.isNotBlank())
        assertTrue(configuration.pageDefinitionVersion.isNotBlank())
        assertEquals(
            setOf("SETTINGS", "CHARACTER_SELECT", "SAFE_DIALOG", "NETWORK_DISCONNECTED", "INVENTORY_FULL", "HOME", "AREA_MAP", "AREA_PICKER", "DUNGEON_LIST", "DUNGEON_DETAIL", "BATTLE"),
            configuration.pages.map { it.pageId }.toSet(),
        )
        assertTrue(configuration.pages.all { it.required.size >= if (it.pageId in setOf("NETWORK_DISCONNECTED", "INVENTORY_FULL")) 1 else 2 })
    }
}
