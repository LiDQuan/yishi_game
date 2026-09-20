package com.lidquan.yishigame.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowMonitorTest {
    @Test
    fun `requires an accepted baseline and keeps changes pending`() {
        val monitor = WindowMonitor()
        val first = WindowBounds(10, 20, 810, 620)

        assertEquals(WindowGate.NO_BASELINE, monitor.observe(first))
        assertEquals(WindowGate.MATCHED, monitor.accept(first))
        assertEquals(WindowGate.MATCHED, monitor.observe(first))
        assertEquals(WindowGate.CHANGED, monitor.observe(first.copy(right = 900)))
        assertEquals(WindowGate.CHANGED, monitor.observe(first.copy(right = 900)))
        assertEquals(WindowGate.MATCHED, monitor.accept(first.copy(right = 900)))
    }

    @Test
    fun `rejects missing or invalid bounds without replacing last sample`() {
        val monitor = WindowMonitor()
        val valid = WindowBounds(0, 0, 800, 600)

        assertEquals(WindowGate.MATCHED, monitor.accept(valid))
        assertEquals(WindowGate.UNAVAILABLE, monitor.observe(null))
        assertEquals(WindowGate.UNAVAILABLE, monitor.observe(WindowBounds(0, 0, 0, 600)))
        assertEquals(WindowGate.MATCHED, monitor.observe(valid))
    }
}
