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
    private var req0006State: String = "IDLE"
    private var req0006Error: String? = null
    private val mutableUiState = MutableStateFlow(EnvironmentUiState(deviceSummary = deviceSummary()))
    val uiState: StateFlow<EnvironmentUiState> = mutableUiState.asStateFlow()

    init {
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
        if (req0006Job?.isActive == true) {
            record("REQ-0006", "BLOCKED", "ALREADY_RUNNING")
            return
        }
        val snapshot = mutableUiState.value
        val service = GameAccessibilityService.instance
        val configuredPackage = preferences.getString(targetPackageKey, "").orEmpty()
        val windows = service?.queryWindows().orEmpty()
        val gameWindow = windows
            .filter { configuredPackage.isNotBlank() && it.packageName == configuredPackage }
            .maxByOrNull { it.layer }
        val assistantWindow = windows
            .filter { it.packageName == appContext.packageName }
            .maxByOrNull { it.layer }
        val precheck = Req0006Precheck.evaluate(
            Req0006PrecheckInput(
                accessibilityEnabled = snapshot.accessibilityEnabled,
                captureActive = snapshot.captureState is ScreenCaptureState.Active,
                gameWindow = gameWindow?.bounds,
                assistantWindow = assistantWindow?.bounds,
                windowGate = snapshot.windowGate,
                contentViewport = snapshot.contentViewport,
                stablePageId = snapshot.visionMetrics.stablePage?.pageId,
            ),
        )
        if (precheck is Req0006PrecheckResult.Failed) {
            val error = "PRECHECK_" + precheck.reason.name
            req0006State = "PRECHECK_FAILED"
            req0006Error = error
            record("REQ-0006_PRECHECK", "FAILED", error)
            refresh()
            return
        }
        if (!snapshot.canArmPlaceholder) {
            req0006State = "PRECHECK_FAILED"
            req0006Error = "PRECHECK_ENVIRONMENT_NOT_READY"
            record("REQ-0006_PRECHECK", "FAILED", "PRECHECK_ENVIRONMENT_NOT_READY")
            refresh()
            return
        }
        record("REQ-0006_PRECHECK", "READY")
        req0006Pending = true
        req0006State = "WAIT_TARGET_ACTIVE"
        req0006Error = null
        req0006SessionId = null
        beginPlaceholder()
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
            }
        }
    }

    fun stop() {
        targetActivationTimeout?.cancel()
        req0006Job?.cancel()
        req0006Pending = false
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
            req0006Job = viewModelScope.launch { runReq0006() }
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

    private suspend fun runReq0006() {
        val logger = RunLogger(appContext)
        logger.start(mapOf(
            "startedAt" to System.currentTimeMillis(),
            "currentRoleKey" to "current-role",
            "appVersion" to appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName,
        ))
        req0006SessionId = logger.sessionId // public-scan: allow - runtime ID, not a credential
        req0006State = "STARTED"
        val dao = AppDatabase.get(appContext).dailyExecutionDao()
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val dailyId = "${dayKey}:current-role:first-free-dungeon"
        val prior = dao.findDaily(dayKey, "current-role", "DUNGEON", "first-free-dungeon")
        if (prior?.status == "SUCCESS") {
            finishReq0006(logger, "SKIPPED", "DAILY_ALREADY_SUCCESS")
            return
        }
        dao.upsert(DailyExecutionEntity(
            id = dailyId, gameDayKey = dayKey, roleId = "current-role", actionType = "DUNGEON",
            targetId = "first-free-dungeon", status = "RUNNING", attemptCount = (prior?.attemptCount ?: 0) + 1,
            startedAt = System.currentTimeMillis(),
        ))
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
        val deadline = System.currentTimeMillis() + req0006TimeoutMillis
        while (System.currentTimeMillis() < deadline && actions < req0006MaxActions) {
            if (stateMachine.state.value != AutomationState.RUNNING_PLACEHOLDER) {
                persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "ENVIRONMENT_CHANGED")
                return
            }
            val snapshot = mutableUiState.value
            val page = snapshot.visionMetrics.stablePage?.pageId
            val progress = snapshot.visionMetrics.progress
            req0006State = page ?: "WAIT_STABLE_PAGE"
            logger.state(mapOf(
                "businessState" to req0006State,
                "previousState" to previousBusinessState,
                "stablePage" to page,
                "currentMap" to snapshot.visionMetrics.currentMap,
                "currentDungeon" to snapshot.visionMetrics.currentDungeon,
                "freeAttemptState" to snapshot.visionMetrics.freeAttemptState.name,
                "progressCurrent" to progress?.current,
                "progressTotal" to progress?.total,
                "autoBattleState" to snapshot.visionMetrics.autoBattleState.name,
            ))
            previousBusinessState = req0006State
            if (progress?.current != null && progress.current != lastProgressCurrent) lastProgressCurrent = progress.current
            if (page == "BATTLE") {
                enteredBattle = true
                if (progress != null && progress.current == progress.total) dungeonCompleted = true
            }
            val intent = when (page) {
                "SAFE_DIALOG" -> {
                    persistAndFinishReq0006(logger, dao, runningRecord, "FAILED", "GAME_DIALOG_REQUIRES_USER")
                    return
                }
                "CHARACTER_SELECT" -> ActionIntent(ActionType.ENTER_GAME)
                "HOME" -> {
                    if (dungeonCompleted) {
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
            val execution = executor.execute(intent, actionContext(snapshot)) { expected, timeout -> awaitStablePage(expected, timeout) }
            actions++
            logAction(logger, intent, execution, actions)
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

    private suspend fun awaitStablePage(expected: Set<String>, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            mutableUiState.value.visionMetrics.stablePage?.pageId?.let { if (it in expected) return it }
            delay(250)
        }
        return null
    }

    private fun logAction(logger: RunLogger, intent: ActionIntent, execution: ActionExecution, retryIndex: Int) {
        val allow = execution.decision as? GuardDecision.AllowDryRun
        logger.action(mapOf(
            "ActionIntent" to intent.type.name,
            "policy" to com.lidquan.yishigame.action.ActionPolicyRegistry.policy(intent.type)?.riskLevel?.name,
            "pageBefore" to allow?.plan?.expectedPageBefore,
            "targetId" to com.lidquan.yishigame.action.ActionPolicyRegistry.policy(intent.type)?.requiredTargetId,
            "targetRect" to allow?.plan?.targetRect?.toString(),
            "GuardDecision" to execution.decision::class.simpleName,
            "tapResult" to execution.tapResult,
            "expectedPostcondition" to allow?.plan?.expectedPageAfter?.joinToString(),
            "pageAfter" to execution.pageAfter,
            "durationMs" to execution.durationMs,
            "retryIndex" to retryIndex,
            "errorCode" to if (execution.postconditionMet) null else "ACTION_${intent.type.name}_UNVERIFIED",
        ))
    }

    private suspend fun persistAndFinishReq0006(
        logger: RunLogger,
        dao: com.lidquan.yishigame.data.DailyExecutionDao,
        runningRecord: DailyExecutionEntity,
        result: String,
        error: String?,
    ) {
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
        logger.finish(result, error)
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
