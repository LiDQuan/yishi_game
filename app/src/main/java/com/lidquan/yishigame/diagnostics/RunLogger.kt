package com.lidquan.yishigame.diagnostics

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

class RunLogger(context: Context, val sessionId: String = UUID.randomUUID().toString()) {
    private val file = File(context.filesDir, "run-logs/$sessionId.jsonl").also { it.parentFile?.mkdirs() }
    private var lastStateFingerprint: String? = null
    private var sequence = 0L
    private var ended = false
    private val startedAt = System.nanoTime() / 1_000_000L
    private var gestureCount = 0
    private var actionCount = 0
    private var guardDenyCount = 0
    private var networkReconnectCount = 0
    private var inventoryAutoSellCount = 0

    init {
        file.createNewFile()
        check(file.canWrite()) { "RUN_LOG_UNAVAILABLE" }
    }

    @Synchronized
    fun event(
        eventType: String,
        businessState: String,
        automationState: String,
        result: String? = null,
        errorCode: String? = null,
        values: Map<String, Any?> = emptyMap(),
    ) {
        check(!ended) { "SESSION_ALREADY_ENDED" }
        append(eventType, businessState, automationState, result, errorCode, values)
        when (eventType) {
            "ACTION_INTENT" -> actionCount++
            "INPUT_DISPATCH" -> if (values["dispatchAccepted"] == true) gestureCount++
            "GUARD_DECISION" -> if (values["guardDecision"] == "DENY") guardDenyCount++
            "RECONNECT_ATTEMPT" -> networkReconnectCount++
            "AUTO_SELL_INTENT" -> inventoryAutoSellCount++
        }
    }

    @Synchronized
    fun end(businessState: String, automationState: String, result: String, errorCode: String?, values: Map<String, Any?> = emptyMap()) {
        if (ended) return
        append("SESSION_END", businessState, automationState, result, errorCode,
            values + mapOf("endedAt" to System.currentTimeMillis(), "totalDurationMs" to (System.nanoTime() / 1_000_000L - startedAt),
                "finalBusinessState" to businessState, "gestureCount" to gestureCount, "actionCount" to actionCount,
                "guardDenyCount" to guardDenyCount, "networkReconnectCount" to networkReconnectCount,
                "inventoryAutoSellCount" to inventoryAutoSellCount, "finalStablePage" to values["finalStablePage"],
                "finalResult" to result, "finalErrorCode" to errorCode))
        ended = true
    }

    val isEnded: Boolean get() = ended

    @Synchronized
    fun start(values: Map<String, Any?>) = event("SESSION_START", "PRECHECK", "PRECHECK", values = values)

    @Synchronized
    fun state(values: Map<String, Any?>) {
        val fingerprint = values.toSortedMap().toString()
        if (fingerprint == lastStateFingerprint) return
        lastStateFingerprint = fingerprint
        event("BUSINESS_STATE", values["businessState"]?.toString() ?: "UNKNOWN", "RUNNING_PLACEHOLDER", values = values)
    }

    @Synchronized
    fun action(values: Map<String, Any?>) = event("ACTION_FINISH", values["pageAfter"]?.toString() ?: "UNKNOWN", "RUNNING_PLACEHOLDER", values = values)

    @Synchronized
    fun finish(result: String, errorCode: String? = null) = end(result, "RUNNING_PLACEHOLDER", result, errorCode)

    private fun append(eventType: String, businessState: String, automationState: String, result: String?, errorCode: String?, values: Map<String, Any?>) {
        val json = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("monotonicMs", System.nanoTime() / 1_000_000L)
            .put("sessionId", sessionId)
            .put("sequence", ++sequence)
            .put("eventType", eventType)
            .put("businessState", businessState)
            .put("automationState", automationState)
            .put("result", result ?: JSONObject.NULL)
            .put("errorCode", errorCode ?: JSONObject.NULL)
        values.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        file.appendText(json.toString() + "\n")
    }

    companion object {
        fun finalizeOrphanedSessions(context: Context) {
            File(context.filesDir, "run-logs").listFiles { file -> file.extension == "jsonl" }?.forEach { file ->
                runCatching {
                    val raw = file.readText()
                    val events = raw.lineSequence().mapNotNull { line -> runCatching { JSONObject(line) }.getOrNull() }.toList()
                    if (events.isEmpty() || events.any { it.optString("eventType") == "SESSION_END" }) return@runCatching
                    val last = events.last()
                    val sessionId = last.optString("sessionId")
                    val sequence = events.maxOf { it.optLong("sequence") } + 1
                    val dispatched = events.filter { it.optString("eventType") == "INPUT_DISPATCH" && it.optBoolean("dispatchAccepted") }
                        .map { it.optString("actionId") }.toSet()
                    val finished = events.filter { it.optString("eventType") == "ACTION_FINISH" }
                        .map { it.optString("actionId") }.toSet()
                    val error = if ((dispatched - finished).isNotEmpty()) "INCOMPLETE_ACTION_TRACE" else "PROCESS_INTERRUPTED"
                    val terminal = JSONObject()
                        .put("timestamp", System.currentTimeMillis()).put("monotonicMs", System.nanoTime() / 1_000_000L)
                        .put("sessionId", sessionId).put("sequence", sequence).put("eventType", "SESSION_END")
                        .put("businessState", last.optString("businessState", "UNKNOWN"))
                        .put("automationState", "STOPPED").put("result", "FAILED").put("errorCode", error)
                        .put("endedAt", System.currentTimeMillis()).put("totalDurationMs", JSONObject.NULL)
                        .put("finalBusinessState", last.optString("businessState", "UNKNOWN"))
                        .put("finalStablePage", JSONObject.NULL).put("gestureCount", dispatched.size)
                        .put("actionCount", events.count { it.optString("eventType") == "ACTION_INTENT" })
                        .put("guardDenyCount", events.count { it.optString("eventType") == "GUARD_DECISION" && it.optString("guardDecision") == "DENY" })
                        .put("networkReconnectCount", events.count { it.optString("eventType") == "RECONNECT_ATTEMPT" })
                        .put("inventoryAutoSellCount", events.count { it.optString("eventType") == "AUTO_SELL_INTENT" })
                        .put("finalResult", "FAILED").put("finalErrorCode", error)
                    file.appendText((if (raw.endsWith("\n")) "" else "\n") + terminal.toString() + "\n")
                }
            }
        }
    }
}
