package com.lidquan.yishigame.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lidquan.yishigame.EnvironmentUiState
import com.lidquan.yishigame.MainViewModel
import com.lidquan.yishigame.automation.AutomationState
import com.lidquan.yishigame.automation.RecoveryRequirement
import com.lidquan.yishigame.capture.ScreenCaptureState
import com.lidquan.yishigame.vision.PageDetection
import com.lidquan.yishigame.action.GuardDecision

@Composable
fun AssistantApp(
    viewModel: MainViewModel,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestCapture: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    var showDiagnostics by remember { mutableStateOf(false) }
    var showVision by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                if (maxWidth >= 840.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Overview(state, Modifier.weight(1f))
                        Controls(
                            state = state,
                            modifier = Modifier.weight(1f),
                            viewModel = viewModel,
                            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                            onRequestCapture = onRequestCapture,
                            onShowDiagnostics = { showDiagnostics = true },
                            onShowVision = { showVision = true },
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Overview(state, Modifier.fillMaxWidth())
                        Controls(
                            state = state,
                            modifier = Modifier.fillMaxWidth(),
                            viewModel = viewModel,
                            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                            onRequestCapture = onRequestCapture,
                            onShowDiagnostics = { showDiagnostics = true },
                            onShowVision = { showVision = true },
                        )
                    }
                }
            }
        }
    }

    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            confirmButton = { TextButton(onClick = { showDiagnostics = false }) { Text("关闭") } },
            title = { Text("最近诊断事件") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (state.recentEvents.isEmpty()) Text("暂无事件")
                    state.recentEvents.forEach { event ->
                        Text("${event.eventType} · ${event.state} · ${event.result}${event.errorCode?.let { " · $it" }.orEmpty()}")
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
        )
    }
    if (showVision) {
        AlertDialog(
            onDismissRequest = { showVision = false },
            confirmButton = { TextButton(onClick = { showVision = false }) { Text("关闭") } },
            title = { Text("Vision Debug（只读）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusRow("Viewport", state.windowBounds?.let { "${it.left},${it.top} · ${it.width}×${it.height}" } ?: "未确认")
                    StatusRow("WindowGate", state.windowGate.name)
                    StatusRow("Frame", "#${state.frameMetadata.frameId} · %.1f capture FPS".format(state.frameMetadata.captureFps))
                    StatusRow("Vision", "%.1f FPS · ${state.visionMetrics.lastVisionDurationMs} ms".format(state.visionMetrics.visionFps))
                    StatusRow("P95", "${state.visionMetrics.p95VisionDurationMs} ms")
                    StatusRow("OCR / Page", "${state.visionMetrics.ocrDurationMs} / ${state.visionMetrics.pageDetectorDurationMs} ms")
                    StatusRow("Detection", detectionLabel(state.visionMetrics.pageDetection))
                    StatusRow("StablePage", state.visionMetrics.stablePage?.let { "${it.pageId} · %.3f".format(it.confidence) } ?: "无")
                    StatusRow("FreeAttempt", state.visionMetrics.freeAttemptState.name)
                    StatusRow("Action Guard", guardLabel(state.dryRunDecision))
                    Button(onClick = viewModel::evaluateOpenSettingsDryRun, modifier = Modifier.fillMaxWidth()) {
                        Text("评估 OPEN_SETTINGS Dry-Run")
                    }
                    Text("当前没有已确认的公开/私有页面模板时，结果必须保持 UNKNOWN。")
                }
            },
        )
    }
}

