package com.lidquan.yishigame

import com.lidquan.yishigame.automation.AutomationState
import com.lidquan.yishigame.automation.RecoveryRequirement
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
        targetVisible = true,
        targetActive = true,
        windowGate = WindowGate.MATCHED,
    )

    @Test
    fun `visible matched target can arm while assistant has focus`() {
        assertTrue(readyEnvironment.canArmPlaceholder)
        assertFalse(readyEnvironment.copy(windowGate = WindowGate.CHANGED).canArmPlaceholder)
        assertFalse(readyEnvironment.copy(windowGate = WindowGate.UNAVAILABLE).canArmPlaceholder)
        assertTrue(readyEnvironment.copy(targetActive = false).canArmPlaceholder)
        assertFalse(readyEnvironment.copy(targetVisible = false).canArmPlaceholder)
    }

    @Test
    fun `explicit resume requires a safe environment and prior user confirmation`() {
        val paused = readyEnvironment.copy(
            automationState = AutomationState.PAUSED,
            targetActive = false,
            recoveryRequirement = RecoveryRequirement.EXPLICIT_CONFIRMATION,
        )

        assertTrue(paused.canRequestResume)
        assertFalse(paused.copy(recoveryRequirement = RecoveryRequirement.ENVIRONMENT_CHECK).canRequestResume)
        assertFalse(paused.copy(captureState = ScreenCaptureState.Stopped("test")).canRequestResume)
        assertFalse(paused.copy(windowGate = WindowGate.CHANGED).canRequestResume)
        assertFalse(paused.copy(targetVisible = false).canRequestResume)
        assertFalse(paused.copy(accessibilityEnabled = false).canRequestResume)
    }
}
