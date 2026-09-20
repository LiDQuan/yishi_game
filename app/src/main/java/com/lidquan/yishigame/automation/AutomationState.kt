package com.lidquan.yishigame.automation

enum class AutomationState {
    IDLE,
    PRECHECK,
    PERMISSION_REQUIRED,
    DEVICE_READY,
    WINDOW_CHECK,
    READY,
    WAIT_TARGET_ACTIVE,
    RUNNING_PLACEHOLDER,
    PAUSED,
    ERROR,
    STOPPED,
}

enum class RecoveryRequirement {
    NONE,
    EXPLICIT_CONFIRMATION,
    ENVIRONMENT_CHECK,
}

enum class EnvironmentChangeReason {
    TARGET_NOT_ACTIVE,
    WINDOW_CHANGED,
    WINDOW_UNAVAILABLE,
    CAPTURE_INACTIVE,
    ACCESSIBILITY_UNAVAILABLE,
}

sealed interface AutomationEvent {
    data object StartPrecheck : AutomationEvent
    data object PermissionMissing : AutomationEvent
    data object DeviceReady : AutomationEvent
    data object CheckWindow : AutomationEvent
    data object WindowVerified : AutomationEvent
    data object BeginPlaceholder : AutomationEvent
    data object TargetActivated : AutomationEvent
    data object TargetActivationTimedOut : AutomationEvent
    data object RequestResume : AutomationEvent
    data object Pause : AutomationEvent
    data class EnvironmentChanged(val reason: EnvironmentChangeReason) : AutomationEvent
    data object Stop : AutomationEvent
    data object Reset : AutomationEvent
    data class Fail(val errorCode: String) : AutomationEvent
}
