package org.jetbrains.hexana.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XDebugSession

/**
 * A debug process for the WAMR runtime that extends [WasmDebugProcess]
 * with WAMR-specific lifecycle management.
 *
 * WAMR uses a GDB remote debug stub model:
 * - `iwasm -g=host:port module.wasm` runs as a separate process.
 * - LLDB connects via `process connect --plugin wasm connect://host:port`.
 *
 * This class manages both the iwasm process (the debuggee) and the
 * LLDB process (the debugger), connecting them during session
 * initialization and tearing both down on stop.
 */
class WamrDebugProcess(
    session: XDebugSession,
    console: ExecutionConsole,
    lldbProcessHandler: ProcessHandler,
    private val iwasmProcessHandler: ProcessHandler,
    lldb: LldbCommunicator?,
    private val debugHost: String,
    private val debugPort: Int,
    engine: WasmEngine = WasmEngine.WAMR,
) : WasmDebugProcess(
    session,
    console,
    lldbProcessHandler,
    lldb,
    compositionUsed = false,
    dwarfInjected = false,
    engine = engine,
) {

    init {
        // For WAMR, `process connect --plugin wasm` must be the very
        // first LLDB command. Any `breakpoint set` commands issued
        // beforehand create a "dummy target" that prevents the wasm
        // process plugin from creating a proper process (the connect
        // silently fails and `continue` errors with "Command requires
        // a current process"). Defer breakpoints until after connect.
        deferBreakpointsToLldb = true
    }

    override fun sessionInitialized() {
        // Connect LLDB to the WAMR GDB remote stub.
        //
        // WAMR uses its own Wasm process plugin in LLDB, so the
        // connect command must specify `--plugin wasm` and use the
        // `connect://host:port` URL scheme.
        //
        // Do NOT issue `target create` before connecting. The WAMR
        // wasm process plugin creates its own target from the remote
        // stub. A pre-existing local target from `target create`
        // interferes with the plugin and causes `process connect`
        // to silently fail (no process is created, and subsequent
        // commands like `continue` error with "Command requires a
        // current process").
        val communicator = getLldb()
        if (communicator != null) {
            val connected = connectWithRetry(communicator)
            if (!connected) {
                LOG.error(
                    "Failed to connect LLDB to WAMR debug stub at " +
                        "$debugHost:$debugPort after $MAX_CONNECT_ATTEMPTS attempts"
                )
                session.reportMessage(
                    "Failed to connect LLDB to the WAMR debug server at " +
                        "$debugHost:$debugPort. Make sure iwasm was built with " +
                        "debug support (WAMR_BUILD_DEBUG_INTERP=1) and that " +
                        "LLDB 22+ with the Wasm process plugin is configured.",
                    com.intellij.openapi.ui.MessageType.ERROR,
                )
                return
            }

            // Now that the connection is established and LLDB has a
            // proper wasm target/process, replay all breakpoints that
            // were deferred during registerBreakpoint() calls.
            replayBreakpointsToLldb()
        }

        // Do NOT call super.sessionInitialized() — the parent
        // implementation calls lldb.runProcess() which sends `run`
        // to LLDB, but for WAMR the process is already running via
        // iwasm and LLDB is connected as a remote debugger. Instead,
        // resume the already-connected process and start the event
        // loop directly.
        if (communicator != null) {
            // Drain any extra stop events that may have been queued
            // after the primary stop event consumed by connectWithRetry
            // so they don't confuse the event loop.
            communicator.drainPendingStopEvents()

            communicator.resume()
            startEventLoopForSession()
        }
    }

    /**
     * Attempts to connect LLDB to the WAMR debug stub, retrying up
     * to [MAX_CONNECT_ATTEMPTS] times with a delay between attempts.
     *
     * Sends the `process connect` command directly (not via
     * [LldbCommunicator.sendCommandAndCollectOutput]) and then waits
     * for a stop event in the queue. A successful connection always
     * produces a stop event (typically `stop reason = trace`), which
     * is the most reliable signal that the connection is established.
     *
     * The marker-based output collection approach is intentionally
     * avoided here because `process connect` is a blocking LLDB
     * command whose output may arrive asynchronously (outside the
     * command response stream), causing the marker-based collector
     * to miss the "Process 1 stopped" text or time out.
     *
     * @return `true` if the connection was established successfully.
     */
    private fun connectWithRetry(communicator: LldbCommunicator): Boolean {
        val connectCmd = "process connect --plugin wasm connect://$debugHost:$debugPort"

        for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
            LOG.info("WAMR connect attempt $attempt/$MAX_CONNECT_ATTEMPTS: $connectCmd")

            communicator.sendCommand(connectCmd)

            // Wait for a stop event that proves the connection succeeded.
            // A successful `process connect` produces a stop like
            //   `* thread #1, name = 'nobody', stop reason = trace`
            // which the LldbCommunicator parses into a StopEvent.
            val event = communicator.waitForStopEvent(CONNECT_COMMAND_TIMEOUT_MS)

            if (event != null) {
                LOG.info("Successfully connected to WAMR debug stub on attempt $attempt (event: $event)")
                return true
            }

            LOG.warn("WAMR connect attempt $attempt: no stop event within timeout")

            if (attempt < MAX_CONNECT_ATTEMPTS) {
                Thread.sleep(CONNECT_RETRY_DELAY_MS)
            }
        }
        return false
    }

    override fun stop() {
        super.stop()
        // Also terminate the iwasm process
        if (!iwasmProcessHandler.isProcessTerminated) {
            iwasmProcessHandler.destroyProcess()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WamrDebugProcess::class.java)

        /** Maximum number of attempts to connect LLDB to WAMR. */
        private const val MAX_CONNECT_ATTEMPTS = 5

        /** Delay between connection retry attempts. */
        private const val CONNECT_RETRY_DELAY_MS = 1_000L

        /**
         * Timeout for the `process connect` command output collection.
         * The connect itself may take a few seconds when the WAMR stub
         * is slow to respond, so this is longer than the default
         * command timeout.
         */
        private const val CONNECT_COMMAND_TIMEOUT_MS = 10_000L
    }
}
