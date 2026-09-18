package com.lidquan.yishigame.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowMonitorTest {
    @Test
    fun `reports first unchanged and changed samples`() {
        val monitor = WindowMonitor()
        val first = WindowBounds(10, 20, 810, 620)

        assertEquals(WindowChange.FIRST_SAMPLE, monitor.observe(first))
        assertEquals(WindowChange.UNCHANGED, monitor.observe(first))
        assertEquals(WindowChange.CHANGED, monitor.observe(first.copy(right = 900)))
    }

    @Test
    fun `rejects missing or invalid bounds without replacing last sample`() {
        val monitor = WindowMonitor()
        val valid = WindowBounds(0, 0, 800, 600)

        assertEquals(WindowChange.FIRST_SAMPLE, monitor.observe(valid))
        assertEquals(WindowChange.UNAVAILABLE, monitor.observe(null))
        assertEquals(WindowChange.UNAVAILABLE, monitor.observe(WindowBounds(0, 0, 0, 600)))
        assertEquals(WindowChange.UNCHANGED, monitor.observe(valid))
    }
}
