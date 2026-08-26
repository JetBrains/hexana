package org.jetbrains.hexana.firmware.debug

import com.intellij.testFramework.UsefulTestCase
import kotlinx.serialization.json.*

/**
 * Unit tests for [renderEvaluateResponse].
 *
 * The function is pure over (response JsonObject) -> List<String>, so no process or IDE
 * session is needed. Each test checks an exact shape of the DAP `evaluate` response body.
 */
class GdbDapConsoleTransportTest : UsefulTestCase() {

    // -----------------------------------------------------------------------
    // Success responses
    // -----------------------------------------------------------------------

    fun testSuccess_singleLine_returnsSingleElement() {
        val body = buildJsonObject {
            put("success", true)
            putJsonObject("body") { put("result", "0x20001234") }
        }
        val lines = renderEvaluateResponse(body)
        assertEquals(listOf("0x20001234"), lines)
    }

    fun testSuccess_multiLine_returnsAllLines() {
        val body = buildJsonObject {
            put("success", true)
            putJsonObject("body") { put("result", "line one\nline two\nline three") }
        }
        val lines = renderEvaluateResponse(body)
        assertEquals(listOf("line one", "line two", "line three"), lines)
    }

    fun testSuccess_trailingBlankLines_areDropped() {
        val body = buildJsonObject {
            put("success", true)
            putJsonObject("body") { put("result", "output\n\n") }
        }
        val lines = renderEvaluateResponse(body)
        assertEquals(listOf("output"), lines)
    }

    fun testSuccess_emptyResult_returnsEmptyList() {
        val body = buildJsonObject {
            put("success", true)
            putJsonObject("body") { put("result", "") }
        }
        val lines = renderEvaluateResponse(body)
        assertTrue("Expected empty list for empty result, got $lines", lines.isEmpty())
    }

    fun testSuccess_missingBodyResult_returnsEmptyList() {
        val body = buildJsonObject {
            put("success", true)
            putJsonObject("body") { /* no 'result' key */ }
        }
        val lines = renderEvaluateResponse(body)
        assertTrue("Expected empty list when result is absent, got $lines", lines.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Error responses
    // -----------------------------------------------------------------------

    fun testError_topLevelMessage_returnsErrorLine() {
        val body = buildJsonObject {
            put("success", false)
            put("message", "No symbol table is loaded.")
        }
        val lines = renderEvaluateResponse(body)
        assertEquals(1, lines.size)
        assertTrue(
            "Expected line starting with 'error:', got '${lines[0]}'",
            lines[0].startsWith("error:"),
        )
        assertTrue(
            "Expected original message in output, got '${lines[0]}'",
            lines[0].contains("No symbol table is loaded."),
        )
    }

    fun testError_bodyErrorFormat_returnsErrorLine() {
        val body = buildJsonObject {
            put("success", false)
            putJsonObject("body") {
                putJsonObject("error") { put("format", "Evaluation failed: unknown identifier") }
            }
        }
        val lines = renderEvaluateResponse(body)
        assertEquals(1, lines.size)
        assertTrue(
            "Expected 'error:' prefix, got '${lines[0]}'",
            lines[0].startsWith("error:"),
        )
        assertTrue(
            "Expected format string in output, got '${lines[0]}'",
            lines[0].contains("Evaluation failed: unknown identifier"),
        )
    }

    fun testError_noMessageField_returnsFallback() {
        val body = buildJsonObject {
            put("success", false)
            // Neither 'message' nor body.error.format present.
        }
        val lines = renderEvaluateResponse(body)
        assertEquals(1, lines.size)
        assertTrue(
            "Expected 'error:' prefix in fallback, got '${lines[0]}'",
            lines[0].startsWith("error:"),
        )
    }

    /** success=false must NOT return a non-error line even if 'body.result' is also present. */
    fun testError_withResultPresent_stillReturnsError() {
        val body = buildJsonObject {
            put("success", false)
            put("message", "Something went wrong")
            putJsonObject("body") { put("result", "should be ignored") }
        }
        val lines = renderEvaluateResponse(body)
        assertEquals(1, lines.size)
        assertFalse(
            "Error response must not return the result value, got '${lines[0]}'",
            lines[0].contains("should be ignored"),
        )
    }
}
