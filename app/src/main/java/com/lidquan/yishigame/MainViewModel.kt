package com.lidquan.yishigame

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lidquan.yishigame.accessibility.GameAccessibilityService
import com.lidquan.yishigame.automation.AutomationEvent
import com.lidquan.yishigame.automation.EnvironmentChangeReason
import com.lidquan.yishigame.automation.RecoveryRequirement
import com.lidquan.yishigame.automation.AutomationState
import com.lidquan.yishigame.automation.AutomationStateMachine
import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.automation.WindowGate
import com.lidquan.yishigame.automation.WindowMonitor
import com.lidquan.yishigame.capture.MediaProjectionScreenCaptureController
import com.lidquan.yishigame.capture.ScreenCaptureController
import com.lidquan.yishigame.capture.ScreenCaptureState
import com.lidquan.yishigame.config.TargetGameConfig
import com.lidquan.yishigame.diagnostics.DiagnosticEvent
import com.lidquan.yishigame.diagnostics.DiagnosticRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class EnvironmentUiState(
    val automationState: AutomationState = AutomationState.IDLE,
    val recoveryRequirement: RecoveryRequirement = RecoveryRequirement.NONE,
    val accessibilityEnabled: Boolean = false,
    val captureState: ScreenCaptureState = ScreenCaptureState.NotRequested,
    val latestFrameBytes: Int = 0,
    val targetPackageInput: String = "",
    val targetVisible: Boolean = false,
    val targetActive: Boolean = false,
    val windowBounds: WindowBounds? = null,
    val windowGate: WindowGate = WindowGate.UNAVAILABLE,
    val deviceSummary: String = "",
    val recentEvents: List<DiagnosticEvent> = emptyList(),
) {
    val canArmPlaceholder: Boolean get() =
        automationState == AutomationState.READY &&
            accessibilityEnabled &&
            captureState is ScreenCaptureState.Active &&
            targetVisible &&
            windowGate == WindowGate.MATCHED

    val canRequestResume: Boolean get() =
        automationState == AutomationState.PAUSED &&
            recoveryRequirement == RecoveryRequirement.EXPLICIT_CONFIRMATION &&
            accessibilityEnabled &&
            captureState is ScreenCaptureState.Active &&
            targetVisible &&
            windowGate == WindowGate.MATCHED
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val preferences = appContext.getSharedPreferences("local-device-config", Context.MODE_PRIVATE)
    private val stateMachine = AutomationStateMachine()
    private val windowMonitor = WindowMonitor()
    private val captureController: ScreenCaptureController = MediaProjectionScreenCaptureController(appContext)
    private var targetActivationTimeout: Job? = null
    private val mutableUiState = MutableStateFlow(EnvironmentUiState(deviceSummary = deviceSummary()))
    val uiState: StateFlow<EnvironmentUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch { stateMachine.state.collect { refresh() } }
        viewModelScope.launch { captureController.state.collect { refresh() } }
        viewModelScope.launch { captureController.latestFrame.collect { refresh() } }
        viewModelScope.launch { GameAccessibilityService.connected.collect { refresh() } }
        viewModelScope.launch { GameAccessibilityService.lastEventPackage.collect { refresh() } }
        viewModelScope.launch { DiagnosticRecorder.events.collect { refresh() } }
        refresh()
    }

    fun capturePermissionIntent(): Intent = captureController.permissionIntent()

    fun handleCapturePermissionResult(resultCode: Int, data: Intent?) {
        captureController.handlePermissionResult(resultCode, data)
    }

    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun updateTargetPackage(raw: String) {
        mutableUiState.value = mutableUiState.value.copy(targetPackageInput = raw)
    }

    fun saveTargetPackage() {
        val config = TargetGameConfig.parse(mutableUiState.value.targetPackageInput)
        preferences.edit().putString(targetPackageKey, config.packageName.orEmpty()).apply()
        record("TARGET_CONFIG", if (config.packageName == null) "INVALID_OR_EMPTY" else "SAVED")
        windowMonitor.clear()
        refresh()
    }

    fun runEnvironmentCheck() {
        if (stateMachine.state.value in setOf(AutomationState.ERROR, AutomationState.STOPPED)) {
            stateMachine.dispatch(AutomationEvent.Reset)
        }
        if (!stateMachine.dispatch(AutomationEvent.StartPrecheck)) return
        refresh()
        val snapshot = mutableUiState.value
        if (!snapshot.accessibilityEnabled || snapshot.captureState !is ScreenCaptureState.Active) {
            stateMachine.dispatch(AutomationEvent.PermissionMissing)
            record("PRECHECK", "PERMISSION_REQUIRED")
            return
        }
        if (!snapshot.targetVisible || snapshot.windowBounds == null || windowMonitor.accept(snapshot.windowBounds) != WindowGate.MATCHED) {
            stateMachine.dispatch(AutomationEvent.Fail("ENV_TARGET_WINDOW_NOT_READY"))
            record("PRECHECK", "FAILED", "ENV_TARGET_WINDOW_NOT_READY")
            return
        }
        stateMachine.dispatch(AutomationEvent.DeviceReady)
        stateMachine.dispatch(AutomationEvent.CheckWindow)
        stateMachine.dispatch(AutomationEvent.WindowVerified)
        record("PRECHECK", "READY")
    }

    fun beginPlaceholder() {
        refresh()
        if (!mutableUiState.value.canArmPlaceholder) {
            record("AUTOMATION_PLACEHOLDER", "BLOCKED", "ENVIRONMENT_CHANGED")
            return
        }
        if (stateMachine.dispatch(AutomationEvent.BeginPlaceholder)) {
            record("AUTOMATION_PLACEHOLDER", "ARMED")
            scheduleTargetActivationTimeout()
        }
    }

    fun requestResumePlaceholder() {
        refresh()
        if (!mutableUiState.value.canRequestResume) {
            record("AUTOMATION_PLACEHOLDER", "RESUME_BLOCKED", "ENVIRONMENT_RECHECK_REQUIRED")
            return
        }
        if (stateMachine.dispatch(AutomationEvent.RequestResume)) {
            record("AUTOMATION_PLACEHOLDER", "RESUME_ARMED")
            scheduleTargetActivationTimeout()
        }
    }

    private fun scheduleTargetActivationTimeout() {
        targetActivationTimeout?.cancel()
        targetActivationTimeout = viewModelScope.launch {
            delay(targetActivationTimeoutMillis)
            if (stateMachine.dispatch(AutomationEvent.TargetActivationTimedOut)) {
                record("AUTOMATION_PLACEHOLDER", "PAUSED", "TARGET_ACTIVATION_TIMEOUT")
            }
        }
    }

    fun stop() {
        targetActivationTimeout?.cancel()
        stateMachine.dispatch(AutomationEvent.Stop)
        captureController.stop()
        record("STOP", "STOPPED")
    }

    private fun refresh() {
        val configuredPackage = preferences.getString(targetPackageKey, "").orEmpty()
        val service = GameAccessibilityService.instance
        val targetWindow = service
            ?.queryWindows()
            ?.filter {
                configuredPackage.isNotBlank() &&
                    it.packageName == configuredPackage
            }
            ?.maxByOrNull { it.layer }
        val targetVisible = targetWindow != null
        val targetActive = targetWindow?.let { it.isActive || it.isFocused } ?: false
        val gate = windowMonitor.observe(targetWindow?.bounds)
        val currentState = stateMachine.state.value
        val captureActive = captureController.state.value is ScreenCaptureState.Active
        val accessibilityEnabled = GameAccessibilityService.connected.value
        val handshakeStates = setOf(
            AutomationState.WAIT_TARGET_ACTIVE,
            AutomationState.RUNNING_PLACEHOLDER,
            AutomationState.PAUSED,
        )
        val environmentChangeReason = when {
            gate == WindowGate.CHANGED -> EnvironmentChangeReason.WINDOW_CHANGED
            gate == WindowGate.UNAVAILABLE -> EnvironmentChangeReason.WINDOW_UNAVAILABLE
            !captureActive -> EnvironmentChangeReason.CAPTURE_INACTIVE
            !accessibilityEnabled -> EnvironmentChangeReason.ACCESSIBILITY_UNAVAILABLE
            else -> null
        }
        if (
            environmentChangeReason != null &&
            (currentState in handshakeStates || currentState == AutomationState.READY)
        ) {
            stateMachine.dispatch(AutomationEvent.EnvironmentChanged(environmentChangeReason))
            record("WINDOW_MONITOR", "BLOCKED", environmentChangeReason.name)
        } else if (currentState == AutomationState.RUNNING_PLACEHOLDER && !targetActive) {
            stateMachine.dispatch(AutomationEvent.EnvironmentChanged(EnvironmentChangeReason.TARGET_NOT_ACTIVE))
            record("WINDOW_MONITOR", "BLOCKED", EnvironmentChangeReason.TARGET_NOT_ACTIVE.name)
        }
        if (stateMachine.state.value == AutomationState.WAIT_TARGET_ACTIVE && targetActive && gate == WindowGate.MATCHED && captureActive) {
            targetActivationTimeout?.cancel()
            if (stateMachine.dispatch(AutomationEvent.TargetActivated)) {
                record("AUTOMATION_PLACEHOLDER", "STARTED")
            }
        }
        val priorInput = mutableUiState.value.targetPackageInput
        mutableUiState.value = EnvironmentUiState(
            automationState = stateMachine.state.value,
            recoveryRequirement = stateMachine.recoveryRequirement,
            accessibilityEnabled = accessibilityEnabled,
            captureState = captureController.state.value,
            latestFrameBytes = captureController.latestFrame.value?.rgba?.size ?: 0,
            targetPackageInput = priorInput.ifBlank { configuredPackage },
            targetVisible = targetVisible,
            targetActive = targetActive,
            windowBounds = targetWindow?.bounds,
            windowGate = gate,
            deviceSummary = deviceSummary(),
            recentEvents = DiagnosticRecorder.events.value.takeLast(12).reversed(),
        )
    }

    private fun record(eventType: String, result: String, errorCode: String? = null) {
        DiagnosticRecorder.record(
            DiagnosticEvent(
                timestamp = System.currentTimeMillis(),
                eventType = eventType,
                state = stateMachine.state.value,
                result = result,
                errorCode = errorCode,
            ),
        )
    }

    private fun deviceSummary(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    companion object {
        private const val targetPackageKey = "target_game_package"
        private const val targetActivationTimeoutMillis = 30_000L
    }
}
