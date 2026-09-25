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
import com.lidquan.yishigame.automation.Req0006Precheck
import com.lidquan.yishigame.automation.Req0006PrecheckInput
import com.lidquan.yishigame.automation.Req0006PrecheckResult
import com.lidquan.yishigame.automation.AutomationState
import com.lidquan.yishigame.automation.AutomationStateMachine
import com.lidquan.yishigame.automation.WindowBounds
import com.lidquan.yishigame.automation.WindowGate
import com.lidquan.yishigame.automation.WindowMonitor
import com.lidquan.yishigame.action.ActionContext
import com.lidquan.yishigame.action.ActionGuard
import com.lidquan.yishigame.action.ActionIntent
import com.lidquan.yishigame.action.ActionType
import com.lidquan.yishigame.action.GuardDecision
import com.lidquan.yishigame.action.ActionExecutor
import com.lidquan.yishigame.action.ActionExecution
import com.lidquan.yishigame.capture.MediaProjectionScreenCaptureController
import com.lidquan.yishigame.capture.ScreenCaptureController
import com.lidquan.yishigame.capture.ScreenCaptureState
import com.lidquan.yishigame.capture.FrameMetadata
import com.lidquan.yishigame.config.TargetGameConfig
import com.lidquan.yishigame.diagnostics.DiagnosticEvent
import com.lidquan.yishigame.diagnostics.DiagnosticRecorder
import com.lidquan.yishigame.diagnostics.RunLogger
import com.lidquan.yishigame.data.AppDatabase
import com.lidquan.yishigame.data.DailyExecutionEntity
import com.lidquan.yishigame.automation.AutoBattleState
import com.lidquan.yishigame.vision.VisionMetrics
import com.lidquan.yishigame.vision.VisionWorker
import com.lidquan.yishigame.vision.ConfiguredVisionEngine
import com.lidquan.yishigame.vision.ocr.MlKitChineseOcrEngine
import com.lidquan.yishigame.viewport.WindowGeometry
import com.lidquan.yishigame.viewport.ContentViewportDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.io.IOException
import kotlinx.coroutines.CancellationException

