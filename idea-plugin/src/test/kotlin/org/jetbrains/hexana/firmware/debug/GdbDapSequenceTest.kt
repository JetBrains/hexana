package org.jetbrains.hexana.firmware.debug

import com.intellij.testFramework.UsefulTestCase
import kotlinx.serialization.json.*
import org.jetbrains.hexana.firmware.run.StopAtEntry

/**
 * Golden tests for the DAP request sequence produced by [FirmwareDebugRunner.buildDapAttachRequests].
 *
 * These tests do NOT start a process. They verify the exact ordering and content of every DAP
 * (command, argsJson) pair the debug runner uses for each [StopAtEntry] policy. A wrong command,
 * wrong order, or missing step causes a hard failure — intentionally, so regressions cannot hide.
 */
class GdbDapSequenceTest : UsefulTestCase() {

    private val elfPath = "/build/qemu_cortex_m3/zephyr/zephyr.elf"
    private val port = 4321

    // -------------------------------------------------------------------------
    // StopAtEntry.MAIN — most common case
    // -------------------------------------------------------------------------

    fun testDapSequenceStopAtMain() {
        val seq = FirmwareDebugRunner.buildDapAttachRequests(elfPath, port, StopAtEntry.MAIN)

        assertEquals(5, seq.size)
        assertEquals("initialize", seq[0].first)
        assertEquals("attach", seq[1].first)

        val attachArgs = Json.parseToJsonElement(seq[1].second).jsonObject
        assertEquals(elfPath, attachArgs["program"]?.jsonPrimitive?.content)
        assertEquals("localhost:$port", attachArgs["target"]?.jsonPrimitive?.content)

        assertEquals("setFunctionBreakpoints", seq[2].first)
        val bpArgs = Json.parseToJsonElement(seq[2].second).jsonObject
        val breakpoints = bpArgs["breakpoints"]!!.jsonArray
        assertEquals(1, breakpoints.size)
        assertEquals("main", breakpoints[0].jsonObject["name"]?.jsonPrimitive?.content)

        assertEquals("configurationDone", seq[3].first)
        assertEquals("continue", seq[4].first)
    }

    // -------------------------------------------------------------------------
    // StopAtEntry.ENTRY — stops at reset vector (QEMU -S already paused)
    // -------------------------------------------------------------------------

    fun testDapSequenceStopAtEntry() {
        val seq = FirmwareDebugRunner.buildDapAttachRequests(elfPath, port, StopAtEntry.ENTRY)

        // initialize, attach, configurationDone — no continue, no breakpoint
        assertEquals(3, seq.size)
        assertEquals("initialize", seq[0].first)
        assertEquals("attach", seq[1].first)
        assertEquals("configurationDone", seq[2].first)
    }

    // -------------------------------------------------------------------------
    // StopAtEntry.NONE — run freely until user breaks
    // -------------------------------------------------------------------------

    fun testDapSequenceStopAtNone() {
        val seq = FirmwareDebugRunner.buildDapAttachRequests(elfPath, port, StopAtEntry.NONE)

        // initialize, attach, configurationDone, continue — no breakpoint
        assertEquals(4, seq.size)
        assertEquals("initialize", seq[0].first)
        assertEquals("attach", seq[1].first)
        assertEquals("configurationDone", seq[2].first)
        assertEquals("continue", seq[3].first)
    }

    // -------------------------------------------------------------------------
    // Structural invariants
    // -------------------------------------------------------------------------

    fun testInitializeArgsHaveRequiredFields() {
        val seq = FirmwareDebugRunner.buildDapAttachRequests(elfPath, port, StopAtEntry.MAIN)
        val initArgs = Json.parseToJsonElement(seq[0].second).jsonObject
        assertNotNull("adapterId required", initArgs["adapterId"])
        assertTrue(
            "linesStartAt1 must be true",
            initArgs["linesStartAt1"]?.jsonPrimitive?.boolean == true,
        )
        assertTrue(
            "columnsStartAt1 must be true",
            initArgs["columnsStartAt1"]?.jsonPrimitive?.boolean == true,
        )
        assertEquals("path", initArgs["pathFormat"]?.jsonPrimitive?.content)
    }

    fun testAttachArgsProgramMatchesElfPath() {
        for (policy in StopAtEntry.entries) {
            val seq = FirmwareDebugRunner.buildDapAttachRequests(elfPath, port, policy)
            val attachArgs = Json.parseToJsonElement(seq[1].second).jsonObject
            assertEquals(
                "attach.program must equal elfPath for $policy",
                elfPath,
                attachArgs["program"]?.jsonPrimitive?.content,
            )
        }
    }

    fun testAttachArgsTargetContainsPort() {
        val otherPort = 9876
        val seq = FirmwareDebugRunner.buildDapAttachRequests(elfPath, otherPort, StopAtEntry.MAIN)
        val attachArgs = Json.parseToJsonElement(seq[1].second).jsonObject
        assertEquals("localhost:$otherPort", attachArgs["target"]?.jsonPrimitive?.content)
    }

    fun testInitializeAlwaysFirst() {
        for (policy in StopAtEntry.entries) {
            val seq = FirmwareDebugRunner.buildDapAttachRequests(elfPath, port, policy)
            assertEquals("initialize must be first for $policy", "initialize", seq[0].first)
        }
    }

    fun testAttachAlwaysSecond() {
        for (policy in StopAtEntry.entries) {
            val seq = FirmwareDebugRunner.buildDapAttachRequests(elfPath, port, policy)
            assertEquals("attach must be second for $policy", "attach", seq[1].first)
        }
    }

    fun testNoSetFunctionBreakpointsForEntryOrNone() {
        for (policy in listOf(StopAtEntry.ENTRY, StopAtEntry.NONE)) {
            val seq = FirmwareDebugRunner.buildDapAttachRequests(elfPath, port, policy)
            assertFalse(
                "setFunctionBreakpoints must not appear for $policy",
                seq.any { it.first == "setFunctionBreakpoints" },
            )
        }
    }

    fun testNoContinueForEntry() {
        val seq = FirmwareDebugRunner.buildDapAttachRequests(elfPath, port, StopAtEntry.ENTRY)
        assertFalse(
            "continue must not appear for ENTRY (emulator already stopped)",
            seq.any { it.first == "continue" },
        )
    }

    fun testConfigurationDoneAlwaysPresent() {
        for (policy in StopAtEntry.entries) {
            val seq = FirmwareDebugRunner.buildDapAttachRequests(elfPath, port, policy)
            assertTrue(
                "configurationDone must be present for $policy",
                seq.any { it.first == "configurationDone" },
            )
        }
    }
}
