package org.jetbrains.hexana.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.impl.XDebuggerUtilImpl
import org.jetbrains.hexana.shared.cuPathEndsWithProjectRelative
import org.jetbrains.hexana.shared.extractCrateName
import org.jetbrains.hexana.shared.cuPathMatchesIdePath
import org.jetbrains.hexana.shared.extractProjectRelativePath
import org.jetbrains.hexana.shared.functionNameMatchesIdePath
import org.jetbrains.hexana.shared.parseLldbFrameLocation
import org.jetbrains.hexana.shared.parseLldbFunctionName
import org.jetbrains.hexana.shared.parseRawDwarfLine
import org.jetbrains.hexana.shared.parseRawDwarfPath
import org.jetbrains.hexana.shared.pathMatchesBreakpointLocation
import org.jetbrains.hexana.shared.stripCuHashSuffix

/**
 * A debug process that launches wasmtime under LLDB so that
 * breakpoints set in source files (WAT, C, Rust, etc.) are
 * actually hit when the Wasm binary contains DWARF debug info.
 *
 * The process communicates with LLDB via stdin/stdout to:
 * - set/remove breakpoints
 * - resume / step / pause execution
 * - detect stop events and suspend the debug session
 *
 * On macOS the JIT loader plugin must be enabled for LLDB to
 * see symbols in JIT-compiled code; this is done automatically
 * via `settings set plugin.jit-loader.gdb.enable on`.
 */
