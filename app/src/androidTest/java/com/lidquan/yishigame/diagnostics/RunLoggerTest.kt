package com.lidquan.yishigame.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunLoggerTest {
    @Test fun sessionHasOrderedEventsAndOneTerminalResult() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logger = RunLogger(context)
        logger.start(mapOf("appVersion" to "test"))
        logger.event("PRECHECK_START", "PRECHECK", "PRECHECK")
        logger.event("ACTION_INTENT", "HOME", "RUNNING_PLACEHOLDER", values = mapOf("actionId" to "test-action"))
        logger.event("GUARD_DECISION", "HOME", "RUNNING_PLACEHOLDER", "DENY",
            values = mapOf("actionId" to "test-action", "guardDecision" to "DENY"))
        logger.end("FAILED", "READY", "FAILED", "TEST_DENIED")
        logger.end("FAILED", "READY", "SUCCESS", null)
        val file = File(context.filesDir, "run-logs/${logger.sessionId}.jsonl")
        val lines = file.readLines().map { JSONObject(it) }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), lines.map { it.getLong("sequence") })
        assertEquals(1, lines.count { it.getString("eventType") == "SESSION_END" })
        assertEquals("FAILED", lines.last().getString("finalResult"))
        assertEquals(1, lines.last().getInt("guardDenyCount"))
        assertFalse(lines.any { it.getString("eventType") == "INPUT_DISPATCH" })
        assertTrue(lines.all { it.has("timestamp") && it.has("monotonicMs") && it.has("businessState") && it.has("automationState") })
        file.delete()
    }

    @Test fun interruptedInputIsMarkedIncompleteBeforeNextSession() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logger = RunLogger(context)
        logger.start(emptyMap())
        logger.event("ACTION_INTENT", "HOME", "RUNNING_PLACEHOLDER", values = mapOf("actionId" to "interrupted"))
        logger.event("INPUT_DISPATCH", "HOME", "RUNNING_PLACEHOLDER", "ACCEPTED",
            values = mapOf("actionId" to "interrupted", "dispatchAccepted" to true))
        RunLogger.finalizeOrphanedSessions(context)
        val file = File(context.filesDir, "run-logs/${logger.sessionId}.jsonl")
        val lines = file.readLines().map { JSONObject(it) }
        assertEquals("INCOMPLETE_ACTION_TRACE", lines.last().getString("errorCode"))
        assertEquals(1, lines.last().getInt("gestureCount"))
        file.delete()
    }
}
