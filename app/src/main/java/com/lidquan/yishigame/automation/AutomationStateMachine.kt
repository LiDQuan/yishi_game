package com.lidquan.yishigame.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AutomationStateMachine(initialState: AutomationState = AutomationState.IDLE) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<AutomationState> = mutableState.asStateFlow()

    @Synchronized
    fun dispatch(event: AutomationEvent): Boolean {
        val current = mutableState.value
        val next = transition(current, event) ?: return false
        mutableState.value = next
        return true
    }

    internal fun transition(current: AutomationState, event: AutomationEvent): AutomationState? = when (event) {
        AutomationEvent.Stop -> AutomationState.STOPPED
        is AutomationEvent.Fail -> AutomationState.ERROR
        AutomationEvent.Reset -> if (current in setOf(AutomationState.STOPPED, AutomationState.ERROR)) AutomationState.IDLE else null
        AutomationEvent.StartPrecheck -> if (current in setOf(AutomationState.IDLE, AutomationState.STOPPED)) AutomationState.PRECHECK else null
        AutomationEvent.PermissionMissing -> if (current == AutomationState.PRECHECK) AutomationState.PERMISSION_REQUIRED else null
        AutomationEvent.DeviceReady -> if (current in setOf(AutomationState.PRECHECK, AutomationState.PERMISSION_REQUIRED)) AutomationState.DEVICE_READY else null
        AutomationEvent.CheckWindow -> if (current == AutomationState.DEVICE_READY) AutomationState.WINDOW_CHECK else null
        AutomationEvent.WindowVerified -> if (current == AutomationState.WINDOW_CHECK) AutomationState.READY else null
        AutomationEvent.BeginPlaceholder -> if (current == AutomationState.READY) AutomationState.RUNNING_PLACEHOLDER else null
        AutomationEvent.Pause -> if (current == AutomationState.RUNNING_PLACEHOLDER) AutomationState.PAUSED else null
        AutomationEvent.Resume -> if (current == AutomationState.PAUSED) AutomationState.READY else null
    }
}
