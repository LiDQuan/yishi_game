package com.lidquan.yishigame.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AutomationStateMachine(initialState: AutomationState = AutomationState.IDLE) {
    private val mutableState = MutableStateFlow(initialState)
    val state: StateFlow<AutomationState> = mutableState.asStateFlow()
    private var mutableRecoveryRequirement = RecoveryRequirement.NONE
    val recoveryRequirement: RecoveryRequirement
        @Synchronized get() = mutableRecoveryRequirement

    @Synchronized
    fun dispatch(event: AutomationEvent): Boolean {
        val current = mutableState.value
        val next = transition(current, event) ?: return false
        updateRecoveryRequirement(current, event, next)
        mutableState.value = next
        return true
    }

    private fun updateRecoveryRequirement(current: AutomationState, event: AutomationEvent, next: AutomationState) {
        if (next in setOf(AutomationState.IDLE, AutomationState.STOPPED)) {
            mutableRecoveryRequirement = RecoveryRequirement.NONE
            return
        }
        when (event) {
            AutomationEvent.WindowVerified -> mutableRecoveryRequirement = RecoveryRequirement.NONE
            AutomationEvent.Pause,
            AutomationEvent.TargetActivationTimedOut -> mutableRecoveryRequirement = RecoveryRequirement.EXPLICIT_CONFIRMATION
            AutomationEvent.RequestResume,
            AutomationEvent.TargetActivated -> mutableRecoveryRequirement = RecoveryRequirement.NONE
            is AutomationEvent.EnvironmentChanged -> {
                val required = if (event.reason == EnvironmentChangeReason.TARGET_NOT_ACTIVE) {
                    RecoveryRequirement.EXPLICIT_CONFIRMATION
                } else {
                    RecoveryRequirement.ENVIRONMENT_CHECK
                }
                if (current != AutomationState.PAUSED || required == RecoveryRequirement.ENVIRONMENT_CHECK) {
                    mutableRecoveryRequirement = required
                }
            }
            else -> if (next == AutomationState.ERROR) {
                mutableRecoveryRequirement = RecoveryRequirement.ENVIRONMENT_CHECK
            }
        }
    }

    internal fun transition(current: AutomationState, event: AutomationEvent): AutomationState? {
        if (current == AutomationState.STOPPED) {
            return when (event) {
                AutomationEvent.Stop -> AutomationState.STOPPED
                AutomationEvent.Reset, AutomationEvent.StartPrecheck -> AutomationState.IDLE
                else -> null
            }
        }
        return when (event) {
        AutomationEvent.Stop -> AutomationState.STOPPED
        is AutomationEvent.Fail -> AutomationState.ERROR
        AutomationEvent.Reset -> if (current in setOf(AutomationState.STOPPED, AutomationState.ERROR)) AutomationState.IDLE else null
        AutomationEvent.StartPrecheck -> if (current in setOf(AutomationState.IDLE, AutomationState.ERROR, AutomationState.WINDOW_CHECK, AutomationState.READY, AutomationState.PAUSED)) AutomationState.PRECHECK else null
        AutomationEvent.PermissionMissing -> if (current == AutomationState.PRECHECK) AutomationState.PERMISSION_REQUIRED else null
        AutomationEvent.DeviceReady -> if (current in setOf(AutomationState.PRECHECK, AutomationState.PERMISSION_REQUIRED)) AutomationState.DEVICE_READY else null
        AutomationEvent.CheckWindow -> if (current == AutomationState.DEVICE_READY) AutomationState.WINDOW_CHECK else null
        AutomationEvent.WindowVerified -> if (current == AutomationState.WINDOW_CHECK) AutomationState.READY else null
        AutomationEvent.BeginPlaceholder -> if (current == AutomationState.READY) AutomationState.WAIT_TARGET_ACTIVE else null
        AutomationEvent.TargetActivated -> if (current == AutomationState.WAIT_TARGET_ACTIVE) AutomationState.RUNNING_PLACEHOLDER else null
        AutomationEvent.TargetActivationTimedOut -> if (current == AutomationState.WAIT_TARGET_ACTIVE) AutomationState.PAUSED else null
        AutomationEvent.RequestResume -> if (
            current == AutomationState.PAUSED &&
            mutableRecoveryRequirement == RecoveryRequirement.EXPLICIT_CONFIRMATION
        ) AutomationState.WAIT_TARGET_ACTIVE else null
        AutomationEvent.Pause -> if (current == AutomationState.RUNNING_PLACEHOLDER) AutomationState.PAUSED else null
        is AutomationEvent.EnvironmentChanged -> when (current) {
            AutomationState.READY -> AutomationState.WINDOW_CHECK
            AutomationState.WAIT_TARGET_ACTIVE,
            AutomationState.RUNNING_PLACEHOLDER,
            AutomationState.PAUSED -> AutomationState.PAUSED
            else -> null
        }
        }
    }
}
