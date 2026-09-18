package com.lidquan.yishigame

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lidquan.yishigame.accessibility.GameAccessibilityService
import com.lidquan.yishigame.automation.AutomationEvent
import com.lidquan.yishigame.automation.AutomationState
import com.lidquan.yishigame.automation.AutomationStateMachine
import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.automation.WindowChange
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
import kotlinx.coroutines.launch

data class EnvironmentUiState(
    val automationState: AutomationState = AutomationState.IDLE,
    val accessibilityEnabled: Boolean = false,
    val captureState: ScreenCaptureState = ScreenCaptureState.NotRequested,
    val targetPackageInput: String = "",
    val targetDetected: Boolean = false,
    val windowBounds: WindowBounds? = null,
    val windowChange: WindowChange = WindowChange.UNAVAILABLE,
    val deviceSummary: String = "",
    val recentEvents: List<DiagnosticEvent> = emptyList(),
) {
    val canBeginPlaceholder: Boolean get() = automationState == AutomationState.READY
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val preferences = appContext.getSharedPreferences("local-device-config", Context.MODE_PRIVATE)
    private val stateMachine = AutomationStateMachine()
    private val windowMonitor = WindowMonitor()
    private val captureController: ScreenCaptureController = MediaProjectionScreenCaptureController(appContext)
    private val mutableUiState = MutableStateFlow(EnvironmentUiState(deviceSummary = deviceSummary()))
    val uiState: StateFlow<EnvironmentUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch { stateMachine.state.collect { refresh() } }
        viewModelScope.launch { captureController.state.collect { refresh() } }
        viewModelScope.launch { GameAccessibilityService.connected.collect { refresh() } }
        viewModelScope.launch { GameAccessibilityService.foregroundPackage.collect { refresh() } }
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
        if (!snapshot.accessibilityEnabled || snapshot.captureState !is ScreenCaptureState.Captured) {
            stateMachine.dispatch(AutomationEvent.PermissionMissing)
            record("PRECHECK", "PERMISSION_REQUIRED")
            return
        }
        if (!snapshot.targetDetected || snapshot.windowBounds == null) {
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
        if (stateMachine.dispatch(AutomationEvent.BeginPlaceholder)) {
            record("AUTOMATION_PLACEHOLDER", "STARTED")
        }
    }

    fun stop() {
        stateMachine.dispatch(AutomationEvent.Stop)
        record("STOP", "STOPPED")
    }

    private fun refresh() {
        val configuredPackage = preferences.getString(targetPackageKey, "").orEmpty()
        val foreground = GameAccessibilityService.foregroundPackage.value
        val targetBounds = GameAccessibilityService.instance
            ?.queryWindows()
            ?.firstOrNull { configuredPackage.isNotBlank() && it.packageName == configuredPackage }
            ?.bounds
        val change = windowMonitor.observe(targetBounds)
        if (change == WindowChange.CHANGED && stateMachine.state.value == AutomationState.RUNNING_PLACEHOLDER) {
            stateMachine.dispatch(AutomationEvent.Fail("WINDOW_BOUNDS_CHANGED"))
            record("WINDOW_MONITOR", "BLOCKED", "WINDOW_BOUNDS_CHANGED")
        }
        val priorInput = mutableUiState.value.targetPackageInput
        mutableUiState.value = EnvironmentUiState(
            automationState = stateMachine.state.value,
            accessibilityEnabled = isAccessibilityEnabled(),
            captureState = captureController.state.value,
            targetPackageInput = priorInput.ifBlank { configuredPackage },
            targetDetected = configuredPackage.isNotBlank() && foreground == configuredPackage,
            windowBounds = targetBounds,
            windowChange = change,
            deviceSummary = deviceSummary(),
            recentEvents = DiagnosticRecorder.events.value.takeLast(12).reversed(),
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (GameAccessibilityService.connected.value) return true
        val expected = ComponentName(appContext, GameAccessibilityService::class.java)
        val manager = appContext.getSystemService(AccessibilityManager::class.java)
        val reportedByManager = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { service ->
            val info = service.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == expected
        }
        if (reportedByManager) return true
        return Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')
            ?.mapNotNull(ComponentName::unflattenFromString)
            ?.any { it == expected }
            ?: false
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
    }
}
