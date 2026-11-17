package org.jetbrains.hexana.firmware.debug

import com.intellij.testFramework.UsefulTestCase
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Live smoke test: boots the qemu_cortex_m3 corpus ELF under QEMU (gdbstub mode),
 * attaches LLDB, sets a breakpoint on `main`, continues, reads the PC, and verifies the
 * breakpoint was hit.
 *
 * This is the debug-path ground truth: it exercises the real QEMU + LLDB integration
 * without any IntelliJ services. Skips loudly (not silently) when either tool is absent.
 *
 * Guards:
 * - QEMU: [QEMU_BINARY] must exist as a file.
 * - LLDB: [LLDB_BINARY] found via `xcrun -f lldb` or [LLDB_FALLBACK].
 * Both guards must pass; skipping on one means the test cannot run.
 *
 * Assertions are exact and exclusive (per project guidelines: tests must be able to fail):
 * - `stop reason = breakpoint` appears in the transcript.
 * - `register read pc` output contains a non-zero hex address.
 */
class QemuLldbSmokeTest : UsefulTestCase() {

    private val qemuBinary = QEMU_BINARY
    private val lldbBinary: String? = resolveLldb()

    fun testQemuCortexM3DebugBreakpointAtMain() {
        // Guard: require both tools.
        if (!File(qemuBinary).isFile) {
            println("SKIP QemuLldbSmokeTest: $qemuBinary not found")
            return
        }
        if (lldbBinary == null) {
            println("SKIP QemuLldbSmokeTest: no lldb found at known paths")
            return
        }

        // Locate and gunzip the corpus ELF.
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { it.resolve("idea-plugin").isDirectory }
            ?: error("Cannot locate project root")

        val elfGz = root.resolve(
            "ui-driver-tests/testData/fixtures/zephyr-corpus/apps/hello_world/qemu_cortex_m3-Os/zephyr.elf.gz"
        )
        assertTrue("corpus ELF.gz not found: ${elfGz.path}", elfGz.exists())

        val elfFile = File.createTempFile("hexana-dbgsmoke-qemu_cortex_m3-", ".elf")
        elfFile.deleteOnExit()
        GZIPInputStream(elfGz.inputStream()).use { gz ->
            elfFile.outputStream().use { out -> gz.copyTo(out) }
        }

        // Allocate a free TCP port for the gdbstub.
        val port = java.net.ServerSocket(0).use { it.localPort }

        // Launch QEMU frozen on [port].
        val qemuCmd = listOf(
            qemuBinary,
            "-machine", "lm3s6965evb",
            "-cpu", "cortex-m3",
            "-kernel", elfFile.absolutePath,
            "-nographic",
            "-serial", "mon:stdio",
            "-gdb", "tcp::$port",
            "-S",
        )
        val qemuProc = ProcessBuilder(qemuCmd)
            .redirectErrorStream(true)
            .start()

        try {
            // Wait for the gdbstub port to accept.
            assertTrue(
                "QEMU gdbstub did not open port $port within 15s",
                QemuGdbStubLauncher.waitForPortAccept("127.0.0.1", port, timeoutMs = 15_000),
            )

            // Run LLDB in batch mode.
            val lldbCmd = listOf(
                lldbBinary,
                "-o", "target create \"${elfFile.absolutePath}\"",
                "-o", "gdb-remote 127.0.0.1:$port",
                "-o", "breakpoint set --name main",
                "-o", "continue",
                "-o", "register read pc",
                "-o", "process detach",
                "-o", "quit",
            )
            val lldbProc = ProcessBuilder(lldbCmd)
                .redirectErrorStream(true)
                .start()

            val transcript = StringBuilder()
            try {
                lldbProc.inputStream.bufferedReader().use { reader ->
                    val deadline = System.currentTimeMillis() + 30_000L
                    var line = reader.readLine()
                    while (line != null && System.currentTimeMillis() < deadline) {
                        transcript.appendLine(line)
                        line = reader.readLine()
                    }
                }
            } finally {
                lldbProc.destroyForcibly()
            }

            val fullTranscript = transcript.toString()
            println("=== QemuLldbSmokeTest transcript ===")
            println(fullTranscript)
            println("=== end transcript ===")

            // Must see the breakpoint hit stop reason.
            assertTrue(
                "Expected 'stop reason = breakpoint' in lldb transcript but it was absent.\n$fullTranscript",
                fullTranscript.contains("stop reason = breakpoint"),
            )

            // Must see a PC register value (non-zero hex address).
            val pcLine = fullTranscript.lines().firstOrNull { "pc" in it && "0x" in it }
            assertNotNull("Expected 'pc = 0x...' register read output in transcript\n$fullTranscript", pcLine)

            // PC must not be all-zeros (that would indicate the CPU never ran).
            assertFalse(
                "PC register read returned 0x00000000 — CPU did not advance past reset vector\n$fullTranscript",
                pcLine!!.contains("0x00000000"),
            )
        } finally {
            qemuProc.destroyForcibly()
            elfFile.delete()
        }
    }

    companion object {
        private const val QEMU_BINARY = "/opt/homebrew/bin/qemu-system-arm"
        private const val LLDB_FALLBACK = "/usr/bin/lldb"

        private fun resolveLldb(): String? {
            // Prefer xcrun lldb (available when Xcode is installed).
            val xcrun = try {
                val proc = ProcessBuilder("xcrun", "-f", "lldb").start()
                val path = proc.inputStream.bufferedReader().readLine()?.trim()
                proc.waitFor()
                if (!path.isNullOrBlank() && File(path).isFile) path else null
            } catch (_: Exception) {
                null
            }
            if (xcrun != null) return xcrun
            if (File(LLDB_FALLBACK).isFile) return LLDB_FALLBACK
            return null
        }
    }
}
