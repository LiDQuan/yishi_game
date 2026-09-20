package com.lidquan.yishigame.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationStateMachineTest {
    @Test
    fun `happy path reaches placeholder without executing game actions`() {
        val machine = AutomationStateMachine()

        assertTrue(machine.dispatch(AutomationEvent.StartPrecheck))
        assertTrue(machine.dispatch(AutomationEvent.DeviceReady))
        assertTrue(machine.dispatch(AutomationEvent.CheckWindow))
        assertTrue(machine.dispatch(AutomationEvent.WindowVerified))
        assertTrue(machine.dispatch(AutomationEvent.BeginPlaceholder))

        assertEquals(AutomationState.RUNNING_PLACEHOLDER, machine.state.value)
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
