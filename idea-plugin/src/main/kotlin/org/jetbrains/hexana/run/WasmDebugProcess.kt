package org.jetbrains.hexana.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
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
            //
            // We use `run` (not `process launch --stop-at-entry`) because
            // LLDB's JIT loader plugin resolves pending breakpoints during
            // process execution as wasmtime JIT-compiles the Wasm module.
            // With `--stop-at-entry`, the process stops at the native
            // entry point (`_dyld_start`) before wasmtime has even started,
            // so breakpoints remain permanently pending.
            lldb.runProcess()
            startEventLoop()
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
            val baseName = loc.first.substringAfterLast('/')
            lldb?.sendBreakpoint(baseName, loc.second)
        }
    }

    fun unregisterBreakpointInLldb(breakpoint: XLineBreakpoint<WasmBreakpointProperties>) {
        registeredBreakpoints.remove(breakpoint)
        // Remove all and re-add remaining breakpoints using basenames.
        lldb?.removeAllBreakpoints()
        for ((_, loc) in registeredBreakpoints) {
            val baseName = loc.first.substringAfterLast('/')
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
     * Wasmtime JIT on macOS/ARM emits `brk` (trap) instructions
     * at breakpoint sites. When LLDB stops on such a trap, the
     * PC still points at the `brk` instruction. A simple
     * `continue` or `thread step-inst` would re-execute the same
     * trap, causing an infinite `EXC_BREAKPOINT` loop.
     *
     * The fix is to advance the PC past the 4-byte `brk`
     * instruction via LLDB's Python API before issuing the real
     * command. This is done through a fire-and-forget
     * `sendCommand` (no blocking wait for output), so the call
     * returns instantly and does not freeze the EDT.
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
     * The PC adjustment is only needed for Wasmtime on macOS/ARM
     * where JIT-emitted `brk` instructions require skipping. For
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

            if (hitBreakpoint != null) {
                val frame = WasmStackFrame(
                    resolvedPosition, functionName, lldb,
                    dwarfVariableResolver = dwarfVariableResolver,
                    engine = engine,
                )
                val context = WasmSuspendContext(frame, lldb, this)
                ApplicationManager.getApplication().invokeLater {
                    session.breakpointReached(hitBreakpoint, null, context)
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
         * current instruction. On macOS/ARM64, wasmtime JIT emits
         * 4-byte `brk` (trap) instructions at breakpoint sites.
         * When LLDB stops on such a trap, the PC still points at
         * the `brk`; this command moves it forward by 4 bytes so
         * that a subsequent `continue` or `next` does not re-hit
         * the same trap.
         *
         * Uses LLDB's built-in Python interpreter so it works
         * reliably across LLDB versions (unlike `register write
         * pc \`$pc+4\`` which broke in some Xcode releases).
         */
        private const val ADVANCE_PC_PAST_TRAP_CMD =
            "script lldb.frame.SetPC(lldb.frame.GetPC() + 4)"
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
