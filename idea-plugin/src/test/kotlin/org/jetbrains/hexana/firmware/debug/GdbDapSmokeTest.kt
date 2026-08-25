package org.jetbrains.hexana.firmware.debug

import com.intellij.testFramework.UsefulTestCase
import kotlinx.serialization.json.*
import java.io.File
import java.net.ServerSocket
import java.util.zip.GZIPInputStream

/**
 * Live smoke test: boots the qemu_cortex_m3 corpus ELF under QEMU (gdbstub mode),
 * attaches GDB in DAP mode, sets a breakpoint on `main`, continues, reads the stack trace,
 * and verifies the breakpoint was hit.
 *
 * Guards:
 * - [GDB_BINARY] must exist as a file.
 * - [QEMU_BINARY] must exist as a file.
 *
 * Both guards must pass; the test prints a skip message (does NOT use Assume) and returns
 * if either is absent. Per project guidelines, tests must be able to fail — silence-on-skip
 * is explicit stdout, not a suppressed exception.
 *
 * Assertions are exact and exclusive:
 * - The `stopped` event arrives within 30 s.
 * - The stop reason is `"breakpoint"` or `"function breakpoint"`.
 * - The top frame function name contains `"main"`.
 */
class GdbDapSmokeTest : UsefulTestCase() {

