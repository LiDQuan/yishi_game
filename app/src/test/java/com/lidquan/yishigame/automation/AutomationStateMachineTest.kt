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
    fun `failure enters error from every state`() {
        AutomationState.entries.forEach { state ->
            val machine = AutomationStateMachine(state)
            assertTrue(machine.dispatch(AutomationEvent.Fail("test")))
            assertEquals(AutomationState.ERROR, machine.state.value)
        }
    }
}
