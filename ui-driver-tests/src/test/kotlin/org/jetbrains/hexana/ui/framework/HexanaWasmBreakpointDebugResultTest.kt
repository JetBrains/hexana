package org.jetbrains.hexana.ui.framework

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HexanaWasmBreakpointDebugResultTest {
    @Test
    fun successWithPausedAndResumedMarkersAndResolvedVariablesPasses() {
        val result = HexanaWasmBreakpointDebugResult.parse(
            """
            PAUSED
            a=3
            b=7
            __vmctx=<unavailable>
            memory=0x1
            RESUMED
            """.trimIndent(),
        )

        val success = result.assertStrict()

        assertEquals(mapOf("a" to "3", "b" to "7"), success.userVariables)
    }

    @Test
    fun duplicateVariablesKeepTheLastObservedValue() {
        val result = HexanaWasmBreakpointDebugResult.parse(
            """
            PAUSED
            a=Stack
            a=3
            b=7
            RESUMED
            """.trimIndent(),
        )

        val success = result.assertStrict()

        assertEquals(mapOf("a" to "3", "b" to "7"), success.userVariables)
    }

    @Test
    fun timeoutIsRejected() {
        val error = assertThrows(AssertionError::class.java) {
            HexanaWasmBreakpointDebugResult.parse("TIMEOUT").assertStrict()
        }

        assertTrue(error.message!!.contains("timed out"))
        assertTrue(error.message!!.contains("rawOutput=TIMEOUT"))
    }

    @Test
    fun detailedTimeoutReportIsRejectedAsTimeout() {
        val error = assertThrows(AssertionError::class.java) {
            HexanaWasmBreakpointDebugResult.parse(
                """
                TIMEOUT
                timeoutMs=60000
                currentSessionSuspended=false
                """.trimIndent(),
            ).assertStrict()
        }

        assertTrue(error.message!!.contains("timed out"))
        assertTrue(error.message!!.contains("timeoutMs=60000"))
    }

    @Test
    fun blankVariableValueIsRejected() {
        val error = assertThrows(AssertionError::class.java) {
            HexanaWasmBreakpointDebugResult.parse(
                """
                PAUSED
                a=
                b=7
                RESUMED
                """.trimIndent(),
            ).assertStrict()
        }

        assertTrue(error.message!!.contains("contain '3'"))
        assertTrue(error.message!!.contains("rawOutput="))
    }

    @Test
    fun unavailableVariableValueIsRejected() {
        val error = assertThrows(AssertionError::class.java) {
            HexanaWasmBreakpointDebugResult.parse(
                """
                PAUSED
                a=3
                b=unavailable
                RESUMED
                """.trimIndent(),
            ).assertStrict()
        }

        assertTrue(error.message!!.contains("contain '7'"))
        assertTrue(error.message!!.contains("unavailable"))
    }

    @Test
    fun missingResumedMarkerIsRejected() {
        val result = HexanaWasmBreakpointDebugResult.parse(
            """
            PAUSED
            a=3
            b=7
            """.trimIndent(),
        )

        val error = assertThrows(AssertionError::class.java) {
            result.assertStrict()
        }

        assertTrue(error.message!!.contains("RESUMED"))
        assertTrue(error.message!!.contains("malformed"))
    }
}