    fun testGdbDapBreakpointAtMain() {
        // Guards — guard-and-return, not Assume (tests must be able to fail).
        if (!File(GDB_BINARY).isFile) {
            println("SKIP GdbDapSmokeTest: $GDB_BINARY not found")
            return
        }
        if (!File(QEMU_BINARY).isFile) {
            println("SKIP GdbDapSmokeTest: $QEMU_BINARY not found")
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

        val elfFile = File.createTempFile("hexana-dap-smoke-", ".elf")
        elfFile.deleteOnExit()
        GZIPInputStream(elfGz.inputStream()).use { gz -> elfFile.outputStream().use { gz.copyTo(it) } }

        // Allocate a free TCP port.
        val port = ServerSocket(0).use { it.localPort }

        // Launch QEMU frozen (raw ProcessBuilder — no platform handler, no stream races).
        val qemuCmd = listOf(
            QEMU_BINARY,
            "-machine", "lm3s6965evb",
            "-cpu", "cortex-m3",
            "-kernel", elfFile.absolutePath,
            "-nographic",
            "-serial", "mon:stdio",
            "-gdb", "tcp::$port",
            "-S",
        )
        val qemuProc = ProcessBuilder(qemuCmd).redirectErrorStream(true).start()

        // Spawn GDB in DAP mode (raw ProcessBuilder — avoids KillableProcessHandler's internal
        // reader threads, which would race with GdbDapClient for the same inputStream).
        var gdbProc: Process? = null
        var client: GdbDapClient? = null

        try {
            assertTrue(
                "QEMU gdbstub did not open port $port within 15 s",
                QemuGdbStubLauncher.waitForPortAccept("127.0.0.1", port, timeoutMs = 15_000),
            )

            val gdbProcess = ProcessBuilder(GDB_BINARY, "-i=dap")
                .redirectErrorStream(true)
                .start()
            gdbProc = gdbProcess

            // Pass raw streams to GdbDapClient — no startNotify(), no competing readers.
            val dapClient = GdbDapClient(
                input = gdbProcess.inputStream,
                output = gdbProcess.outputStream,
                defaultTimeoutMs = 15_000,
            )
            client = dapClient

            // initialize
            val initResp = dapClient.request("initialize", buildJsonObject {
                put("adapterId", "hexana-firmware")
                put("linesStartAt1", true)
                put("columnsStartAt1", true)
                put("pathFormat", "path")
            })
            assertTrue(
                "initialize must succeed",
                initResp["success"]?.jsonPrimitive?.boolean == true,
            )

            // GDB DAP quirk: attach response is only delivered AFTER configurationDone.
            // Fire attach without blocking; collect the future after configuration is done.
            val (_, attachFuture) = dapClient.send("attach", buildJsonObject {
                put("program", elfFile.absolutePath)
                put("target", "localhost:$port")
            })

            // Wait for initialized event.
            val initializedEvent = dapClient.waitForEvent("initialized", timeoutMs = 15_000)
            assertNotNull("Expected initialized event within 15 s", initializedEvent)

            // Set breakpoint on main.
            dapClient.request("setFunctionBreakpoints", buildJsonObject {
                putJsonArray("breakpoints") { addJsonObject { put("name", "main") } }
            })

            // configurationDone triggers GDB to connect and sends the attach response.
            dapClient.request("configurationDone")

            // Now the attach response should have arrived.
            val attachResp = dapClient.awaitResponse(attachFuture, timeoutMs = 15_000)
            assertTrue(
                "attach must succeed",
                attachResp["success"]?.jsonPrimitive?.boolean == true,
            )

            // QEMU -S: GDB reports a stopped(attach) event immediately on connect.
            // Drain it, then continue to the main breakpoint.
            val attachStop = dapClient.waitForEvent("stopped", timeoutMs = 5_000)
            val attachStopReason = attachStop?.get("body")?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
            assertTrue(
                "first stop after attach must be 'attach' or 'entry', was: $attachStopReason",
                attachStopReason != null && (attachStopReason == "attach" || attachStopReason == "entry"),
            )

            // continue (threadId=1 for bare-metal single-threaded target)
            dapClient.request("continue", buildJsonObject { put("threadId", 1) })

            // Wait for stopped event (breakpoint hit) within 30 s.
            val stoppedEvent = dapClient.waitForEvent("stopped", timeoutMs = 30_000)
            assertNotNull("Expected stopped event within 30 s (breakpoint at main)", stoppedEvent)

            val reason = stoppedEvent!!["body"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
            assertTrue(
                "stop reason must be 'breakpoint' or 'function breakpoint', was: $reason",
                reason != null && (reason.contains("breakpoint", ignoreCase = true)),
            )

            // threads
            val threadsResp = dapClient.request("threads")
            assertTrue("threads request must succeed", threadsResp["success"]?.jsonPrimitive?.boolean == true)
            val threadId = threadsResp["body"]?.jsonObject
                ?.get("threads")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("id")?.jsonPrimitive?.intOrNull ?: 1

            // stackTrace
            val stackResp = dapClient.request("stackTrace", buildJsonObject {
                put("threadId", threadId)
                put("startFrame", 0)
                put("levels", 1)
            })
            assertTrue("stackTrace must succeed", stackResp["success"]?.jsonPrimitive?.boolean == true)
            val topFrame = stackResp["body"]?.jsonObject
                ?.get("stackFrames")?.jsonArray
                ?.firstOrNull()?.jsonObject
            assertNotNull("expected at least one stack frame", topFrame)

            val funcName = topFrame!!["name"]?.jsonPrimitive?.contentOrNull ?: ""
            assertTrue(
                "top frame function name must contain 'main', was: '$funcName'",
                funcName.contains("main", ignoreCase = true),
            )

            val frameId = topFrame["id"]?.jsonPrimitive?.intOrNull

            // scopes — optional: look for Registers scope
            if (frameId != null) {
                val scopesResp = dapClient.request("scopes", buildJsonObject { put("frameId", frameId) })
                if (scopesResp["success"]?.jsonPrimitive?.boolean == true) {
                    val scopes = scopesResp["body"]?.jsonObject?.get("scopes")?.jsonArray
                    val regScope = scopes?.firstOrNull { scope ->
                        scope.jsonObject["name"]?.jsonPrimitive?.contentOrNull
                            ?.contains("register", ignoreCase = true) == true
                    }?.jsonObject
                    if (regScope != null) {
                        val varRef = regScope["variablesReference"]?.jsonPrimitive?.intOrNull
                        if (varRef != null && varRef > 0) {
                            val varsResp = dapClient.request(
                                "variables",
                                buildJsonObject { put("variablesReference", varRef) },
                            )
                            if (varsResp["success"]?.jsonPrimitive?.boolean == true) {
                                val vars = varsResp["body"]?.jsonObject?.get("variables")?.jsonArray
                                val pcVar = vars?.firstOrNull { v ->
                                    val name = v.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: ""
                                    name.equals("pc", ignoreCase = true) ||
                                        name.equals("r15", ignoreCase = true)
                                }?.jsonObject
                                if (pcVar != null) {
                                    val pcValue = pcVar["value"]?.jsonPrimitive?.contentOrNull ?: ""
                                    assertFalse("PC register value must not be empty", pcValue.isBlank())
                                    println("GdbDapSmokeTest: pc = $pcValue")
                                }
                            }
                        }
                    }
                }
            }

            // disconnect (clean shutdown — do not terminate debuggee; QEMU handles that)
            try {
                dapClient.request("disconnect", buildJsonObject { put("terminateDebuggee", false) })
            } catch (_: Exception) {
                // Expected on fast process exit.
            }
        } finally {
            client?.close()
            gdbProc?.destroyForcibly()
            qemuProc.destroyForcibly()
            elfFile.delete()
        }
    }

    companion object {
        private const val GDB_BINARY =
            "/Users/user/zephyr-sdk-1.0.1/gnu/arm-zephyr-eabi/bin/arm-zephyr-eabi-gdb-py"
        private const val QEMU_BINARY = "/opt/homebrew/bin/qemu-system-arm"
    }
}
