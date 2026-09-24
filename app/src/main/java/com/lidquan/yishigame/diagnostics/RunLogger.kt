package com.lidquan.yishigame.diagnostics

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

class RunLogger(context: Context, val sessionId: String = UUID.randomUUID().toString()) {
    private val file = File(context.filesDir, "run-logs/$sessionId.jsonl").also { it.parentFile?.mkdirs() }
    private var lastStateFingerprint: String? = null

    @Synchronized
    fun start(values: Map<String, Any?>) = append("START", values)

    @Synchronized
    fun state(values: Map<String, Any?>) {
        val fingerprint = values.toSortedMap().toString()
        if (fingerprint == lastStateFingerprint) return
        lastStateFingerprint = fingerprint
        append("STATE", values)
    }

    @Synchronized
    fun action(values: Map<String, Any?>) = append("ACTION", values)

    @Synchronized
    fun finish(result: String, errorCode: String? = null) =
        append("RESULT", mapOf("result" to result, "errorCode" to errorCode))

    private fun append(kind: String, values: Map<String, Any?>) {
        val json = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("sessionId", sessionId)
            .put("kind", kind)
        values.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
        file.appendText(json.toString() + "\n")
    }
}
