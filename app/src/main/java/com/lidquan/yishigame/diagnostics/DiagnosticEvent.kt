package com.lidquan.yishigame.diagnostics

import android.util.Log
import com.lidquan.yishigame.automation.AutomationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiagnosticEvent(
    val timestamp: Long,
    val eventType: String,
    val state: AutomationState,
    val result: String,
    val errorCode: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

object DiagnosticRecorder {
    private const val maxEvents = 100
    private val mutableEvents = MutableStateFlow<List<DiagnosticEvent>>(emptyList())
    val events: StateFlow<List<DiagnosticEvent>> = mutableEvents.asStateFlow()

    @Synchronized
    fun record(event: DiagnosticEvent) {
        mutableEvents.value = (mutableEvents.value + event).takeLast(maxEvents)
        Log.i(
            "YishiDiagnostic",
            "timestamp=${event.timestamp} eventType=${event.eventType} state=${event.state} result=${event.result} errorCode=${event.errorCode.orEmpty()} metadataKeys=${event.metadata.keys.sorted().joinToString(",")}",
        )
    }
}
