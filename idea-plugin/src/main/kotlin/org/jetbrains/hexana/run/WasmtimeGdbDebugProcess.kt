package org.jetbrains.hexana.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.impl.XDebuggerUtilImpl
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.hexana.shared.parseLldbFrameLocation
import org.jetbrains.hexana.shared.parseLldbFunctionName
import org.jetbrains.hexana.shared.wasmModuleIdForGdbPc
import org.jetbrains.hexana.shared.wasmOffsetForGdbPc
import org.jetbrains.hexana.wasisdk.WasiSdkOffer

/**
 * Debug process for Wasmtime's built-in gdbstub guest debugger.
 *
 * Instead of wrapping wasmtime under LLDB (the legacy JIT approach), this
 * process launches wasmtime separately with `-g <host>:<port>` and then
 * connects a plain LLDB instance via the wasm process plugin:
 *
 * ```
 * process connect --plugin wasm connect://127.0.0.1:<port>
 * ```
 *
 * Wasmtime 44+ with gdbstub support exposes DWARF debug info directly from
 * the wasm binary.  LLDB reads it via the wasm process plugin without any
 * JIT loader or addrmap machinery.  PC-to-wasm-offset translation is the
 * low 32 bits of the LLDB PC value (`pc and 0xFFFFFFFF`), encoding the wasm
 * file byte offset directly.
 *
 * This mirrors [WamrDebugProcess]: same connect-with-retry pattern, same
 * lifecycle (stop terminates the wasmtime process), same deferred-breakpoint
 * scheme so `process connect` is the very first LLDB command.
 */
