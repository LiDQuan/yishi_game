package com.lidquan.yishigame

import com.lidquan.yishigame.automation.AutomationState
import com.lidquan.yishigame.automation.WindowGate
import com.lidquan.yishigame.capture.ScreenCaptureState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentUiStateTest {
    private val readyEnvironment = EnvironmentUiState(
        automationState = AutomationState.READY,
        accessibilityEnabled = true,
        captureState = ScreenCaptureState.Active(100, 100, 1),
        targetDetected = true,
        windowGate = WindowGate.MATCHED,
    )

    @Test
    fun `only a matched active target can begin`() {
        assertTrue(readyEnvironment.canBeginPlaceholder)
        assertFalse(readyEnvironment.copy(windowGate = WindowGate.CHANGED).canBeginPlaceholder)
        assertFalse(readyEnvironment.copy(windowGate = WindowGate.UNAVAILABLE).canBeginPlaceholder)
        assertFalse(readyEnvironment.copy(targetDetected = false).canBeginPlaceholder)
    }
}