data class EnvironmentUiState(
    val automationState: AutomationState = AutomationState.IDLE,
    val recoveryRequirement: RecoveryRequirement = RecoveryRequirement.NONE,
    val accessibilityEnabled: Boolean = false,
    val captureState: ScreenCaptureState = ScreenCaptureState.NotRequested,
    val frameMetadata: FrameMetadata = FrameMetadata(),
    val visionMetrics: VisionMetrics = VisionMetrics(),
    val dryRunDecision: GuardDecision? = null,
    val targetPackageInput: String = "",
    val targetVisible: Boolean = false,
    val targetActive: Boolean = false,
    val windowBounds: WindowBounds? = null,
    val contentViewport: WindowBounds? = null,
    val windowGate: WindowGate = WindowGate.UNAVAILABLE,
    val deviceSummary: String = "",
    val recentEvents: List<DiagnosticEvent> = emptyList(),
    val req0006SessionId: String? = null, // public-scan: allow - runtime ID, not a credential
    val req0006State: String = "IDLE",
    val req0006Error: String? = null,
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
    private val visionEngine = ConfiguredVisionEngine(
        ocr = MlKitChineseOcrEngine(),
        viewport = { confirmedGeometry(mutableUiState.value.windowBounds)?.contentViewport },
    )
    private val visionWorker = VisionWorker(captureController.frameStore, analyze = visionEngine::analyze)
    private var targetActivationTimeout: Job? = null
    private var lastCaptureActive = false
    private var lastVisionPackage = ""
    private var lastVisionGate: WindowGate? = null
    private var req0006Pending = false
    private var req0006Job: Job? = null
    private var req0006SessionId: String? = null // public-scan: allow - runtime ID, not a credential
    private var req0006Logger: RunLogger? = null
    private var req0006PrecheckJob: Job? = null
    private var req0006State: String = "IDLE"
    private var req0006Error: String? = null
    private val mutableUiState = MutableStateFlow(EnvironmentUiState(deviceSummary = deviceSummary()))
    val uiState: StateFlow<EnvironmentUiState> = mutableUiState.asStateFlow()

    init {
        RunLogger.finalizeOrphanedSessions(appContext)
        viewModelScope.launch { stateMachine.state.collect { refresh() } }
        viewModelScope.launch { captureController.state.collect { refresh() } }
        viewModelScope.launch { captureController.frameStore.metadata.collect { refresh() } }
        viewModelScope.launch { visionWorker.metrics.collect { refresh() } }
        viewModelScope.launch { GameAccessibilityService.connected.collect { refresh() } }
        viewModelScope.launch { GameAccessibilityService.lastEventPackage.collect { refresh() } }
        viewModelScope.launch { DiagnosticRecorder.events.collect { refresh() } }
        refresh()
        visionWorker.start(viewModelScope) { windowMonitor.version }
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
        clearContentViewportProfile()
        visionWorker.invalidate()
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
        val frame = captureController.frameStore.latestSnapshot()
        val contentViewport = frame?.let { ContentViewportDetector.detect(it, snapshot.windowBounds) }
        if (contentViewport == null) {
            stateMachine.dispatch(AutomationEvent.Fail("ENV_CONTENT_VIEWPORT_UNCONFIRMED"))
            record("PRECHECK", "FAILED", "ENV_CONTENT_VIEWPORT_UNCONFIRMED")
            return
        }
        acceptContentViewportProfile(snapshot.windowBounds, contentViewport)
        visionWorker.invalidate()
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

    fun beginReq0006() {
        refresh()
        if (req0006Job?.isActive == true || req0006PrecheckJob?.isActive == true || req0006Pending) {
            record("REQ-0006", "BLOCKED", "ALREADY_RUNNING")
            return
        }
        val logger = runCatching { RunLogger(appContext) }.getOrElse {
            req0006State = "PRECHECK_FAILED"
            req0006Error = "RUN_LOG_UNAVAILABLE"
            record("REQ-0006", "FAILED", "RUN_LOG_UNAVAILABLE")
            refresh()
            return
        }
        req0006Logger?.takeIf { !it.isEnded }?.end(req0006State, stateMachine.state.value.name, "CANCELLED", "SUPERSEDED")
        req0006Logger = logger
        req0006SessionId = logger.sessionId // public-scan: allow - runtime ID, not a credential
        try {
            logger.start(mapOf("appVersion" to appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName))
            logger.event("PRECHECK_START", "PRECHECK", stateMachine.state.value.name)
        } catch (_: Exception) {
            req0006State = "PRECHECK_FAILED"
            req0006Error = "RUN_LOG_UNAVAILABLE"
            record("REQ-0006", "FAILED", req0006Error)
            refresh()
            return
        }
        req0006PrecheckJob = viewModelScope.launch {
            try { performReq0006Precheck(logger) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                req0006Pending = false
                req0006State = "PRECHECK_FAILED"
                req0006Error = "RUN_LOG_UNAVAILABLE"
                record("REQ-0006", "FAILED", req0006Error)
            }
            finally { if (!logger.isEnded && !req0006Pending) runCatching { logger.end(req0006State, stateMachine.state.value.name,
                if (req0006State == "PRECHECK_FAILED") "FAILED" else "CANCELLED", req0006Error ?: "PRECHECK_INTERRUPTED",
                mapOf("finalStablePage" to mutableUiState.value.visionMetrics.stablePage?.pageId)) } }
        }
    }

    private suspend fun performReq0006Precheck(logger: RunLogger) {
        val deadline = System.nanoTime() / 1_000_000L + 5_000L
        var precheck: Req0006PrecheckResult
        lateinit var snapshot: EnvironmentUiState
        var gameWindowFound = false
        var assistantWindowFound = false
        var gameBounds: WindowBounds? = null
        var assistantBounds: WindowBounds? = null
        do {
            refresh()
            snapshot = mutableUiState.value
            val service = GameAccessibilityService.instance
            val configuredPackage = preferences.getString(targetPackageKey, "").orEmpty()
            val windows = service?.queryWindows().orEmpty()
            val gameWindow = windows
                .filter { configuredPackage.isNotBlank() && it.packageName == configuredPackage }
                .maxByOrNull { it.layer }
            val assistantWindow = windows
                .filter { it.packageName == appContext.packageName }
                .maxByOrNull { it.layer }
            gameWindowFound = gameWindow != null
            assistantWindowFound = assistantWindow != null
            gameBounds = gameWindow?.bounds
            assistantBounds = assistantWindow?.bounds
            precheck = Req0006Precheck.evaluate(
                Req0006PrecheckInput(
                    accessibilityEnabled = snapshot.accessibilityEnabled,
                    captureActive = snapshot.captureState is ScreenCaptureState.Active,
                    gameWindow = gameWindow?.bounds,
                    assistantWindow = assistantWindow?.bounds,
                    windowGate = snapshot.windowGate,
                    contentViewport = snapshot.contentViewport,
                    stablePageId = snapshot.visionMetrics.stablePage
                        ?.takeIf { it.viewportVersion == windowMonitor.version && System.currentTimeMillis() - it.observedAt in 0..2_000 }
                        ?.pageId,
                ),
            )
            if (precheck is Req0006PrecheckResult.Failed && precheck.reason == com.lidquan.yishigame.automation.Req0006PrecheckFailure.GAME_PAGE_NOT_STABLE && System.nanoTime() / 1_000_000L < deadline) {
                delay(250)
                continue
            }
            break
        } while (true)
        val stablePage = snapshot.visionMetrics.stablePage?.takeIf {
            it.viewportVersion == windowMonitor.version && System.currentTimeMillis() - it.observedAt in 0..2_000
        }
        val layoutValid = gameBounds != null && assistantBounds != null &&
            Req0006Precheck.isGameLeftAssistantRight(gameBounds, assistantBounds)
        listOf(
            "ACCESSIBILITY" to snapshot.accessibilityEnabled,
            "CAPTURE" to (snapshot.captureState is ScreenCaptureState.Active),
            "GAME_WINDOW" to gameWindowFound,
            "ASSISTANT_WINDOW" to assistantWindowFound,
            "WINDOW_GATE" to (snapshot.windowGate == WindowGate.MATCHED),
            "CONTENT_VIEWPORT" to (snapshot.contentViewport?.isValid == true),
            "LAYOUT" to layoutValid,
            "STABLE_PAGE" to (stablePage != null),
            "HOME" to (stablePage?.pageId == "HOME"),
        ).forEach { (check, passed) -> logger.event("PRECHECK_CHECK", "PRECHECK", stateMachine.state.value.name,
            if (passed) "PASS" else "FAIL", values = mapOf("check" to check)) }
        logger.event("PRECHECK_CHECKS", "PRECHECK", stateMachine.state.value.name,
            result = if (precheck is Req0006PrecheckResult.Ready) "READY" else "FAILED",
            errorCode = (precheck as? Req0006PrecheckResult.Failed)?.reason?.name,
            values = mapOf("accessibility" to snapshot.accessibilityEnabled, "capture" to (snapshot.captureState is ScreenCaptureState.Active),
                "gameWindow" to gameWindowFound, "assistantWindow" to assistantWindowFound, "windowGate" to snapshot.windowGate.name,
                "viewportConfirmed" to (snapshot.contentViewport?.isValid == true), "stablePage" to snapshot.visionMetrics.stablePage?.pageId))
        if (precheck is Req0006PrecheckResult.Failed) {
            val error = "PRECHECK_" + precheck.reason.name
            req0006State = "PRECHECK_FAILED"
            req0006Error = error
            record("REQ-0006_PRECHECK", "FAILED", error)
            logger.event("PRECHECK_FAILED", "PRECHECK_FAILED", stateMachine.state.value.name, "FAILED", error)
            logger.end("PRECHECK_FAILED", stateMachine.state.value.name, "FAILED", error)
            refresh()
            return
        }
        if (!snapshot.canArmPlaceholder) {
            req0006State = "PRECHECK_FAILED"
            req0006Error = "PRECHECK_ENVIRONMENT_NOT_READY"
            record("REQ-0006_PRECHECK", "FAILED", "PRECHECK_ENVIRONMENT_NOT_READY")
            logger.event("PRECHECK_FAILED", "PRECHECK_FAILED", stateMachine.state.value.name, "FAILED", "PRECHECK_ENVIRONMENT_NOT_READY")
            logger.end("PRECHECK_FAILED", stateMachine.state.value.name, "FAILED", "PRECHECK_ENVIRONMENT_NOT_READY")
            refresh()
            return
        }
        record("REQ-0006_PRECHECK", "READY")
        logger.event("PRECHECK_READY", "WAIT_TARGET_ACTIVE", stateMachine.state.value.name, "READY")
        req0006Pending = true
        req0006State = "WAIT_TARGET_ACTIVE"
        req0006Error = null
        beginPlaceholder()
        if (stateMachine.state.value != AutomationState.WAIT_TARGET_ACTIVE) {
            logger.end("PRECHECK_FAILED", stateMachine.state.value.name, "FAILED", "TARGET_HANDSHAKE_NOT_ARMED")
            req0006Pending = false
        }
        refresh()
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
                if (req0006Pending) {
                    req0006Pending = false
                    req0006State = "FAILED"
                    req0006Error = "TARGET_ACTIVATION_TIMEOUT"
                    req0006Logger?.end(req0006State, stateMachine.state.value.name, "FAILED", req0006Error)
                }
            }
        }
    }

    fun stop() {
        targetActivationTimeout?.cancel()
        val precheckWasActive = req0006PrecheckJob?.isActive == true
        val runWasActive = req0006Job?.isActive == true
        req0006PrecheckJob?.cancel()
        req0006Job?.cancel()
        req0006Pending = false
        if (!precheckWasActive && !runWasActive) req0006Logger?.takeIf { !it.isEnded }
            ?.end(req0006State, stateMachine.state.value.name, "CANCELLED", "USER_STOPPED",
                mapOf("finalStablePage" to mutableUiState.value.visionMetrics.stablePage?.pageId))
        stateMachine.dispatch(AutomationEvent.Stop)
        captureController.stop()
        visionWorker.invalidate()
        record("STOP", "STOPPED")
    }

    override fun onCleared() {
        visionWorker.stop()
        visionEngine.close()
        super.onCleared()
    }

    fun evaluateDungeonAnchorDryRun() {
        val state = mutableUiState.value
        val decision = dungeonAnchorDryRun(state)
        mutableUiState.value = state.copy(dryRunDecision = decision)
        record("ACTION_GUARD_DRY_RUN", if (decision is GuardDecision.AllowDryRun) "ALLOW_DRY_RUN" else "DENY")
    }

    private fun dungeonAnchorDryRun(state: EnvironmentUiState): GuardDecision =
        ActionGuard.plan(
            ActionIntent(ActionType.OPEN_DUNGEON_LIST),
            ActionContext(
                automationState = state.automationState,
                accessibilityConnected = state.accessibilityEnabled,
                captureState = state.captureState,
                targetVisible = state.targetVisible,
                targetActive = state.targetActive,
                windowGate = state.windowGate,
                contentViewport = state.contentViewport,
                stablePage = state.visionMetrics.stablePage,
                currentViewportVersion = windowMonitor.version,
                now = System.currentTimeMillis(),
                actionTargets = state.visionMetrics.actionTargets,
            ),
        )

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
        if (
            captureActive != lastCaptureActive ||
            configuredPackage != lastVisionPackage ||
            (gate != lastVisionGate && gate != WindowGate.MATCHED)
        ) {
            visionWorker.invalidate()
        }
        lastCaptureActive = captureActive
        lastVisionPackage = configuredPackage
        lastVisionGate = gate
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
        if (req0006Pending && stateMachine.state.value == AutomationState.RUNNING_PLACEHOLDER && req0006Job?.isActive != true) {
            req0006Pending = false
            val logger = req0006Logger
            if (logger != null && !logger.isEnded) req0006Job = viewModelScope.launch {
                try { runReq0006(logger) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    val error = if (failure is IOException || failure.message == "RUN_LOG_UNAVAILABLE") "RUN_LOG_UNAVAILABLE" else "RUN_INTERNAL_ERROR"
                    req0006State = "FAILED"
                    req0006Error = error
                    runCatching { logger.event("RUN_FAILED", "FAILED", stateMachine.state.value.name, "FAILED", error) }
                    record("REQ-0006", "FAILED", error)
                }
                finally { runCatching { logger.end(req0006State, stateMachine.state.value.name,
                    if (req0006State == "FAILED") "FAILED" else "CANCELLED",
                    req0006Error ?: "RUN_INTERRUPTED",
                    mapOf("finalStablePage" to mutableUiState.value.visionMetrics.stablePage?.pageId)) } }
            }
        }
        if (req0006Pending && stateMachine.state.value in setOf(AutomationState.PAUSED, AutomationState.ERROR, AutomationState.STOPPED)) {
            req0006Pending = false
            req0006State = "FAILED"
            req0006Error = "ENVIRONMENT_CHANGED"
            runCatching { req0006Logger?.end(req0006State, stateMachine.state.value.name, "FAILED", req0006Error,
                mapOf("finalStablePage" to mutableUiState.value.visionMetrics.stablePage?.pageId)) }
        }
        val priorInput = mutableUiState.value.targetPackageInput
        val refreshed = EnvironmentUiState(
            automationState = stateMachine.state.value,
            recoveryRequirement = stateMachine.recoveryRequirement,
            accessibilityEnabled = accessibilityEnabled,
            captureState = captureController.state.value,
            frameMetadata = captureController.frameStore.metadata.value,
            visionMetrics = visionWorker.metrics.value,
            dryRunDecision = mutableUiState.value.dryRunDecision,
            targetPackageInput = priorInput.ifBlank { configuredPackage },
            targetVisible = targetVisible,
            targetActive = targetActive,
            windowBounds = targetWindow?.bounds,
            contentViewport = confirmedGeometry(targetWindow?.bounds)?.contentViewport,
            windowGate = gate,
            deviceSummary = deviceSummary(),
            recentEvents = DiagnosticRecorder.events.value.takeLast(12).reversed(),
            req0006SessionId = req0006SessionId, // public-scan: allow - runtime ID, not a credential
            req0006State = req0006State,
            req0006Error = req0006Error,
        )
        mutableUiState.value = refreshed.copy(dryRunDecision = dungeonAnchorDryRun(refreshed))
    }

    private suspend fun runReq0006(logger: RunLogger) {
        req0006State = "STARTED"
        val dao = AppDatabase.get(appContext).dailyExecutionDao()
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        // ponytail: single configured role/dungeon only; replace this key before multi-dungeon scheduling.
        val dailyId = "${dayKey}:current-role:first-free-dungeon"
        val prior = dao.findDaily(dayKey, "current-role", "DUNGEON", "first-free-dungeon")
        logger.event("DAILY_EXECUTION_LOOKUP", "STARTED", stateMachine.state.value.name, "OK",
            values = mapOf("businessKey" to "current-role:first-free-dungeon", "priorStatus" to prior?.status))
        if (prior?.status == "SUCCESS") {
            logger.event("DAILY_EXECUTION_SKIPPED", "SKIPPED", stateMachine.state.value.name, "SKIPPED",
                values = mapOf("businessKey" to "current-role:first-free-dungeon"))
            finishReq0006(logger, "SKIPPED", "DAILY_ALREADY_SUCCESS")
            return
        }
        dao.upsert(DailyExecutionEntity(
            id = dailyId, gameDayKey = dayKey, roleId = "current-role", actionType = "DUNGEON",
            targetId = "first-free-dungeon", status = "RUNNING", attemptCount = (prior?.attemptCount ?: 0) + 1,
            startedAt = System.currentTimeMillis(),
        ))
        logger.event("DAILY_EXECUTION_RUNNING", "STARTED", stateMachine.state.value.name, "RUNNING")
        val runningRecord = requireNotNull(dao.findDaily(dayKey, "current-role", "DUNGEON", "first-free-dungeon"))
        val controller = GameAccessibilityService.instance
        if (controller == null) {
            persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "ACCESSIBILITY_UNAVAILABLE")
            return
        }
        val executor = ActionExecutor(controller)
        var actions = 0
        var lastProgressCurrent: Int? = null
        var previousBusinessState: String? = null
        var dungeonCompleted = false
        var enteredBattle = false
        var areaSelectionIssued = false
        var reconnectAttempts = 0
        var sellAttempts = 0
        var disconnectDetectedAt = 0L
        var inventoryDetectedAt = 0L
        var progressBeforeRecovery: Int? = null
        val deadline = System.currentTimeMillis() + req0006TimeoutMillis
        while (System.currentTimeMillis() < deadline && actions < req0006MaxActions) {
            if (stateMachine.state.value != AutomationState.RUNNING_PLACEHOLDER) {
                persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "ENVIRONMENT_CHANGED")
                return
            }
            val snapshot = mutableUiState.value
            val page = snapshot.visionMetrics.stablePage?.takeIf {
                it.viewportVersion == windowMonitor.version && System.currentTimeMillis() - it.observedAt in 0..2_000
            }?.pageId
            val progress = snapshot.visionMetrics.progress
            req0006State = page ?: "WAIT_STABLE_PAGE"
            logger.state(mapOf(
                "businessState" to req0006State,
                "previousState" to previousBusinessState,
                "stablePage" to page,
                "currentMap" to snapshot.visionMetrics.currentMap,
                "currentDungeon" to snapshot.visionMetrics.currentDungeon,
                "freeAttemptState" to snapshot.visionMetrics.freeAttemptState.name,
                "freeCurrent" to snapshot.visionMetrics.freeAttempts?.current,
                "freeTotal" to snapshot.visionMetrics.freeAttempts?.total,
                "viewportVersion" to snapshot.visionMetrics.stablePage?.viewportVersion,
                "pageDetection" to snapshot.visionMetrics.pageDetection.javaClass.simpleName,
                "progressCurrent" to progress?.current,
                "progressTotal" to progress?.total,
                "autoBattleState" to snapshot.visionMetrics.autoBattleState.name,
            ))
            val priorBusinessState = previousBusinessState
            previousBusinessState = req0006State
            if (page == "BATTLE" && progress?.current != null && progress.current != lastProgressCurrent) {
                logger.event("BATTLE_PROGRESS", "BATTLE", stateMachine.state.value.name, "OBSERVED",
                    values = mapOf("current" to progress.current, "total" to progress.total))
                lastProgressCurrent = progress.current
            }
            if (page == "BATTLE") {
                enteredBattle = true
                if (progress != null && progress.current == progress.total) dungeonCompleted = true
            }
            val intent = when (page) {
                "NETWORK_DISCONNECTED" -> {
                    if (disconnectDetectedAt == 0L) {
                        disconnectDetectedAt = System.currentTimeMillis()
                        progressBeforeRecovery = progress?.current
                        logger.event("NETWORK_DISCONNECTED", page, stateMachine.state.value.name, "DETECTED",
                            values = mapOf("disconnectDetectedAt" to disconnectDetectedAt, "pageBeforeDisconnect" to priorBusinessState,
                                "businessStateBeforeDisconnect" to priorBusinessState, "progressBefore" to progressBeforeRecovery))
                    }
                    if (reconnectAttempts >= 2) {
                        logger.event("SERVER_RECONNECT_FAILED", page, stateMachine.state.value.name, "FAILED", "SERVER_RECONNECT_FAILED")
                        persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "SERVER_RECONNECT_FAILED")
                        return
                    }
                    reconnectAttempts++
                    logger.event("RECONNECT_ATTEMPT", page, stateMachine.state.value.name, "STARTED",
                        values = mapOf("attemptIndex" to reconnectAttempts, "maxAttempts" to 2,
                            "disconnectDetectedAt" to disconnectDetectedAt, "progressBefore" to progressBeforeRecovery))
                    ActionIntent(ActionType.RECONNECT_GAME)
                }
                "INVENTORY_FULL" -> {
                    if (inventoryDetectedAt == 0L) {
                        inventoryDetectedAt = System.currentTimeMillis()
                        logger.event("INVENTORY_FULL_DETECTED", page, stateMachine.state.value.name, "DETECTED",
                            values = mapOf("pageBefore" to priorBusinessState, "businessStateBefore" to priorBusinessState))
                    }
                    if (sellAttempts >= 2) {
                        logger.event("AUTO_SELL_UNVERIFIED", page, stateMachine.state.value.name, "FAILED", "AUTO_SELL_UNVERIFIED")
                        persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "AUTO_SELL_UNVERIFIED")
                        return
                    }
                    sellAttempts++
                    logger.event("AUTO_SELL_INTENT", page, stateMachine.state.value.name, "STARTED",
                        values = mapOf("retryIndex" to sellAttempts, "maxAttempts" to 2))
                    ActionIntent(ActionType.AUTO_SELL_INVENTORY)
                }
                "SAFE_DIALOG" -> {
                    persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "GAME_DIALOG_REQUIRES_USER")
                    return
                }
                "CHARACTER_SELECT" -> ActionIntent(ActionType.ENTER_GAME)
                "HOME" -> {
                    if (dungeonCompleted) {
                        logger.event("RETURN_HOME_CONFIRMED", "HOME", stateMachine.state.value.name, "CONFIRMED",
                            values = mapOf("stablePage" to page))
                        logger.event("DAILY_EXECUTION_SUCCESS", "HOME", stateMachine.state.value.name, "SUCCESS")
                        dao.upsert(runningRecord.copy(status = "SUCCESS", finishedAt = System.currentTimeMillis(), resultCode = "SUCCESS", errorCode = null))
                        finishReq0006(logger, "SUCCESS", null)
                        return
                    }
                    if (enteredBattle) {
                        persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "DUNGEON_INCOMPLETE")
                        return
                    }
                    ActionIntent(ActionType.OPEN_AREA_MAP)
                }
                "AREA_MAP" -> when {
                    !areaSelectionIssued -> ActionIntent(ActionType.OPEN_AREA_SWITCH)
                    snapshot.visionMetrics.currentMap == req0006TargetArea -> ActionIntent(ActionType.OPEN_DUNGEON_LIST)
                    else -> {
                        persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "AREA_SWITCH_UNVERIFIED")
                        return
                    }
                }
                "AREA_PICKER" -> ActionIntent(ActionType.SELECT_AREA)
                "DUNGEON_LIST" -> ActionIntent(ActionType.SELECT_DUNGEON)
                "DUNGEON_DETAIL" -> {
                    if (snapshot.visionMetrics.freeAttemptState != com.lidquan.yishigame.vision.FreeAttemptState.AVAILABLE) {
                        persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "FREE_ATTEMPT_UNCONFIRMED")
                        return
                    }
                    ActionIntent(ActionType.START_DUNGEON_FREE)
                }
                "BATTLE" -> when {
                    progress != null && progress.current == progress.total -> {
                        delay(1_000)
                        continue
                    }
                    snapshot.visionMetrics.autoBattleState == AutoBattleState.OFF -> ActionIntent(ActionType.ENABLE_AUTO_BATTLE)
                    snapshot.visionMetrics.autoBattleState == AutoBattleState.MISSING -> {
                        persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "MANUAL_BATTLE_REQUIRED")
                        return
                    }
                    snapshot.visionMetrics.autoBattleState == AutoBattleState.UNKNOWN -> {
                        persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "AUTO_BATTLE_UNCONFIRMED")
                        return
                    }
                    else -> {
                        delay(1_000)
                        continue
                    }
                }
                else -> {
                    delay(500)
                    continue
                }
            }
            val actionId = UUID.randomUUID().toString()
            val execution = executeLoggedAction(logger, executor, intent, snapshot, actions + 1, actionId)
            actions++
            if (intent.type == ActionType.START_DUNGEON_FREE && execution.tapResult == true) enteredBattle = true
            if (intent.type == ActionType.RECONNECT_GAME) {
                logger.event("RECONNECT_ACTION", page ?: "UNKNOWN", stateMachine.state.value.name,
                    if (execution.tapResult == true) "DISPATCHED" else "BLOCKED", values = mapOf("attemptIndex" to reconnectAttempts, "actionId" to actionId))
                logger.event("RECONNECT_WAIT", page ?: "UNKNOWN", stateMachine.state.value.name,
                    if (execution.postconditionMet) "RECOVERED" else "UNVERIFIED",
                    values = mapOf("recoveredPage" to execution.pageAfter, "actionId" to actionId))
                if (execution.postconditionMet) {
                    logger.event("NETWORK_RECOVERED", execution.pageAfter ?: "UNKNOWN", stateMachine.state.value.name, "RECOVERED",
                        values = mapOf("disconnectDetectedAt" to disconnectDetectedAt, "attemptIndex" to reconnectAttempts,
                            "maxAttempts" to 2, "recoveredPage" to execution.pageAfter,
                            "recoveryDurationMs" to (System.currentTimeMillis() - disconnectDetectedAt),
                            "progressBefore" to progressBeforeRecovery, "progressAfter" to mutableUiState.value.visionMetrics.progress?.current))
                    disconnectDetectedAt = 0L
                    reconnectAttempts = 0
                    continue
                }
                if (execution.decision is GuardDecision.Deny || execution.tapResult != true) {
                    persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "SERVER_RECONNECT_FAILED")
                    return
                }
                continue
            }
            if (intent.type == ActionType.AUTO_SELL_INVENTORY) {
                logger.event("AUTO_SELL_POSTCONDITION", page ?: "UNKNOWN", stateMachine.state.value.name,
                    if (execution.postconditionMet) "CONFIRMED" else "UNVERIFIED",
                    values = mapOf("dialogGone" to execution.postconditionMet, "pageAfter" to execution.pageAfter,
                        "retryIndex" to sellAttempts, "actionId" to actionId))
                if (execution.postconditionMet) {
                    logger.event("INVENTORY_RECOVERED", execution.pageAfter ?: "UNKNOWN", stateMachine.state.value.name, "RECOVERED",
                        values = mapOf("recoveryDurationMs" to (System.currentTimeMillis() - inventoryDetectedAt),
                            "pageAfter" to execution.pageAfter))
                    inventoryDetectedAt = 0L
                    sellAttempts = 0
                    continue
                }
                if (execution.decision is GuardDecision.Deny || execution.tapResult != true) {
                    persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "AUTO_SELL_UNVERIFIED")
                    return
                }
                continue
            }
            if (execution.tapResult == true && !execution.postconditionMet &&
                mutableUiState.value.visionMetrics.stablePage?.pageId in setOf("NETWORK_DISCONNECTED", "INVENTORY_FULL")) continue
            if (execution.decision is GuardDecision.Deny || execution.tapResult != true || !execution.postconditionMet) {
                persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "ACTION_${intent.type.name}_BLOCKED_OR_UNVERIFIED")
                return
            }
            if (intent.type == ActionType.SELECT_AREA) areaSelectionIssued = true
        }
        persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", if (actions >= req0006MaxActions) "MAX_ACTIONS_REACHED" else "RUN_TIMEOUT")
    }

    private fun actionContext(state: EnvironmentUiState) = ActionContext(
        automationState = state.automationState,
        accessibilityConnected = state.accessibilityEnabled,
        captureState = state.captureState,
        targetVisible = state.targetVisible,
        targetActive = state.targetActive,
        windowGate = state.windowGate,
        contentViewport = state.contentViewport,
        stablePage = state.visionMetrics.stablePage,
        currentViewportVersion = windowMonitor.version,
        now = System.currentTimeMillis(),
        actionTargets = state.visionMetrics.actionTargets,
        freeAttemptState = state.visionMetrics.freeAttemptState,
        dailyAlreadySuccess = false,
    )

    private suspend fun awaitStablePage(expected: Set<String>, timeoutMs: Long, afterTap: Long, intent: ActionType, logger: RunLogger, actionId: String): String? {
        val deadline = System.nanoTime() / 1_000_000L + timeoutMs
        var lastEvidence: Map<String, Any?>? = null
        while (System.nanoTime() / 1_000_000L < deadline) {
            val state = mutableUiState.value
            val page = state.visionMetrics.stablePage
            val evidence = mapOf("actionId" to actionId, "stablePage" to page?.pageId,
                "currentMap" to state.visionMetrics.currentMap, "viewportVersion" to windowMonitor.version,
                "pageDetection" to state.visionMetrics.pageDetection.javaClass.simpleName,
                "evidenceIds" to state.visionMetrics.evidenceIds.joinToString())
            if (evidence != lastEvidence) {
                logger.event("POSTCONDITION_OBSERVATION", req0006State, state.automationState.name, values = evidence)
                lastEvidence = evidence
            }
            if (page != null && page.pageId in expected && page.observedAt >= afterTap &&
                state.automationState == AutomationState.RUNNING_PLACEHOLDER && state.targetActive &&
                state.windowGate == WindowGate.MATCHED && page.viewportVersion == windowMonitor.version && System.currentTimeMillis() - page.observedAt in 0..2_000 &&
                (intent != ActionType.SELECT_AREA || state.visionMetrics.currentMap == req0006TargetArea) &&
                (intent != ActionType.ENABLE_AUTO_BATTLE || state.visionMetrics.autoBattleState == AutoBattleState.ON)) return page.pageId
            delay(250)
        }
        return null
    }

    private suspend fun executeLoggedAction(logger: RunLogger, executor: ActionExecutor, intent: ActionIntent,
        state: EnvironmentUiState, retryIndex: Int, actionId: String): ActionExecution {
        val policy = com.lidquan.yishigame.action.ActionPolicyRegistry.policy(intent.type)
        val pageBefore = state.visionMetrics.stablePage
        val common = mapOf("actionId" to actionId, "actionType" to intent.type.name, "pageBefore" to pageBefore?.pageId,
            "stablePageAgeMs" to pageBefore?.let { System.currentTimeMillis() - it.observedAt },
            "viewportVersion" to windowMonitor.version, "targetId" to policy?.requiredTargetId,
            "policy" to policy?.riskLevel?.name, "ActionIntent" to intent.type.name,
            "riskLevel" to policy?.riskLevel?.name, "expectedPostcondition" to policy?.expectedPagesAfter?.joinToString(),
            "retryIndex" to retryIndex)
        logger.event("ACTION_INTENT", req0006State, stateMachine.state.value.name, "PLANNED", values = common)
        var dispatchAt = 0L
        var dispatched = false
        var finished = false
        try {
            val execution = executor.execute(intent, actionContext(state),
                onGuardDecision = { decision ->
                    val allow = decision as? GuardDecision.AllowDryRun
                    if (allow != null) dispatchAt = System.currentTimeMillis()
                    logger.event("GUARD_DECISION", req0006State, stateMachine.state.value.name,
                        if (allow == null) "DENY" else "ALLOW", (decision as? GuardDecision.Deny)?.reason?.name,
                        common + mapOf("guardDecision" to (if (allow == null) "DENY" else "ALLOW"),
                            "denyReason" to (decision as? GuardDecision.Deny)?.reason?.name,
                            "targetRect" to allow?.plan?.targetRect?.toString(), "tapPoint" to allow?.plan?.tapPoint?.toString()))
                    if (intent.type == ActionType.AUTO_SELL_INVENTORY) logger.event("AUTO_SELL_GUARD_DECISION", req0006State,
                        stateMachine.state.value.name, if (allow == null) "DENY" else "ALLOW",
                        (decision as? GuardDecision.Deny)?.reason?.name, common + mapOf("guardDecision" to (if (allow == null) "DENY" else "ALLOW"),
                            "targetRect" to allow?.plan?.targetRect?.toString()))
                },
                onTapDispatched = { plan, accepted ->
                    dispatched = accepted
                    if (accepted) {
                        dispatchAt = System.currentTimeMillis()
                        visionWorker.invalidate()
                        refresh()
                    }
                    logger.event("INPUT_DISPATCH", req0006State, stateMachine.state.value.name,
                        if (accepted) "ACCEPTED" else "REJECTED", values = common + mapOf(
                            "targetRect" to plan.targetRect.toString(), "tapPoint" to plan.tapPoint.toString(),
                            "dispatchAccepted" to accepted))
                    if (intent.type == ActionType.AUTO_SELL_INVENTORY) logger.event("AUTO_SELL_DISPATCH", req0006State,
                        stateMachine.state.value.name, if (accepted) "ACCEPTED" else "REJECTED",
                        values = common + mapOf("targetRect" to plan.targetRect.toString(), "dispatchAccepted" to accepted))
                },
            ) { expected, timeout -> awaitStablePage(expected, timeout, dispatchAt, intent.type, logger, actionId) }
            logger.event("POSTCONDITION_RESULT", req0006State, stateMachine.state.value.name,
                if (execution.postconditionMet) "CONFIRMED" else "UNVERIFIED",
                if (execution.postconditionMet) null else "ACTION_${intent.type.name}_UNVERIFIED",
                common + mapOf("pageAfter" to execution.pageAfter, "durationMs" to execution.durationMs))
            logger.event("ACTION_FINISH", req0006State, stateMachine.state.value.name,
                if (execution.postconditionMet) "SUCCESS" else "FAILED",
                if (execution.postconditionMet) null else "ACTION_${intent.type.name}_UNVERIFIED",
                common + mapOf("pageAfter" to execution.pageAfter, "durationMs" to execution.durationMs,
                    "dispatchAccepted" to execution.tapResult, "tapResult" to execution.tapResult))
            finished = true
            return execution
        } finally {
            if (!finished) {
                runCatching { logger.event("POSTCONDITION_RESULT", req0006State, stateMachine.state.value.name, "CANCELLED",
                    "ACTION_INTERRUPTED", common + mapOf("dispatchAccepted" to dispatched)) }
                runCatching { logger.event("ACTION_FINISH", req0006State, stateMachine.state.value.name, "CANCELLED",
                    "ACTION_INTERRUPTED", common + mapOf("dispatchAccepted" to dispatched)) }
            }
        }
    }

    private suspend fun persistAndFinishReq0006(
        logger: RunLogger,
        dao: com.lidquan.yishigame.data.DailyExecutionDao,
        runningRecord: DailyExecutionEntity,
        result: String,
        error: String?,
    ) {
        logger.event("DAILY_EXECUTION_FAILED", req0006State, stateMachine.state.value.name, result, error)
        dao.upsert(runningRecord.copy(
            status = result,
            finishedAt = System.currentTimeMillis(),
            resultCode = result,
            errorCode = error,
        ))
        finishReq0006(logger, result, error)
    }

    private fun finishReq0006(logger: RunLogger, result: String, error: String?) {
        req0006State = result
        req0006Error = error
        logger.end(req0006State, stateMachine.state.value.name, result, error,
            mapOf("finalStablePage" to mutableUiState.value.visionMetrics.stablePage?.pageId))
        stateMachine.dispatch(AutomationEvent.RunFinished)
        record("REQ-0006", result, error)
        refresh()
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

    private fun acceptContentViewportProfile(window: WindowBounds, content: WindowBounds) {
        preferences.edit()
            .putInt(profileWindowLeftKey, window.left)
            .putInt(profileWindowTopKey, window.top)
            .putInt(profileWindowRightKey, window.right)
            .putInt(profileWindowBottomKey, window.bottom)
            .putInt(contentViewportLeftKey, content.left)
            .putInt(contentViewportTopKey, content.top)
            .putInt(contentViewportRightKey, content.right)
            .putInt(contentViewportBottomKey, content.bottom)
            .putLong(contentViewportProfileVersionKey, windowMonitor.version)
            .apply()
    }

    private fun confirmedGeometry(currentWindow: WindowBounds?): WindowGeometry? {
        if (currentWindow == null || !preferences.contains(contentViewportProfileVersionKey)) return null
        val profileWindow = WindowBounds(
            preferences.getInt(profileWindowLeftKey, 0), preferences.getInt(profileWindowTopKey, 0),
            preferences.getInt(profileWindowRightKey, 0), preferences.getInt(profileWindowBottomKey, 0),
        )
        val geometry = WindowGeometry(
            windowBounds = profileWindow,
            contentViewport = WindowBounds(
                preferences.getInt(contentViewportLeftKey, 0), preferences.getInt(contentViewportTopKey, 0),
                preferences.getInt(contentViewportRightKey, 0), preferences.getInt(contentViewportBottomKey, 0),
            ),
            profileVersion = preferences.getLong(contentViewportProfileVersionKey, 0),
        )
        return geometry.takeIf { it.isConfirmed && it.windowBounds == currentWindow }
    }

    private fun clearContentViewportProfile() {
        preferences.edit().remove(contentViewportProfileVersionKey).apply()
    }

    companion object {
        private const val targetPackageKey = "target_game_package"
        private const val targetActivationTimeoutMillis = 30_000L
        private const val profileWindowLeftKey = "profile_window_left"
        private const val profileWindowTopKey = "profile_window_top"
        private const val profileWindowRightKey = "profile_window_right"
        private const val profileWindowBottomKey = "profile_window_bottom"
        private const val contentViewportLeftKey = "content_viewport_left"
        private const val contentViewportTopKey = "content_viewport_top"
        private const val contentViewportRightKey = "content_viewport_right"
        private const val contentViewportBottomKey = "content_viewport_bottom"
        private const val contentViewportProfileVersionKey = "content_viewport_profile_version"
        private const val req0006TimeoutMillis = 10 * 60_000L
        private const val req0006MaxActions = 20
        private const val req0006TargetArea = "亡灵之地"
    }
}