class WasmtimeGdbDebugProcess(
    session: XDebugSession,
    console: ExecutionConsole,
    lldbProcessHandler: ProcessHandler,
    private val wasmtimeProcessHandler: ProcessHandler,
    lldb: LldbCommunicator?,
    private val debugHost: String,
    private val debugPort: Int,
    executedWasmPath: String?,
    private val captureGdbstubAddress: ULong? = null,
) : WasmDebugProcess(
    session,
    console,
    lldbProcessHandler,
    lldb,
    compositionUsed = false,
    dwarfInjected = false,
    engine = WasmEngine.WASMTIME,
    executedWasmPath = executedWasmPath,
) {

    @Volatile
    private var captureBreakpointPending = captureGdbstubAddress != null

    init {
        // `process connect --plugin wasm` must be the very first LLDB
        // command; defer breakpoints until after the connection is ready.
        deferBreakpointsToLldb = true
    }

    /**
     * Enable per-instruction stepping when the Wasm disassembly tab is
     * selected. In gdbstub mode the PC encodes the wasm byte offset
     * directly, so `thread step-inst` / `thread step-inst-over` advance
     * by exactly one wasm instruction and the disassembly tab updates
     * correctly through the existing stop -> wasmLocationForPc pipeline.
     */
    override val supportsInstructionStepping: Boolean = true

    /** Wasmtime gdbstub sessions use LLDB connected via the wasm process plugin. */
    override val consoleSessionKind: ConsoleSessionKind
        get() = ConsoleSessionKind.LLDB_WASMTIME_GDBSTUB

    /** Gdbstub breakpoints live in the stub; there is no JIT trap to skip. */
    override val usesJitTrapWorkaround: Boolean = false

    /** LLDB `expression` aborts on wasm targets; use `frame variable` only. */
    override val guestOnlyDebugging: Boolean = true

    override fun sessionInitialized() {
        val communicator = getLldb()
        if (communicator != null) {
            val connected = connectWithRetry(communicator)
            if (!connected) {
                LOG.error(
                    "Failed to connect LLDB to Wasmtime gdbstub at " +
                        "$debugHost:$debugPort after $MAX_CONNECT_ATTEMPTS attempts"
                )
                session.reportMessage(
                    "Failed to connect LLDB to the Wasmtime debug server at " +
                        "$debugHost:$debugPort. Make sure the installed wasmtime " +
                        "supports guest debugging (-g / --gdbstub; needs wasmtime 44+) " +
                        "and that LLDB with the Wasm process plugin is configured.",
                    com.intellij.openapi.ui.MessageType.ERROR,
                )
                return
            }

            replayBreakpointsToLldb()
            startBinaryBreakpointSession()
            captureGdbstubAddress?.let { address ->
                communicator.sendCommand("breakpoint set --address 0x${address.toString(16)}")
            }
        }

        // Do NOT call super.sessionInitialized(): the parent issues `run`
        // which is wrong here — wasmtime is already running and LLDB is
        // connected as a remote debugger (same as WAMR).
        if (communicator != null) {
            communicator.drainPendingStopEvents()

            val initialBreakpoint = checkInitialStopMatchesBreakpoint(communicator)

            startEventLoopForSession()

            if (initialBreakpoint != null) {
                val bp = initialBreakpoint.key
                val loc = initialBreakpoint.value
                LOG.info(
                    "Initial wasmtime gdbstub stop matches breakpoint at " +
                        "${loc.first}:${loc.second} — presenting stop instead of resuming"
                )

                val frameLines = communicator.sendCommandAndCollectOutput(
                    "frame info", timeoutMs = 10_000
                )
                val frameLocation = parseLldbFrameLocation(frameLines)
                val functionName = parseLldbFunctionName(frameLines)

                val sourcePosition = if (frameLocation != null && frameLocation.line > 0) {
                    val vFile = LocalFileSystem.getInstance()
                        .findFileByPath(frameLocation.filePath)
                        ?: resolveFileByDwarfPath(frameLocation.filePath)
                    if (vFile != null) {
                        XDebuggerUtilImpl.getInstance()
                            .createPosition(vFile, frameLocation.line - 1)
                    } else null
                } else null

                val effectivePosition = sourcePosition ?: run {
                    val bpFile = LocalFileSystem.getInstance().findFileByPath(loc.first)
                    if (bpFile != null) {
                        XDebuggerUtilImpl.getInstance()
                            .createPosition(bpFile, loc.second - 1)
                    } else null
                }

                val frame = WasmStackFrame(
                    effectivePosition, functionName, getLldb(),
                    dwarfVariableResolver = dwarfVariableResolver,
                    engine = engine,
                    guestOnly = true,
                )
                val context = WasmSuspendContext(frame, getLldb(), this)
                ApplicationManager.getApplication().invokeLater {
                    session.breakpointReached(bp, null, context)
                }
            } else {
                communicator.resume()
            }
        }
    }

    /**
     * PC-to-wasm-location translation for the wasmtime gdbstub.
     *
     * In gdbstub mode, LLDB reports PCs as
     * `0x4000000000000000 | (<module id> << 32) | <byte offset in that module>`, so
     * both halves are read straight out of the PC — no addrmap or JIT loader needed.
     * The offset counts from the addressed module's own start, which is why a
     * component's stops resolve against its embedded core modules rather than
     * against the component file.
     */
    override fun wasmLocationForPc(communicator: LldbCommunicator, pc: ULong): WasmStopLocation =
        WasmStopLocation(moduleId = wasmModuleIdForGdbPc(pc), offset = wasmOffsetForGdbPc(pc))

    override fun consumeCaptureBreakpoint(event: LldbCommunicator.StopEvent.BreakpointHit): Boolean {
        // Wasmtime's internal traps have no LLDB breakpoint id. The opt-in
        // capture helper installs the only address breakpoint in its session,
        // so accepting an identified hit here cannot mistake a trap for the
        // requested guest stop.
        if (!captureBreakpointPending || event.breakpointId.isBlank()) return false
        captureBreakpointPending = false
        return true
    }

    /**
     * Attempts to connect LLDB to the Wasmtime gdbstub, retrying up to
     * [MAX_CONNECT_ATTEMPTS] times. Mirrors [WamrDebugProcess.connectWithRetry].
     *
     * A successful connection produces a stop event (SIGTRAP at pc 0x0) and
     * may print a harmless "memory read failed for 0x0" error — both are
     * expected and do not indicate failure.
     *
     * If LLDB does not produce a stop event after all attempts, the wasi-sdk
     * offer notification is shown since the most common cause is an LLDB
     * build that lacks the wasm process plugin.
     *
     * @return `true` if the connection was established.
     */
    private fun connectWithRetry(communicator: LldbCommunicator): Boolean {
        val connectCmd = "process connect --plugin wasm connect://$debugHost:$debugPort"

        for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
            LOG.info("Wasmtime gdbstub connect attempt $attempt/$MAX_CONNECT_ATTEMPTS: $connectCmd")

            communicator.sendCommand(connectCmd)

            val event = communicator.waitForStopEvent(CONNECT_COMMAND_TIMEOUT_MS)

            if (event != null) {
                LOG.info(
                    "Connected to Wasmtime gdbstub on attempt $attempt (event: $event)"
                )
                return true
            }

            // After the first failed attempt try to detect whether the wasm
            // process plugin is absent by probing with a harmless diagnostic
            // command.  When the plugin is missing, LLDB has no process and
            // the output will not contain "process" state info.
            if (attempt == 1) {
                val probe = communicator.sendCommandAndCollectOutput("process status", timeoutMs = 3_000)
                val probeText = probe.joinToString(" ")
                val pluginMissing = probeText.contains("plugin", ignoreCase = true) &&
                    (probeText.contains("invalid", ignoreCase = true) ||
                        probeText.contains("unable", ignoreCase = true) ||
                        probeText.contains("unknown", ignoreCase = true))
                if (pluginMissing) {
                    LOG.warn("Wasmtime gdbstub: LLDB wasm process plugin appears unavailable: $probeText")
                    ApplicationManager.getApplication().invokeLater {
                        WasiSdkOffer.showLldbMissingNotification(session.project)
                    }
                    return false
                }
            }

            LOG.warn("Wasmtime gdbstub connect attempt $attempt: no stop event within timeout")

            if (attempt < MAX_CONNECT_ATTEMPTS) {
                Thread.sleep(CONNECT_RETRY_DELAY_MS)
            }
        }

        // All attempts exhausted — the most likely cause is a missing wasm plugin.
        ApplicationManager.getApplication().invokeLater {
            WasiSdkOffer.showLldbMissingNotification(session.project)
        }
        return false
    }

    override fun stop() {
        super.stop()
        if (!wasmtimeProcessHandler.isProcessTerminated) {
            wasmtimeProcessHandler.destroyProcess()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WasmtimeGdbDebugProcess::class.java)

        /**
         * Readiness marker printed by wasmtime to stderr/stdout when the
         * gdbstub is listening and ready to accept debugger connections.
         */
        const val READY_MARKER = "Debugger listening on"

        private const val MAX_CONNECT_ATTEMPTS = 3
        private const val CONNECT_COMMAND_TIMEOUT_MS = 15_000L
        private const val CONNECT_RETRY_DELAY_MS = 1_000L
    }
}