@Composable
private fun Overview(state: EnvironmentUiState, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("异世界勇者自动化助手", style = MaterialTheme.typography.headlineMedium)
            Text("M1 视觉基础设施验证版，只识别与 Dry-Run，不执行游戏输入。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusRow("当前状态", state.automationState.name)
            StatusRow("辅助功能", if (state.accessibilityEnabled) "已启用" else "未启用")
            StatusRow("屏幕采集", captureLabel(state.captureState))
            StatusRow("Frame", state.frameMetadata.frameId.takeIf { it > 0 }?.let { "#$it · %.1f FPS".format(state.frameMetadata.captureFps) } ?: "无")
            StatusRow(
                "目标游戏",
                when {
                    state.targetActive -> "可见 · 前台已激活"
                    state.targetVisible -> "可见 · 请切回游戏"
                    else -> "未识别"
                },
            )
            StatusRow("窗口检测", state.windowBounds?.let { "${it.width} × ${it.height} · ${state.windowGate}" } ?: state.windowGate.name)
            StatusRow("设备摘要", state.deviceSummary)
        }
    }
}

@Composable
private fun Controls(
    state: EnvironmentUiState,
    modifier: Modifier,
    viewModel: MainViewModel,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestCapture: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onShowVision: () -> Unit,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("环境与控制", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = state.targetPackageInput,
                onValueChange = viewModel::updateTargetPackage,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("已确认的目标游戏包名（仅保存在本机）") },
                singleLine = true,
            )
            OutlinedButton(onClick = viewModel::saveTargetPackage, modifier = Modifier.fillMaxWidth()) { Text("保存本机配置") }
            OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) { Text("打开辅助功能设置") }
            OutlinedButton(
                onClick = onRequestCapture,
                enabled = state.captureState !is ScreenCaptureState.Active,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("请求并验证屏幕采集") }
            Button(onClick = viewModel::runEnvironmentCheck, modifier = Modifier.fillMaxWidth()) { Text("环境检查") }
            Button(
                onClick = viewModel::beginPlaceholder,
                enabled = state.canArmPlaceholder,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("准备启动（M0 占位）") }
            if (state.automationState == AutomationState.WAIT_TARGET_ACTIVE) {
                Text("握手第 2 阶段：请切回游戏窗口；只有窗口、焦点和采集状态均通过检查后才会继续。", color = MaterialTheme.colorScheme.primary)
            }
            if (state.automationState == AutomationState.PAUSED) {
                if (state.recoveryRequirement == RecoveryRequirement.EXPLICIT_CONFIRMATION) {
                    Text("已暂停。恢复必须由你显式发起，然后再切回游戏完成安全握手。")
                    Button(
                        onClick = viewModel::requestResumePlaceholder,
                        enabled = state.canRequestResume,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("显式恢复并重新握手") }
                } else {
                    Text("窗口、采集或辅助功能环境发生变化，不能直接恢复；处理后请重新执行环境检查。")
                }
            }
            OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) { Text("停止") }
            TextButton(onClick = onShowDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("查看诊断") }
            TextButton(onClick = onShowVision, modifier = Modifier.fillMaxWidth()) { Text("Vision Debug（只读）") }
        }
    }
}

private fun detectionLabel(detection: PageDetection): String = when (detection) {
    is PageDetection.Matched -> "MATCHED ${detection.pageId} · %.3f".format(detection.confidence)
    is PageDetection.Ambiguous -> "AMBIGUOUS · ${detection.candidates.joinToString { it.pageId }}"
    is PageDetection.Unknown -> "UNKNOWN"
}

private fun guardLabel(decision: GuardDecision?): String = when (decision) {
    null -> "未评估"
    is GuardDecision.AllowDryRun -> "ALLOW_DRY_RUN（不会执行）"
    is GuardDecision.Deny -> "DENY · ${decision.reason}"
}

@Composable
private fun StatusRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun captureLabel(state: ScreenCaptureState): String = when (state) {
    ScreenCaptureState.NotRequested -> "未请求"
    ScreenCaptureState.PermissionDenied -> "用户拒绝"
    ScreenCaptureState.Capturing -> "采集中"
    is ScreenCaptureState.Active -> "会话中 ${state.width} × ${state.height} · ${state.frameCount} 帧"
    is ScreenCaptureState.Stopped -> "已停止：${state.reason}"
    is ScreenCaptureState.Failed -> "失败：${state.code}"
}