open class WasmDebugProcess(
    session: XDebugSession,
    private val console: ExecutionConsole,
    private val processHandler: ProcessHandler,
    private val lldb: LldbCommunicator?,
    private val compositionUsed: Boolean = false,
    private val dwarfInjected: Boolean = false,
    internal val engine: WasmEngine = WasmEngine.WASMTIME,
) : XDebugProcess(session) {

    /**
     * Parsed DWARF variable location metadata from the Wasm binary.
     * Set during debug session creation; used by [WasmStackFrame] to
     * resolve variables that LLDB's standard `frame variable` cannot.
     */
    internal var dwarfVariableResolver: WasmDwarfVariableResolver? = null

    private val breakpointHandler = WasmBreakpointHandler(this)

    /** Maps registered breakpoints to the file+line used in LLDB. */
    private val registeredBreakpoints =
        mutableMapOf<XLineBreakpoint<WasmBreakpointProperties>, Pair<String, Int>>()

    /**
     * When `true`, [registerBreakpointInLldb] stores breakpoints but
     * does NOT send them to LLDB. The subclass must call
     * [replayBreakpointsToLldb] after the LLDB connection is ready.
     *
     * This is needed for WAMR because the `process connect --plugin wasm`
     * command must be the first LLDB command — any `breakpoint set`
     * commands issued beforehand create a "dummy target" that prevents
     * the wasm process plugin from creating a proper process.
     */
    protected var deferBreakpointsToLldb: Boolean = false

    @Volatile
    private var eventLoopThread: Thread? = null

    /** Provides access to the LLDB communicator for subclasses. */
    internal fun getLldb(): LldbCommunicator? = lldb

    override fun getEditorsProvider(): XDebuggerEditorsProvider = WasmDebuggerEditorsProvider()

    override fun doGetProcessHandler(): ProcessHandler = processHandler

    override fun createConsole(): ExecutionConsole = console

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(breakpointHandler)

    override fun sessionInitialized() {
        super.sessionInitialized()
        if (lldb != null) {
            if (compositionUsed) {
                if (dwarfInjected) {
                    session.reportMessage(
                        "Note: component composition was used to satisfy dependencies. " +
                                "DWARF debug info from source files has been transferred " +
                                "into the composed binary.",
                        com.intellij.openapi.ui.MessageType.INFO,
                    )
                } else {
                    session.reportMessage(
                        "Note: component composition was used to satisfy dependencies. " +
                                "Composition tools strip DWARF debug info, so source-level " +
                                "breakpoints may not resolve. To debug with breakpoints, " +
                                "compile your Wasm binary with debug info (-g).",
                        com.intellij.openapi.ui.MessageType.WARNING,
                    )
                }
            }

            // Start the inferior *after* all existing breakpoints have been
            // registered by the platform (registerBreakpoint() is called before
            // sessionInitialized()), so LLDB has the breakpoints in place
            // before wasmtime begins execution.
            if (SystemInfo.isWindows) {
                // On Windows, LLDB's JIT loader GDB plugin does not
                // automatically process JIT debug symbols registered via
                // the GDB JIT interface. We work around this by:
                //   1. Removing user breakpoints (they can't resolve yet).
                //   2. Setting a breakpoint on `__jit_debug_register_code`
                //      — the GDB JIT interface entry wasmtime calls after
                //      JIT-compiling a module and registering debug symbols.
                //   3. Launching the process; each time the JIT breakpoint
                //      fires, manually reading __jit_debug_descriptor from
                //      the inferior's memory, extracting the in-memory ELF
                //      object, writing it to a temp file, and loading it
                //      into LLDB via target modules add. Then re-adding
                //      file/line breakpoints which can now resolve against
                //      the manually loaded debug info.
                //   4. Once resolved, resuming execution.
                //
                // This entire flow runs on a background thread so
                // sessionInitialized() returns immediately and the UI
                // stays responsive.
                lldb.removeAllBreakpoints()
                lldb.sendBreakpointOnFunction(JIT_REGISTER_FUNCTION)
                lldb.runProcess()
                Thread {
                    try {
                        windowsJitBreakpointSetup(lldb)
                    } catch (e: Exception) {
                        LOG.warn("Windows JIT breakpoint setup failed", e)
                    }
                    startEventLoop()
                }.apply {
                    name = "Hexana-Windows-JIT-Setup"
                    isDaemon = true
                    start()
                }
            } else {
                // On macOS/Linux, LLDB's JIT loader plugin resolves
                // pending breakpoints during process execution as
                // wasmtime JIT-compiles the Wasm module. A simple
                // `run` is sufficient.
                lldb.runProcess()
                startEventLoop()
            }
        }
    }

    fun registerBreakpointInLldb(breakpoint: XLineBreakpoint<WasmBreakpointProperties>) {
        val position = breakpoint.sourcePosition ?: return
        val filePath = position.file.path
        val line = position.line + 1 // LLDB uses 1-based lines
        registeredBreakpoints[breakpoint] = filePath to line
        // Use the file basename for the LLDB breakpoint command because
        // DWARF debug info inside composed/JIT-compiled Wasm binaries
        // records relative source paths (e.g. "src/lib.rs"). LLDB's
        // `breakpoint set --file` matches against compilation-unit
        // filenames, so a full absolute IDE path would fail to resolve
        // when the DWARF path is relative.
        //
        // Basename matching means that files with the same name in
        // different directories (e.g. "calculator/lib.rs" and
        // "adder/lib.rs") will both get LLDB breakpoints. We
        // disambiguate in handleStop() by comparing the resolved stop
        // location against the intended IDE file path and automatically
        // resuming past stops that hit the wrong file.
        if (!deferBreakpointsToLldb) {
            val fileName = position.file.name
            lldb?.sendBreakpoint(fileName, line)
        }
        session.setBreakpointVerified(breakpoint)
    }

    /**
     * Sends all stored breakpoints to LLDB. Called by subclasses
     * (e.g. [WamrDebugProcess]) after the LLDB connection is
     * established and breakpoint commands can be accepted.
     */
    protected fun replayBreakpointsToLldb() {
        for ((_, loc) in registeredBreakpoints) {
            val baseName = loc.first.substringAfterLast('/').substringAfterLast('\\')
            lldb?.sendBreakpoint(baseName, loc.second)
        }
    }

    /**
     * On Windows, loops through `__jit_debug_register_code` hits and
     * manually loads JIT debug objects from the inferior's memory into
     * LLDB (since the built-in JIT loader doesn't work on Windows).
     * After loading each object, searches its line tables via the
     * Python API for addresses matching user breakpoints and sets
     * address breakpoints directly. Once resolved (or attempts
     * exhausted), resumes the process.
     *
     * Must be called from a background thread — this method blocks
     * while waiting for stop events.
     */
    private fun windowsJitBreakpointSetup(lldb: LldbCommunicator) {
        val breakpointFiles = registeredBreakpoints.values.map { (path, line) ->
            val baseName = path.substringAfterLast('/').substringAfterLast('\\')
            baseName to line
        }

        val tempDir = java.nio.file.Files.createTempDirectory("hexana-jit").toAbsolutePath().toString()

        var resolved = false
        var processExited = false
        for (attempt in 1..MAX_JIT_REGISTER_ATTEMPTS) {
            val jitEvent = lldb.waitForStopEvent(WINDOWS_JIT_WAIT_TIMEOUT_MS)
            LOG.info("Windows wasmtime: JIT register attempt=$attempt event=$jitEvent")
            if (jitEvent is LldbCommunicator.StopEvent.ProcessExited) {
                LOG.warn("Windows wasmtime: process exited before breakpoints resolved")
                processExited = true
                // Re-offer the exit event so the event loop can pick it up
                // and terminate the debug session properly.
                lldb.reofferStopEvent(jitEvent)
                break
            }
            if (jitEvent == null) {
                LOG.warn("Windows wasmtime: timeout waiting for JIT register hit")
                break
            }
            // Read the descriptor at the breakpoint hit. If action=0
            // (initialization) or entries are empty, skip and wait for
            // the next hit — do NOT step out, as that would run past
            // subsequent __jit_debug_register_code calls.
            lldb.removeAllBreakpoints()
            val addresses = lldb.loadJitObjectAndResolveBreakpoints(tempDir, breakpointFiles)
            if (addresses.isNotEmpty()) {
                LOG.info("Windows wasmtime: breakpoints resolved after attempt $attempt at $addresses")
                resolved = true
                break
            }
            // No matches yet — re-add the JIT function breakpoint
            // and continue to the next hit.
            lldb.sendBreakpointOnFunction(JIT_REGISTER_FUNCTION)
            lldb.resume()
        }

        if (processExited) {
            // Process already exited — do not send any more commands.
            // The re-offered ProcessExited event will be handled by
            // the event loop.
            return
        }

        if (!resolved) {
            LOG.warn("Windows wasmtime: breakpoints did not resolve after $MAX_JIT_REGISTER_ATTEMPTS attempts, continuing with pending breakpoints")
            // The process may still be stopped (last loop iteration
            // didn't resume) or already running (if the loop broke
            // out after a timeout/exit). Interrupt to ensure we can
            // set breakpoints, then replay and resume.
            lldb.pause()
            // Give LLDB a moment to process the interrupt.
            lldb.waitForStopEvent(2_000)
            lldb.removeAllBreakpoints()
            replayBreakpointsToLldb()
        }
        lldb.resume()
    }

    /**
     * Queries LLDB to determine whether the current stop location
     * corresponds to any registered breakpoint and, if so, returns
     * the matching IDE breakpoint. This is used by WAMR after
     * connecting and replaying breakpoints: the initial `trace` stop
     * may already be at (or very near) the breakpoint address, so
     * resuming would skip past it.
     *
     * Two checks are performed in order:
     *
     * 1. **Exact PC match** — compare the current `lldb.frame.pc`
     *    against every breakpoint-location load address.  This is
     *    the most precise check and produces no false positives.
     *
     * 2. **Source-line match** — if the PC check returns "none"
     *    (which happens with WAMR when the trace-stop PC is a few
     *    bytes past the breakpoint address), fall back to comparing
     *    the frame's resolved source file + line against each
     *    breakpoint location's resolved source file + line.  The
     *    comparison uses `SBFileSpec.GetDirectory()` and
     *    `SBFileSpec.GetFilename()` so that files with the same
     *    basename but different directories (e.g. multiple Rust
     *    crates each having `src/lib.rs`) are distinguished.
     *
     * When a match is found, the LLDB breakpoint number is used to
     * map back to the corresponding IDE breakpoint (breakpoints are
     * replayed in `registeredBreakpoints` iteration order, so LLDB
     * breakpoint N corresponds to the Nth registered entry).
     *
     * @return the matching breakpoint entry (breakpoint → file+line),
     *         or `null` if the current stop does not match any
     *         breakpoint.
     */
    protected fun checkInitialStopMatchesBreakpoint(
        communicator: LldbCommunicator,
    ): Map.Entry<XLineBreakpoint<WasmBreakpointProperties>, Pair<String, Int>>? {
        if (registeredBreakpoints.isEmpty()) return null

        // --- Tier 1: exact PC-vs-address comparison ---
        //
        // Uses a single-line `script print(...)` invocation to avoid
        // entering the interactive Python REPL, which requires a
        // blank-line terminator and can interfere with the marker-
        // based response collection.
        val pcScriptLines = communicator.sendCommandAndCollectOutput(
            "script print(next((str(bp.GetID()) for bp in lldb.target.breakpoint_iter() " +
                "for loc in bp if loc.GetAddress().GetLoadAddress(lldb.target) == lldb.frame.pc), 'none'))",
            timeoutMs = 10_000,
        )

        val pcBpIdStr = pcScriptLines
            .firstOrNull { it.trim() != "" && !it.trim().startsWith("(lldb)") }
            ?.trim()

        LOG.debug("Initial stop PC check: scriptOutput=$pcScriptLines, parsed bpId=$pcBpIdStr")

        val pcMatch = pcBpIdStr?.toIntOrNull()?.let { bpNumber ->
            if (bpNumber >= 1) registeredBreakpoints.entries.drop(bpNumber - 1).firstOrNull() else null
        }

        if (pcMatch != null) {
            LOG.debug(
                "Initial stop at PC matches LLDB breakpoint (exact address) " +
                    "→ IDE breakpoint at ${pcMatch.value.first}:${pcMatch.value.second}"
            )
            return pcMatch
        }

        // --- Tier 2: source file + line match ---
        //
        // WAMR's trace stop can place the PC a few bytes past the
        // breakpoint address (e.g. PC=0x41a vs BP=0x418).  In that
        // case the exact-address check above returns "none".  Compare
        // the frame's resolved source line against each breakpoint
        // location's resolved source line (including directory to
        // prevent false positives from shared basenames).
        val lineScriptLines = communicator.sendCommandAndCollectOutput(
            "script " +
                "le=lldb.frame.GetLineEntry(); " +
                "fs=le.GetFileSpec(); " +
                "fd=fs.GetDirectory() or ''; fn=fs.GetFilename() or ''; fl=le.GetLine(); " +
                "print(next((str(bp.GetID()) for bp in lldb.target.breakpoint_iter() " +
                "for loc in bp " +
                "for ble in [loc.GetAddress().GetLineEntry()] " +
                "for bfs in [ble.GetFileSpec()] " +
                "if (bfs.GetDirectory() or '')==fd and (bfs.GetFilename() or '')==fn and ble.GetLine()==fl), 'none'))",
            timeoutMs = 10_000,
        )

        val lineBpIdStr = lineScriptLines
            .firstOrNull { it.trim() != "" && !it.trim().startsWith("(lldb)") }
            ?.trim()

        LOG.debug("Initial stop line check: scriptOutput=$lineScriptLines, parsed bpId=$lineBpIdStr")

        val lineMatch = lineBpIdStr?.toIntOrNull()?.let { bpNumber ->
            if (bpNumber >= 1) registeredBreakpoints.entries.drop(bpNumber - 1).firstOrNull() else null
        }

        if (lineMatch != null) {
            LOG.debug(
                "Initial stop at PC matches LLDB breakpoint (source line) " +
                    "→ IDE breakpoint at ${lineMatch.value.first}:${lineMatch.value.second}"
            )
        }

        return lineMatch
    }

    fun unregisterBreakpointInLldb(breakpoint: XLineBreakpoint<WasmBreakpointProperties>) {
        registeredBreakpoints.remove(breakpoint)
        // Remove all and re-add remaining breakpoints using basenames.
        lldb?.removeAllBreakpoints()
        for ((_, loc) in registeredBreakpoints) {
            val baseName = loc.first.substringAfterLast('/').substringAfterLast('\\')
            lldb?.sendBreakpoint(baseName, loc.second)
        }
    }

    override fun resume(context: XSuspendContext?) {
        if (engine == WasmEngine.WASMTIME) {
            advancePcPastTrapThen { lldb?.resume() }
        } else {
            lldb?.resume()
        }
    }

    override fun startPausing() {
        lldb?.pause() ?: LOG.debug("No LLDB communicator — cannot pause")
    }

    override fun startStepOver(context: XSuspendContext?) {
        if (engine == WasmEngine.WASMTIME) {
            advancePcPastTrapThen { lldb?.stepOver() }
        } else {
            lldb?.stepOver()
        }
    }

    override fun startStepInto(context: XSuspendContext?) {
        if (engine == WasmEngine.WASMTIME) {
            advancePcPastTrapThen { lldb?.stepInto() }
        } else {
            lldb?.stepInto()
        }
    }

    override fun startStepOut(context: XSuspendContext?) {
        if (engine == WasmEngine.WASMTIME) {
            advancePcPastTrapThen { lldb?.stepOut() }
        } else {
            lldb?.stepOut()
        }
    }

    /**
     * Advances the program counter past a wasmtime JIT trap
     * instruction, then executes the given [action] (e.g. resume,
     * step-over, step-into).
     *
     * Wasmtime JIT emits trap instructions at breakpoint sites:
     * `brk` (4 bytes) on ARM64, `ud2` (2 bytes) on x86_64.
     * When LLDB stops on such a trap, the PC still points at the
     * trap instruction. A simple `continue` or `thread step-inst`
     * would re-execute the same trap, causing an infinite loop.
     *
     * The fix is to read the current instruction's byte size via
     * LLDB's Python API and advance the PC by that amount before
     * issuing the real command. This is done through a
     * fire-and-forget `sendCommand` (no blocking wait for output),
     * so the call returns instantly and does not freeze the EDT.
     *
     * Stop-event detection is suppressed while the PC is adjusted
     * to prevent any intermediate LLDB output from being
     * misinterpreted as a user-visible stop.
     */
    private fun advancePcPastTrapThen(action: () -> Unit) {
        val communicator = lldb ?: run {
            LOG.debug("No LLDB communicator — cannot step")
            return
        }
        communicator.withStopEventsSuppressed {
            communicator.sendCommand(ADVANCE_PC_PAST_TRAP_CMD)
        }
        action()
    }

    /**
     * Advances the PC past the current trap instruction during
     * auto-resume (called from [handleStopImpl] which already
     * runs inside [LldbCommunicator.withStopEventsSuppressed]),
     * then resumes execution.
     *
     * The PC adjustment is only needed for Wasmtime where
     * JIT-emitted trap instructions require skipping. For
     * WAMR (interpreter-based GDB stub), a plain resume suffices.
     */
    private fun resumePastTrap(communicator: LldbCommunicator) {
        if (engine == WasmEngine.WASMTIME) {
            communicator.sendCommand(ADVANCE_PC_PAST_TRAP_CMD)
        }
        communicator.resume()
    }

    override fun stop() {
        eventLoopThread?.interrupt()
        lldb?.destroy()
        processHandler.destroyProcess()
    }

    /** Starts the background event loop that watches for LLDB stop events. */
    protected fun startEventLoopForSession() {
        startEventLoop()
    }

    /**
     * Starts a background thread that watches for LLDB stop events
     * and translates them into [XDebugSession] suspend calls.
     */
    private fun startEventLoop() {
        eventLoopThread = Thread({
            try {
                while (lldb != null && processHandler.isProcessTerminated.not()) {
                    val event = lldb.waitForStopEvent(1_000) ?: continue
                    when (event) {
                        is LldbCommunicator.StopEvent.BreakpointHit -> handleStop(event)
                        is LldbCommunicator.StopEvent.Stepped -> handleStop(event)
                        is LldbCommunicator.StopEvent.StoppedAtEntry -> {
                            // Unexpected stop at entry; resume automatically.
                            LOG.info("Process stopped at entry; continuing execution")
                            lldb.resume()
                        }
                        is LldbCommunicator.StopEvent.ProcessExited -> {
                            LOG.info("Wasmtime process exited with code ${event.exitCode}")
                            ApplicationManager.getApplication().invokeLater {
                                session.stop()
                            }
                            break
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // Shutdown
            }
        }, "wasm-lldb-event-loop").also { it.isDaemon = true; it.start() }
    }

    /**
     * Called when LLDB reports a stop (breakpoint or step).
     * Parses the frame location from recent LLDB output and suspends
     * the debug session at that source position.
     *
     * Because LLDB breakpoints are set by basename, a single IDE
     * breakpoint on `calculator/src/lib.rs:11` also creates an LLDB
     * breakpoint in `adder/src/lib.rs:11` (same basename). When the
     * stop location does not match any registered IDE breakpoint
     * (full-path check), the stop is treated as a "wrong file" hit
     * and execution is automatically resumed.
     */
    private fun handleStop(event: LldbCommunicator.StopEvent) {
        // Suppress stop-event detection while we issue informational
        // commands (`frame info`). Without this, the `frame info` output
        // echoes `stop reason = breakpoint ...` which would be
        // misinterpreted as a brand-new stop event, re-entering
        // handleStop() and creating an infinite loop.
        val communicator = lldb ?: return
        communicator.withStopEventsSuppressed {
            handleStopImpl(communicator, event)
        }
    }

    private fun handleStopImpl(communicator: LldbCommunicator, event: LldbCommunicator.StopEvent) {
        // On Windows, Exception 0x80000003 stops may fire on system
        // threads (e.g. thread 5 in ntdll.dll!DbgBreakPoint) that are
        // unrelated to Wasm execution. These have an empty breakpointId
        // (the LLDB output doesn't match "breakpoint N.M") and a
        // non-main thread. Select thread 1 before querying frame info
        // so we inspect the wasmtime main thread, not a system thread.
        // If the stop is genuinely on a non-main thread in a system
        // DLL, just resume without advancing PC.
        if (SystemInfo.isWindows &&
            event is LldbCommunicator.StopEvent.BreakpointHit &&
            event.breakpointId.isEmpty() &&
            event.threadId != 1
        ) {
            // Check if this is a system DLL stop (e.g. ntdll.dll,
            // kernelbase.dll). Query frame info on the stopped thread
            // first to see where we are.
            val sysLines = communicator.sendCommandAndCollectOutput("frame info")
            val frameDesc = sysLines.joinToString(" ")
            if (frameDesc.contains(".dll") || frameDesc.contains(".DLL")) {
                LOG.info(
                    "Ignoring Exception 0x80000003 on system thread " +
                        "${event.threadId}: $frameDesc — resuming without PC advance"
                )
                communicator.resume()
                return
            }
            // Not a system DLL — switch to thread 1 for further
            // inspection since Wasm execution runs on the main thread.
            communicator.sendCommand("thread select 1")
        }

        // Use sendCommandAndCollectOutput so the response is fenced
        // and stop-event detection is suppressed for the output lines.
        var lines = communicator.sendCommandAndCollectOutput("frame info")

        // WAMR remote debugging sometimes causes the marker-based
        // output collection to time out (the Python `script` command
        // may not interleave reliably with the GDB remote protocol).
        // Retry once with a longer timeout before giving up.
        if (lines.isEmpty()) {
            LOG.info("frame info returned empty output, retrying with longer timeout")
            lines = communicator.sendCommandAndCollectOutput("frame info", timeoutMs = 10_000)
        }

        val rawDwarfPath = parseRawDwarfPath(lines)
        val rawDwarfLine = parseRawDwarfLine(lines)
        var sourcePosition = parseSourcePosition(lines)
        val functionName = parseLldbFunctionName(lines)

        LOG.debug("handleStop: sourcePosition=$sourcePosition, rawDwarf=$rawDwarfPath, function=$functionName, lines=$lines")

        // Find the breakpoint that was hit by comparing the stop
        // location against registered breakpoint paths. Because LLDB
        // breakpoints are set by basename, a stop may land in the
        // wrong file (same basename, different directory).
        //
        // When the raw DWARF path from `frame info` is relative
        // (e.g. "src/lib.rs"), `parseSourcePosition` resolves it via
        // suffix matching against registered breakpoints. This can
        // silently pick the wrong file even when only one breakpoint
        // file matches — the stop may actually be in a *different*
        // file that shares the same relative DWARF path but has no
        // breakpoint set (e.g. stopped in adder/src/lib.rs but
        // resolved to calculator/src/lib.rs because only calculator
        // has a breakpoint).
        //
        // To detect this, whenever suffix matching was used (raw DWARF
        // path differs from resolved path), we query the DWARF
        // compilation-unit path from LLDB and verify it is consistent
        // with the resolved IDE path. If the paths are inconsistent
        // (different crate/directory above the shared suffix), the
        // stop is in the wrong file and must be auto-resumed.
        val usedSuffixMatching = sourcePosition != null &&
            rawDwarfPath != null &&
            sourcePosition!!.file.path != rawDwarfPath &&
            !sourcePosition!!.file.path.let { p ->
                // Check if the raw DWARF path resolved to the same file
                // via direct filesystem lookup (non-suffix case).
                LocalFileSystem.getInstance().findFileByPath(rawDwarfPath)?.path == p
            }

        val ambiguousResolution = sourcePosition == null &&
            rawDwarfPath != null && rawDwarfLine != null &&
            hasDwarfPathAmbiguity(rawDwarfPath)

        val verifiedPath: String?
        if (ambiguousResolution) {
            // Multiple breakpoint files match the same DWARF suffix:
            // query CU path to disambiguate.
            val cuPath = queryCompilationUnitPath(communicator)
            verifiedPath = cuPath

            if (cuPath != null && rawDwarfLine != null) {
                val vFile = LocalFileSystem.getInstance().findFileByPath(cuPath)
                if (vFile != null) {
                    sourcePosition = XDebuggerUtilImpl.getInstance()
                        .createPosition(vFile, rawDwarfLine - 1)
                }
            }
        } else if (usedSuffixMatching) {
            // Single suffix match — verify the resolved file is
            // actually the one the debugger stopped in.
            //
            // Multiple verification strategies are tried in order:
            //
            // 1. CU path precise: the DWARF compilation-unit path
            //    from LLDB ends with the project-relative IDE path
            //    (e.g. CU `/build/calculator/src/lib.rs` ends with
            //    `calculator/src/lib.rs`).
            //
            // 2. CU path heuristic: trailing component matching
            //    between CU and IDE paths (≥ 3 common components
            //    confirms same crate; short CU paths are optimistic).
            //
            // 3. Function name: the Rust mangled symbol name in the
            //    LLDB frame output embeds the crate name (e.g.
            //    `calculator..Component` in the demangled name).
            //    When CU path checks are inconclusive (the build-
            //    time CU root differs from the IDE project root),
            //    the crate name extracted from the function symbol
            //    is the most reliable disambiguation signal.
            //
            // Only when ALL available signals indicate a mismatch
            // is the stop treated as spurious and auto-resumed.
            val cuPath = queryCompilationUnitPath(communicator)
            val resolvedIdePath = sourcePosition!!.file.path
            var verified = false

            // Strategy 1 & 2: CU path checks.
            if (cuPath != null) {
                val projectRelative = extractProjectRelativePath(resolvedIdePath)
                verified = if (projectRelative != null) {
                    cuPathEndsWithProjectRelative(cuPath, projectRelative) ||
                        cuPathMatchesIdePath(cuPath, resolvedIdePath)
                } else {
                    cuPathMatchesIdePath(cuPath, resolvedIdePath)
                }
            }

            // Strategy 3: function name crate extraction.
            if (!verified && functionName != null) {
                verified = functionNameMatchesIdePath(functionName, resolvedIdePath)
                if (verified) {
                    LOG.debug(
                        "CU path verification was inconclusive but function " +
                            "name '$functionName' matches IDE path " +
                            "'$resolvedIdePath' — accepting suffix match"
                    )
                }
            }

            // Strategy 4: when no verification signal is available
            // (CU path query returned null AND function name has no
            // recognisable crate pattern), accept the suffix match
            // optimistically — there is no contradicting evidence and
            // the alternative (auto-resume) would silently skip every
            // breakpoint in simple single-file projects where DWARF
            // records only a bare filename (e.g. "debug_add.rs").
            if (!verified && cuPath == null &&
                (functionName == null || extractCrateName(functionName) == null)
            ) {
                verified = true
                LOG.debug(
                    "No CU path and no crate name extractable from " +
                        "function '$functionName' — accepting suffix " +
                        "match optimistically for '$resolvedIdePath'"
                )
            }

            if (!verified) {
                LOG.info(
                    "Suffix match verification failed: CU='$cuPath', " +
                        "resolved='$resolvedIdePath', " +
                        "function='$functionName', " +
                        "rawDwarf='$rawDwarfPath' — spurious suffix match, resuming"
                )
                resumePastTrap(communicator)
                return
            }
            verifiedPath = sourcePosition?.file?.path
        } else {
            verifiedPath = sourcePosition?.file?.path
        }

        val resolvedPosition = sourcePosition
        if (resolvedPosition != null && verifiedPath != null) {
            val hitBreakpoint = registeredBreakpoints.entries.firstOrNull { (_, loc) ->
                loc.second == resolvedPosition.line + 1 &&
                    pathMatchesBreakpointLocation(verifiedPath, loc.first)
            }?.key

            // When LLDB resolves a file/line breakpoint, it may shift
            // the actual stop location to a nearby line that has code
            // (e.g. user sets line 11, LLDB resolves to line 12).
            // If the stop came from a real LLDB breakpoint (non-empty
            // breakpointId) and the file matches a registered
            // breakpoint, accept the nearest breakpoint in that file
            // even if the line doesn't match exactly.
            val effectiveBreakpoint = hitBreakpoint
                ?: if (event is LldbCommunicator.StopEvent.BreakpointHit &&
                    event.breakpointId.isNotEmpty()
                ) {
                    registeredBreakpoints.entries
                        .filter { (_, loc) -> pathMatchesBreakpointLocation(verifiedPath, loc.first) }
                        .minByOrNull { (_, loc) -> kotlin.math.abs(loc.second - (resolvedPosition.line + 1)) }
                        ?.key
                } else null

            if (effectiveBreakpoint != null) {
                val frame = WasmStackFrame(
                    resolvedPosition, functionName, lldb,
                    dwarfVariableResolver = dwarfVariableResolver,
                    engine = engine,
                )
                val context = WasmSuspendContext(frame, lldb, this)
                ApplicationManager.getApplication().invokeLater {
                    session.breakpointReached(effectiveBreakpoint, null, context)
                }
                return
            }

            // Check whether the stop is at a line that *some* breakpoint
            // targets but in the wrong file (basename collision). If so,
            // this is a spurious LLDB stop — resume automatically.
            val isSpuriousBreakpoint = registeredBreakpoints.values.any { loc ->
                loc.second == resolvedPosition.line + 1 &&
                    verifiedPath.substringAfterLast('/') == loc.first.substringAfterLast('/') &&
                    !pathMatchesBreakpointLocation(verifiedPath, loc.first)
            }
            if (isSpuriousBreakpoint) {
                LOG.info(
                    "Skipping spurious breakpoint hit in wrong file: " +
                        "stopped at $verifiedPath:${resolvedPosition.line + 1}, " +
                        "no matching IDE breakpoint — resuming"
                )
                resumePastTrap(communicator)
                return
            }
        }

        // If this stop came from a BreakpointHit event (real LLDB
        // breakpoint or EXC_BREAKPOINT) but no registered IDE breakpoint
        // matched the location, this is most likely a wasmtime-internal
        // trap (e.g. `unreachable`, bounds check, or other JIT-emitted
        // `brk` instruction) — not a user breakpoint.  Auto-resume
        // instead of pausing the IDE.
        //
        // WAMR is an exception: it uses an interpreter-based GDB
        // remote stub (not JIT compilation), so it does not emit
        // spurious EXC_BREAKPOINT / trap signals. Every BreakpointHit
        // from WAMR is a real user breakpoint. When location
        // resolution fails (e.g. `frame info` timed out or the DWARF
        // path could not be mapped to a local file), we must NOT
        // auto-resume — doing so would silently skip every
        // breakpoint the user set.
        if (event is LldbCommunicator.StopEvent.BreakpointHit && engine == WasmEngine.WASMTIME) {
            LOG.info(
                "No matching IDE breakpoint for stop at " +
                    "${verifiedPath ?: rawDwarfPath}:${rawDwarfLine ?: "?"} " +
                    "(breakpointId='${event.breakpointId}') — " +
                    "likely wasmtime-internal trap, resuming"
            )
            resumePastTrap(communicator)
            return
        }

        // For WAMR (and other non-Wasmtime engines): when the stop
        // location could not be resolved to a source position, try
        // to find a matching registered breakpoint by LLDB breakpoint
        // number so we can still report it as a breakpoint hit in
        // the IDE. LLDB assigns breakpoint numbers in the order
        // they are set (1, 2, 3 …). The `replayBreakpointsToLldb`
        // method sets them in `registeredBreakpoints` iteration
        // order, so breakpoint number N corresponds to the Nth
        // registered breakpoint.
        if (event is LldbCommunicator.StopEvent.BreakpointHit &&
            resolvedPosition == null
        ) {
            val bpNumber = event.breakpointId.substringBefore('.').toIntOrNull()
            val matchedEntry = if (bpNumber != null && bpNumber >= 1) {
                registeredBreakpoints.entries.drop(bpNumber - 1).firstOrNull()
            } else null

            if (matchedEntry != null) {
                val (bp, loc) = matchedEntry
                val bpFile = LocalFileSystem.getInstance().findFileByPath(loc.first)
                val bpPosition = if (bpFile != null) {
                    XDebuggerUtilImpl.getInstance()
                        .createPosition(bpFile, loc.second - 1)
                } else null
                LOG.info(
                    "WAMR breakpoint hit (id='${event.breakpointId}') — " +
                        "resolved via breakpoint number to ${loc.first}:${loc.second}"
                )
                val frame = WasmStackFrame(
                    bpPosition, functionName, lldb,
                    dwarfVariableResolver = dwarfVariableResolver,
                    engine = engine,
                )
                val context = WasmSuspendContext(frame, lldb, this)
                ApplicationManager.getApplication().invokeLater {
                    session.breakpointReached(bp, null, context)
                }
                return
            }

            // Could not match by breakpoint number — still present
            // the stop so the user is not silently skipped.
            LOG.info(
                "WAMR breakpoint hit (id='${event.breakpointId}') at " +
                    "${rawDwarfPath ?: "unknown"}:${rawDwarfLine ?: "?"} — " +
                    "could not match to IDE breakpoint, presenting stop"
            )
        }

        // On Windows with Wasmtime, spurious `trace` stops can occur
        // in JIT code with no DWARF source info (e.g. after resuming
        // from a system DLL exception). Auto-resume these instead of
        // presenting a bogus stop to the user.
        if (SystemInfo.isWindows &&
            engine == WasmEngine.WASMTIME &&
            event is LldbCommunicator.StopEvent.Stepped &&
            sourcePosition == null
        ) {
            LOG.info(
                "Ignoring trace stop with no source position at " +
                    "${rawDwarfPath ?: "unknown"}:${rawDwarfLine ?: "?"} " +
                    "(function='$functionName') — resuming"
            )
            communicator.resume()
            return
        }

        val frame = WasmStackFrame(
            sourcePosition, functionName, lldb,
            dwarfVariableResolver = dwarfVariableResolver,
            engine = engine,
        )
        val context = WasmSuspendContext(frame, lldb, this)
        ApplicationManager.getApplication().invokeLater {
            session.positionReached(context)
        }
    }

    /**
     * Extracts the project-relative portion of an absolute IDE path
     * using the current project's base path.
     *
     * E.g. for base `/Users/dev/project` and IDE path
     * `/Users/dev/project/calculator/src/lib.rs`, returns
     * `calculator/src/lib.rs`.
     *
     * @return the relative path, or `null` if the IDE path is not
     *         under the project base directory.
     */
    private fun extractProjectRelativePath(idePath: String): String? {
        val basePath = session.project.basePath ?: return null
        return extractProjectRelativePath(basePath, idePath)
    }

    /**
     * Checks whether the given DWARF path is ambiguous among registered
     * breakpoints — i.e. multiple breakpoint files from *different* IDE
     * paths all end with the same DWARF suffix. This is the case when
     * e.g. both `calculator/src/lib.rs` and `adder/src/lib.rs` are
     * registered and DWARF reports `src/lib.rs`.
     *
     * When there is no ambiguity (zero or one matching file), the
     * normal suffix resolution in [resolveFileByDwarfPath] is
     * sufficient and the caller should not query the compilation-unit
     * path from DWARF.
     */
    private fun hasDwarfPathAmbiguity(dwarfPath: String): Boolean {
        val normalised = dwarfPath.replace('\\', '/')
        val matchingPaths = mutableSetOf<String>()
        for ((_, loc) in registeredBreakpoints) {
            val idePath = loc.first
            if (idePath.endsWith("/$normalised") || idePath == normalised) {
                matchingPaths.add(idePath)
            }
        }
        return matchingPaths.size > 1
    }

    /**
     * Queries LLDB for the full compilation-unit file path of the
     * current frame using LLDB's Python scripting API.
     *
     * The DWARF compilation unit stores both `DW_AT_comp_dir` and
     * `DW_AT_name`; LLDB combines them into the full path accessible
     * via `SBCompileUnit.file.fullpath`. This uniquely identifies the
     * source file even when multiple compilation units share the same
     * relative filename (e.g. `src/lib.rs` in both `calculator` and
     * `adder`).
     *
     * @return the full compilation-unit path, or `null` if the query
     *         fails or produces no usable output.
     */
    private fun queryCompilationUnitPath(communicator: LldbCommunicator): String? {
        val cuLines = communicator.sendCommandAndCollectOutput(
            "script print(lldb.frame.compile_unit.file.fullpath or '')"
        )
        val cuPath = cuLines
            .firstOrNull { it.isNotBlank() && it.trim() != "None" && it.trim() != "" }
            ?.trim()
        if (cuPath.isNullOrEmpty()) return null
        LOG.debug("Compilation-unit path from LLDB: $cuPath")
        // Classic Wasm JIT compilation units may have an opaque
        // hash suffix appended by LLDB, e.g.
        //   /path/to/src/lib.rs/@/9le4nom1b1hdoeh9b7lbjqyuq
        // Strip the `/@/<hash>` tail so downstream path comparisons
        // work against normal filesystem paths.
        return stripCuHashSuffix(cuPath)
    }

    /**
     * Parses source position from LLDB output lines.
     *
     * LLDB typically outputs lines like:
     * ```
     *    frame #0: 0x... module`func at file.c:42:5
     * ```
     *
     * The file path reported by LLDB comes from the DWARF debug info
     * and may be a relative or build-time path that does not exist on
     * the local filesystem. When a direct lookup fails, the method
     * falls back to matching the basename against registered breakpoint
     * file paths so the IDE can navigate to the correct source.
     */
    private fun parseSourcePosition(lines: List<String>): XSourcePosition? {
        val location = parseLldbFrameLocation(lines) ?: return null
        val filePath = location.filePath
        val lineNum = location.line

        // LLDB may report line 0 when no line info is available;
        // guard against negative values in createPosition.
        if (lineNum <= 0) return null

        // Try the exact path first (works for non-composed modules)
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
        if (vFile != null) {
            return XDebuggerUtilImpl.getInstance().createPosition(vFile, lineNum - 1)
        }

        // Fall back: match the DWARF filename against known breakpoint
        // paths using suffix matching (e.g. DWARF says "src/lib.rs",
        // IDE knows "/Users/.../calculator/src/lib.rs").
        val resolvedFile = resolveFileByDwarfPath(filePath)
        if (resolvedFile != null) {
            return XDebuggerUtilImpl.getInstance().createPosition(resolvedFile, lineNum - 1)
        }
        return null
    }

    /**
     * Attempts to find a local [VirtualFile] that matches the given DWARF
     * path by checking registered breakpoint locations. When the DWARF
     * path is relative (e.g. `src/lib.rs`) this matches it against full
     * IDE paths that end with the same suffix, requiring a path-separator
     * boundary so that `adder/src/lib.rs` does not accidentally match
     * a breakpoint set in `calculator/src/lib.rs`.
     *
     * When multiple registered breakpoints resolve to *different* IDE
     * files for the same DWARF suffix (e.g. both
     * `calculator/src/lib.rs` and `adder/src/lib.rs` match
     * `src/lib.rs`), the result is ambiguous and `null` is returned so
     * that callers do not silently pick the wrong file.
     */
    internal fun resolveFileByDwarfPath(dwarfPath: String): com.intellij.openapi.vfs.VirtualFile? {
        val normalised = dwarfPath.replace('\\', '/')
        val matchingPaths = mutableSetOf<String>()
        for ((_, loc) in registeredBreakpoints) {
            val idePath = loc.first
            // The IDE path ends with the DWARF path preceded by a separator
            // (e.g. IDE "/home/user/calculator/src/lib.rs" ends with
            // "/src/lib.rs" when DWARF reports "src/lib.rs").
            if (idePath.endsWith("/$normalised") || idePath == normalised) {
                matchingPaths.add(idePath)
            }
        }
        if (matchingPaths.size == 1) {
            return LocalFileSystem.getInstance().findFileByPath(matchingPaths.first())
        }
        if (matchingPaths.size > 1) {
            // Ambiguous: multiple breakpoints in different files share
            // the same DWARF suffix — caller must disambiguate.
            LOG.debug(
                "Ambiguous DWARF path '$normalised' matches ${matchingPaths.size} " +
                    "registered breakpoint files: $matchingPaths"
            )
            return null
        }
        // Also try to find the file in the project base directory
        val projectBasePath = session.project.basePath
        if (projectBasePath != null) {
            val candidate = LocalFileSystem.getInstance().findFileByPath("$projectBasePath/$normalised")
            if (candidate != null) return candidate
        }
        return null
    }

    companion object {
        private val LOG = Logger.getInstance(WasmDebugProcess::class.java)

        /**
         * LLDB command that advances the program counter past the
         * current trap instruction. Wasmtime JIT emits trap
         * instructions at breakpoint sites: `brk` (4 bytes) on
         * ARM64/AArch64, `ud2` (2 bytes) on x86_64. When LLDB
         * stops on such a trap, the PC still points at the trap;
         * this command moves the PC forward by that amount so that
         * a subsequent
         * `continue` or `next` does not re-hit the same trap.
         *
         * Uses LLDB's built-in Python interpreter so it works
         * reliably across LLDB versions (unlike `register write
         * pc \`$pc+4\`` which broke in some Xcode releases).
         */
        private const val ADVANCE_PC_PAST_TRAP_CMD =
            "script lldb.frame.SetPC(lldb.frame.GetPC() + " +
                "(4 if 'arm' in lldb.target.GetTriple() or 'aarch64' in lldb.target.GetTriple() else 2))"

        /**
         * The GDB JIT interface function that wasmtime calls after
         * JIT-compiling a module and registering its debug symbols.
         * On Windows we set a breakpoint on this function to detect
         * when JIT symbols are available so user breakpoints can
         * resolve.
         */
        private const val JIT_REGISTER_FUNCTION = "__jit_debug_register_code"

        /**
         * Maximum time in milliseconds to wait for each
         * [JIT_REGISTER_FUNCTION] breakpoint hit on Windows.
         */
        private const val WINDOWS_JIT_WAIT_TIMEOUT_MS = 10_000L

        /**
         * Maximum number of [JIT_REGISTER_FUNCTION] hits to wait
         * through before giving up on breakpoint resolution.
         * Wasmtime typically fires this 2–5 times; 20 is generous.
         */
        private const val MAX_JIT_REGISTER_ATTEMPTS = 3
    }
}

private class WasmDebuggerEditorsProvider : XDebuggerEditorsProvider() {
    override fun getFileType(): FileType = PlainTextFileType.INSTANCE

    override fun createDocument(
        project: Project,
        expression: XExpression,
        sourcePosition: XSourcePosition?,
        mode: EvaluationMode,
    ): Document {
        return com.intellij.openapi.editor.EditorFactory.getInstance().createDocument(expression.expression)
    }
}
