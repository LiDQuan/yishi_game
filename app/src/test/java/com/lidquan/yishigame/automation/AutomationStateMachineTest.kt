package com.lidquan.yishigame.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationStateMachineTest {
    @Test fun `finished session permits a new precheck without retaining running state`() {
        val machine = AutomationStateMachine(AutomationState.RUNNING_PLACEHOLDER)
        assertTrue(machine.dispatch(AutomationEvent.RunFinished))
        assertEquals(AutomationState.READY, machine.state.value)
        assertTrue(machine.dispatch(AutomationEvent.StartPrecheck))
        assertFalse(machine.dispatch(AutomationEvent.RunFinished))
    }

    @Test
    fun `environment check can retry after permission is granted`() {
        val machine = AutomationStateMachine()

        assertTrue(machine.dispatch(AutomationEvent.StartPrecheck))
        assertTrue(machine.dispatch(AutomationEvent.PermissionMissing))
        assertEquals(AutomationState.PERMISSION_REQUIRED, machine.state.value)
        assertTrue(machine.dispatch(AutomationEvent.StartPrecheck))
        assertTrue(machine.dispatch(AutomationEvent.DeviceReady))
        assertTrue(machine.dispatch(AutomationEvent.CheckWindow))
        assertTrue(machine.dispatch(AutomationEvent.WindowVerified))
        assertEquals(AutomationState.READY, machine.state.value)
    }

    @Test
    fun `arming waits for target activation before reaching placeholder`() {
        val machine = AutomationStateMachine()

        assertTrue(machine.dispatch(AutomationEvent.StartPrecheck))
        assertTrue(machine.dispatch(AutomationEvent.DeviceReady))
        assertTrue(machine.dispatch(AutomationEvent.CheckWindow))
        assertTrue(machine.dispatch(AutomationEvent.WindowVerified))
        assertTrue(machine.dispatch(AutomationEvent.BeginPlaceholder))
        assertEquals(AutomationState.WAIT_TARGET_ACTIVE, machine.state.value)
        assertTrue(machine.dispatch(AutomationEvent.TargetActivated))

        assertEquals(AutomationState.RUNNING_PLACEHOLDER, machine.state.value)
    }

    @Test
    fun `arming times out safely without executing game actions`() {
        val machine = AutomationStateMachine(AutomationState.WAIT_TARGET_ACTIVE)

        assertTrue(machine.dispatch(AutomationEvent.TargetActivationTimedOut))

        assertEquals(AutomationState.PAUSED, machine.state.value)
        assertEquals(RecoveryRequirement.EXPLICIT_CONFIRMATION, machine.recoveryRequirement)
        assertTrue(machine.dispatch(AutomationEvent.RequestResume))
        assertEquals(AutomationState.WAIT_TARGET_ACTIVE, machine.state.value)
    }

    @Test
    fun `active run pauses when the target loses focus`() {
        val machine = AutomationStateMachine(AutomationState.RUNNING_PLACEHOLDER)

        assertTrue(machine.dispatch(AutomationEvent.EnvironmentChanged(EnvironmentChangeReason.TARGET_NOT_ACTIVE)))

        assertEquals(AutomationState.PAUSED, machine.state.value)
        assertEquals(RecoveryRequirement.EXPLICIT_CONFIRMATION, machine.recoveryRequirement)
        assertFalse(machine.dispatch(AutomationEvent.TargetActivated))
        assertEquals(AutomationState.PAUSED, machine.state.value)
        assertTrue(machine.dispatch(AutomationEvent.RequestResume))
        assertEquals(AutomationState.WAIT_TARGET_ACTIVE, machine.state.value)
        assertTrue(machine.dispatch(AutomationEvent.TargetActivated))
        assertEquals(AutomationState.RUNNING_PLACEHOLDER, machine.state.value)
    }

    @Test
    fun `window and capture failures require a complete environment check before resume`() {
        for (reason in listOf(
            EnvironmentChangeReason.WINDOW_CHANGED,
            EnvironmentChangeReason.WINDOW_UNAVAILABLE,
            EnvironmentChangeReason.CAPTURE_INACTIVE,
            EnvironmentChangeReason.ACCESSIBILITY_UNAVAILABLE,
        )) {
            val machine = AutomationStateMachine(AutomationState.RUNNING_PLACEHOLDER)
            assertTrue(machine.dispatch(AutomationEvent.EnvironmentChanged(reason)))
            assertEquals(AutomationState.PAUSED, machine.state.value)
            assertEquals(RecoveryRequirement.ENVIRONMENT_CHECK, machine.recoveryRequirement)
            assertFalse(machine.dispatch(AutomationEvent.RequestResume))

            assertTrue(machine.dispatch(AutomationEvent.StartPrecheck))
            assertTrue(machine.dispatch(AutomationEvent.DeviceReady))
            assertTrue(machine.dispatch(AutomationEvent.CheckWindow))
            assertTrue(machine.dispatch(AutomationEvent.WindowVerified))
            assertEquals(RecoveryRequirement.NONE, machine.recoveryRequirement)
            assertEquals(AutomationState.READY, machine.state.value)
            assertFalse(machine.dispatch(AutomationEvent.RequestResume))
            assertTrue(machine.dispatch(AutomationEvent.BeginPlaceholder))
            assertEquals(AutomationState.WAIT_TARGET_ACTIVE, machine.state.value)
        }
    }

    @Test
    fun `invalid transition is rejected`() {
        val machine = AutomationStateMachine()

        assertFalse(machine.dispatch(AutomationEvent.BeginPlaceholder))
        assertEquals(AutomationState.IDLE, machine.state.value)
    }

    @Test
    fun `stop is safe from every state`() {
        AutomationState.entries.forEach { state ->
            val machine = AutomationStateMachine(state)
            assertTrue(machine.dispatch(AutomationEvent.Stop))
            assertEquals(AutomationState.STOPPED, machine.state.value)
        }
    }

    @Test
    fun `failure enters error except after an explicit stop`() {
        AutomationState.entries.filter { it != AutomationState.STOPPED }.forEach { state ->
            val machine = AutomationStateMachine(state)
            assertTrue(machine.dispatch(AutomationEvent.Fail("test")))
            assertEquals(AutomationState.ERROR, machine.state.value)
        }
    }

    @Test
    fun `stopped ignores delayed failures and resets safely`() {
        val machine = AutomationStateMachine(AutomationState.STOPPED)

        assertFalse(machine.dispatch(AutomationEvent.Fail("late_callback")))
        assertEquals(AutomationState.STOPPED, machine.state.value)
        assertTrue(machine.dispatch(AutomationEvent.Reset))
        assertEquals(AutomationState.IDLE, machine.state.value)
    }
}
