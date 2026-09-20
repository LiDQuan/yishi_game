package com.lidquan.yishigame.automation

enum class AutomationState {
    IDLE,
    PRECHECK,
    PERMISSION_REQUIRED,
    DEVICE_READY,
    WINDOW_CHECK,
    READY,
    RUNNING_PLACEHOLDER,
    PAUSED,
    ERROR,
    STOPPED,
}

sealed interface AutomationEvent {
    data object StartPrecheck : AutomationEvent
    data object PermissionMissing : AutomationEvent
    data object DeviceReady : AutomationEvent
    data object CheckWindow : AutomationEvent
    data object WindowVerified : AutomationEvent
    data object BeginPlaceholder : AutomationEvent
    data object Pause : AutomationEvent
    data object Resume : AutomationEvent
    data object EnvironmentChanged : AutomationEvent
    data object Stop : AutomationEvent
    data object Reset : AutomationEvent
    data class Fail(val errorCode: String) : AutomationEvent
}
