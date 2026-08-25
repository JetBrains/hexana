package org.jetbrains.hexana.firmware.debug

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.firmware.run.StopAtEntry

/**
 * Golden tests for the LLDB command sequence produced by [FirmwareDebugRunner.buildAttachSequence].
 *
 * These tests do NOT start a process. They verify the exact ordering and content of every LLDB
 * command the debug runner sends for each [StopAtEntry] policy. A wrong command, wrong order, or
 * missing step will cause a hard failure — intentionally, so regressions cannot hide.
 */
class FirmwareDebugSequenceTest : UsefulTestCase() {

    private val elfPath = "/build/qemu_cortex_m3/zephyr/zephyr.elf"
    private val port = 4321

    // -------------------------------------------------------------------------
    // StopAtEntry.MAIN — most common case
    // -------------------------------------------------------------------------

    fun testAttachSequenceStopAtMain() {
        val seq = FirmwareDebugRunner.buildAttachSequence(elfPath, port, StopAtEntry.MAIN)

        assertEquals(
            listOf(
                "settings set target.disable-aslr false",
                "target create \"$elfPath\"",
                "target list",
                "gdb-remote 127.0.0.1:$port",
                "breakpoint set --name main",
                "continue",
            ),
            seq,
        )
    }

    // -------------------------------------------------------------------------
    // StopAtEntry.ENTRY — stops at reset vector (QEMU -S already paused)
    // -------------------------------------------------------------------------

    fun testAttachSequenceStopAtEntry() {
        val seq = FirmwareDebugRunner.buildAttachSequence(elfPath, port, StopAtEntry.ENTRY)

        assertEquals(
            listOf(
                "settings set target.disable-aslr false",
                "target create \"$elfPath\"",
                "target list",
                "gdb-remote 127.0.0.1:$port",
                // No breakpoint, no continue: emulator is already stopped at reset vector.
            ),
            seq,
        )
    }

    // -------------------------------------------------------------------------
    // StopAtEntry.NONE — run freely until user breaks
    // -------------------------------------------------------------------------

    fun testAttachSequenceStopAtNone() {
        val seq = FirmwareDebugRunner.buildAttachSequence(elfPath, port, StopAtEntry.NONE)

        assertEquals(
            listOf(
                "settings set target.disable-aslr false",
                "target create \"$elfPath\"",
                "target list",
                "gdb-remote 127.0.0.1:$port",
                "continue",
            ),
            seq,
        )
    }

    // -------------------------------------------------------------------------
    // Structural invariants
    // -------------------------------------------------------------------------

    fun testTargetCreatePrecedesGdbRemote() {
        for (policy in StopAtEntry.entries) {
            val seq = FirmwareDebugRunner.buildAttachSequence(elfPath, port, policy)
            val createIdx = seq.indexOfFirst { it.startsWith("target create") }
            val connectIdx = seq.indexOfFirst { it.startsWith("gdb-remote") }
            assertTrue(
                "target create must precede gdb-remote for $policy (got $seq)",
                createIdx >= 0 && connectIdx > createIdx,
            )
        }
    }

    fun testDisableAslrIsFirst() {
        for (policy in StopAtEntry.entries) {
            val seq = FirmwareDebugRunner.buildAttachSequence(elfPath, port, policy)
            assertEquals(
                "settings set target.disable-aslr false must be the first command for $policy",
                "settings set target.disable-aslr false",
                seq.first(),
            )
        }
    }

    fun testGdbRemoteContainsPortAndHost() {
        val otherPort = 9876
        val seq = FirmwareDebugRunner.buildAttachSequence(elfPath, otherPort, StopAtEntry.MAIN)
        assertTrue(
            "gdb-remote command must contain host and port",
            seq.any { it == "gdb-remote 127.0.0.1:$otherPort" },
        )
    }

    fun testElfPathIsQuotedInTargetCreate() {
        val pathWithSpaces = "/path with spaces/zephyr.elf"
        val seq = FirmwareDebugRunner.buildAttachSequence(pathWithSpaces, port, StopAtEntry.NONE)
        assertTrue(
            "ELF path with spaces must be quoted in target create",
            seq.any { it == "target create \"$pathWithSpaces\"" },
        )
    }
}
