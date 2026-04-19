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
class WasmDebugProcess(
    session: XDebugSession,
    private val console: ExecutionConsole,
    private val processHandler: ProcessHandler,
    private val lldb: LldbCommunicator?,
    private val compositionUsed: Boolean = false,
    private val dwarfInjected: Boolean = false,
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

    @Volatile
    private var eventLoopThread: Thread? = null

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
        val fileName = position.file.name
        lldb?.sendBreakpoint(fileName, line)
        session.setBreakpointVerified(breakpoint)
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
        advancePcPastTrapThen { lldb?.resume() }
    }

    override fun startPausing() {
        lldb?.pause() ?: LOG.debug("No LLDB communicator — cannot pause")
    }

    override fun startStepOver(context: XSuspendContext?) {
        advancePcPastTrapThen { lldb?.stepOver() }
    }

    override fun startStepInto(context: XSuspendContext?) {
        advancePcPastTrapThen { lldb?.stepInto() }
    }

    override fun startStepOut(context: XSuspendContext?) {
        advancePcPastTrapThen { lldb?.stepOut() }
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
     */
    private fun resumePastTrap(communicator: LldbCommunicator) {
        communicator.sendCommand(ADVANCE_PC_PAST_TRAP_CMD)
        communicator.resume()
    }

    override fun stop() {
        eventLoopThread?.interrupt()
        lldb?.destroy()
        processHandler.destroyProcess()
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
        val lines = communicator.sendCommandAndCollectOutput("frame info")

        val rawDwarfPath = parseRawDwarfPath(lines)
        val rawDwarfLine = parseRawDwarfLine(lines)
        var sourcePosition = parseSourcePosition(lines)
        val functionName = parseFunctionName(lines)

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
        if (event is LldbCommunicator.StopEvent.BreakpointHit) {
            LOG.info(
                "No matching IDE breakpoint for stop at " +
                    "${verifiedPath ?: rawDwarfPath}:${rawDwarfLine ?: "?"} " +
                    "(breakpointId='${event.breakpointId}') — " +
                    "likely wasmtime-internal trap, resuming"
            )
            resumePastTrap(communicator)
            return
        }

        val frame = WasmStackFrame(
            sourcePosition, functionName, lldb,
            dwarfVariableResolver = dwarfVariableResolver,
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
        val normBase = basePath.replace('\\', '/')
        val normIde = idePath.replace('\\', '/')
        if (normIde.startsWith("$normBase/")) {
            return normIde.removePrefix("$normBase/")
        }
        return null
    }

    /**
     * Checks whether the CU path (absolute build-time path from LLDB)
     * ends with the given project-relative path at a path-separator
     * boundary.
     *
     * E.g. CU `/build/calculator/src/lib.rs` ends with
     * `calculator/src/lib.rs` → true.
     * CU `/build/adder/src/lib.rs` does NOT end with
     * `calculator/src/lib.rs` → false.
     */
    private fun cuPathEndsWithProjectRelative(cuPath: String, projectRelative: String): Boolean =
        Companion.cuPathEndsWithProjectRelative(cuPath, projectRelative)

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

    private fun pathMatchesBreakpointLocation(stoppedPath: String, breakpointPath: String): Boolean =
        Companion.pathMatchesBreakpointLocation(stoppedPath, breakpointPath)

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
        for (line in lines.asReversed()) {
            val match = FRAME_LOCATION_PATTERN.find(line) ?: continue
            val filePath = match.groupValues[1]
            val lineNum = match.groupValues[2].toIntOrNull() ?: continue

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

    /**
     * Extracts the raw file path from LLDB frame output without
     * attempting to resolve it to a local file. This is the path
     * exactly as LLDB reports it in the `at <path>:<line>` portion
     * of `frame info` (e.g. `src/lib.rs`).
     */
    private fun parseRawDwarfPath(lines: List<String>): String? {
        for (line in lines.asReversed()) {
            val match = FRAME_LOCATION_PATTERN.find(line) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    /**
     * Extracts the 1-based line number from the raw LLDB frame output.
     */
    private fun parseRawDwarfLine(lines: List<String>): Int? {
        for (line in lines.asReversed()) {
            val match = FRAME_LOCATION_PATTERN.find(line) ?: continue
            return match.groupValues[2].toIntOrNull()
        }
        return null
    }

    /**
     * Parses function name from LLDB frame output.
     * Delegates to companion [parseFunctionName] for testability.
     */
    private fun parseFunctionName(lines: List<String>): String? =
        Companion.parseFunctionName(lines)

    companion object {
        private val LOG = Logger.getInstance(WasmDebugProcess::class.java)

        /**
         * Parses function name from LLDB frame output lines.
         *
         * The LLDB `frame info` output contains lines like:
         * ```
         * frame #0: 0x... module`func_name(args) at file.c:42
         * ```
         * This method extracts `func_name` (without the arguments).
         */
        internal fun parseFunctionName(lines: List<String>): String? {
            for (line in lines.asReversed()) {
                val match = FUNCTION_NAME_PATTERN.find(line) ?: continue
                return match.groupValues[1]
            }
            return null
        }

        /**
         * Checks whether [stoppedPath] (the resolved file path from
         * the stop location) matches the [breakpointPath] (the full
         * IDE path stored when the breakpoint was registered).
         *
         * An exact match is tried first. When the stopped path comes
         * from DWARF (possibly relative), it is also accepted if the
         * breakpoint path ends with it preceded by a path separator,
         * so that DWARF "src/lib.rs" matches IDE
         * "/home/user/calculator/src/lib.rs" but not
         * "/home/user/adder/src/lib.rs".
         */
        internal fun pathMatchesBreakpointLocation(stoppedPath: String, breakpointPath: String): Boolean {
            if (stoppedPath == breakpointPath) return true
            // stoppedPath is a suffix of breakpointPath (DWARF relative resolved to IDE absolute)
            if (breakpointPath.endsWith("/$stoppedPath")) return true
            // breakpointPath is a suffix of stoppedPath (IDE path is a suffix of resolved path)
            if (stoppedPath.endsWith("/$breakpointPath")) return true
            return false
        }

        /**
         * Heuristic check: do the CU and IDE paths likely refer to
         * the same source file?
         *
         * This is a **fallback** used when the project base path is
         * not available. The primary verification in [handleStopImpl]
         * uses [cuPathEndsWithProjectRelative] which is always
         * precise.
         *
         * The heuristic counts trailing path components that match
         * between the CU and IDE paths. In the standard Rust/Wasm
         * layout `<crate>/src/<file>`, a matching tail of ≥ 3
         * components (crate + src + file) confirms same-crate
         * identity. A shorter tail (e.g. just `src/lib.rs`) means
         * the paths diverge at the crate directory level →
         * inconsistent.
         *
         * Examples:
         *
         * - CU: `/build/calculator/src/lib.rs`, IDE: `/Users/dev/calculator/src/lib.rs`
         *   → 3 matching components (`calculator/src/lib.rs`) → true ✓
         *
         * - CU: `/build/adder/src/lib.rs`, IDE: `/Users/dev/calculator/src/lib.rs`
         *   → 2 matching components (`src/lib.rs`) → false ✗
         *
         * - CU: `src/lib.rs` (short), IDE: `/Users/dev/calculator/src/lib.rs`
         *   → CU has ≤ 2 components → optimistic true ✓
         */
        internal fun cuPathMatchesIdePath(
            cuPath: String,
            idePath: String,
        ): Boolean {
            val normCu = cuPath.replace('\\', '/')
            val normIde = idePath.replace('\\', '/')

            if (normCu == normIde) return true
            if (normIde.endsWith("/$normCu") || normCu.endsWith("/$normIde")) return true

            val cuComponents = normCu.split('/').filter { it.isNotEmpty() }
            val ideComponents = normIde.split('/').filter { it.isNotEmpty() }

            // If either path has ≤ 2 components (e.g. "src/lib.rs"),
            // there is at most one non-filename component and we
            // cannot reliably distinguish crate directories — be
            // optimistic.
            if (cuComponents.size <= 2 || ideComponents.size <= 2) return true

            // Count how many trailing path components match between
            // the CU and IDE paths (walking from the filename toward
            // the root).
            val minLen = minOf(cuComponents.size, ideComponents.size)
            var matchingFromEnd = 0
            for (i in 1..minLen) {
                if (cuComponents[cuComponents.size - i] == ideComponents[ideComponents.size - i]) {
                    matchingFromEnd++
                } else {
                    break
                }
            }

            // If the shorter path is fully consumed without a
            // mismatch, one is a path-suffix of the other.
            if (matchingFromEnd >= minLen) return true

            // The matching tail covers the filename and possibly some
            // shared intermediate directories. For the paths to refer
            // to the same file the matching tail must include the
            // crate-identifying directory. In the common Rust/Wasm
            // layout `<crate>/src/<file>`, the crate directory is
            // exactly 2 levels above the filename, so a matching tail
            // of 3 components (crate + src + file) confirms same-crate
            // identity.
            //
            // When the matching tail is shorter (e.g. only "src/lib.rs"
            // or "lib.rs"), the crate directory is the FIRST component
            // that mismatches — which means the paths come from
            // different crates.
            //
            // NOTE: this heuristic handles the standard layout. When
            // the project base path is available, handleStopImpl uses
            // the more precise extractProjectRelativePath +
            // cuPathEndsWithProjectRelative instead.
            return matchingFromEnd >= 3
        }

        /**
         * Checks whether the CU path ends with the given project-
         * relative path at a path-separator boundary.
         *
         * This is the most reliable verification: the project-relative
         * path (e.g. `calculator/src/lib.rs`) is derived from the IDE
         * path by stripping the project base directory and always
         * includes the crate directory. The CU path (build-time
         * absolute) must end with this crate-identifying suffix for
         * the paths to refer to the same file.
         */
        internal fun cuPathEndsWithProjectRelative(cuPath: String, projectRelative: String): Boolean {
            val normCu = cuPath.replace('\\', '/')
            val normRel = projectRelative.replace('\\', '/')
            return normCu.endsWith("/$normRel") || normCu == normRel
        }

        /**
         * Backward-compatible wrapper kept for existing tests.
         *
         * Delegates to [cuPathMatchesIdePath].
         * The [rawDwarfPath] parameter is no longer used; the match is
         * based solely on the CU and IDE full paths.
         */
        internal fun cuPathConsistentWithIdePath(
            cuPath: String,
            idePath: String,
            @Suppress("UNUSED_PARAMETER") rawDwarfPath: String,
        ): Boolean = cuPathMatchesIdePath(cuPath, idePath)

        /**
         * Extracts the Rust crate name from a mangled/demangled
         * function symbol as reported by LLDB.
         *
         * Rust Wasm component-model symbols look like:
         * ```
         * _$LT$calculator..Component$u20$as$u20$...$GT$::calculate::h...
         * ```
         * The first `..`-delimited segment after `$LT$` is the crate
         * name (`calculator`).  For plain Rust symbols without the
         * component-model wrapper, the first `::` segment is the
         * crate name.
         *
         * @return the extracted crate name in lowercase, or `null`
         *         when the function name does not follow a recognised
         *         Rust mangling pattern.
         */
        internal fun extractCrateName(functionName: String): String? {
            // Component-model mangled pattern: _$LT$<crate>..
            val componentMatch = CRATE_FROM_COMPONENT_PATTERN.find(functionName)
            if (componentMatch != null) {
                return componentMatch.groupValues[1].lowercase()
            }
            // Plain Rust symbol: <crate>::<func>::<hash>
            val plainMatch = CRATE_FROM_PLAIN_PATTERN.find(functionName)
            if (plainMatch != null) {
                return plainMatch.groupValues[1].lowercase()
            }
            return null
        }

        /**
         * Checks whether the crate name extracted from the LLDB
         * function symbol appears as a path component in the IDE
         * breakpoint file path.
         *
         * E.g. function `_$LT$calculator..Component...` →
         * crate `calculator` → IDE path
         * `/Users/dev/project/calculator/src/lib.rs` contains
         * `/calculator/` → true.
         *
         * This is useful when CU path verification is inconclusive
         * (build-time paths don't match IDE project layout) because
         * the Rust crate name in the symbol table is always
         * authoritative.
         */
        internal fun functionNameMatchesIdePath(
            functionName: String,
            idePath: String,
        ): Boolean {
            val crate = extractCrateName(functionName) ?: return false
            val normIde = idePath.replace('\\', '/').lowercase()
            // The crate name must appear as a directory component in
            // the IDE path. Rust normalises crate names by replacing
            // hyphens with underscores (e.g. crate "hello-world"
            // becomes `hello_world` in symbol names), so we compare
            // with both the original crate name and a hyphen variant.
            if (normIde.contains("/$crate/")) return true
            val hyphenated = crate.replace('_', '-')
            if (hyphenated != crate && normIde.contains("/$hyphenated/")) return true
            return false
        }

        /**
         * Matches LLDB frame location: `at /path/to/file.c:42` or `at /path/to/file.c:42:5`
         */
        private val FRAME_LOCATION_PATTERN =
            """\bat\s+(.+?):(\d+)(?::\d+)?""".toRegex()

        /**
         * Matches function name in LLDB frame output.
         *
         * Component-model (backtick delimiter):
         * `frame #0: 0x... JIT(0x...)`func_name(args) at file.c:42`
         *
         * Classic wasm module (no backtick — function name follows
         * the closing parenthesis of the JIT module identifier):
         * `frame #0: 0x... JIT(0x...)func_name(args) at file.c:42`
         *
         * The `[)\x60]\x60?` prefix consumes the closing `)` or
         * backtick (or both when a backtick follows `)`) so the
         * captured group contains only the function name. The name
         * may be followed by parenthesized arguments before `at` or
         * `+`, so the pattern uses a lazy `\S+?` and an optional
         * `(...)` group.
         */
        private val FUNCTION_NAME_PATTERN =
            """[)`]`?(\S+?)(?:\(.*?\))?\s+(?:at|[+])""".toRegex()

        /**
         * Extracts crate name from Rust component-model mangled symbols.
         * E.g. `_$LT$calculator..Component...` → `calculator`.
         */
        private val CRATE_FROM_COMPONENT_PATTERN =
            "\\${'$'}LT\\${'$'}(\\w+)\\.\\.".toRegex()

        /**
         * Extracts crate name from plain Rust symbols: `<crate>::<rest>`.
         */
        private val CRATE_FROM_PLAIN_PATTERN =
            """^(\w+)::""".toRegex()

        /**
         * Matches the opaque hash suffix that LLDB appends to
         * compilation-unit paths for classic Wasm JIT frames:
         * `/@/<alphanumeric_hash>` at the end of the path.
         *
         * Example:
         * `/path/src/lib.rs/@/9le4nom1b1hdoeh9b7lbjqyuq`
         * → stripped to `/path/src/lib.rs`
         */
        private val CU_HASH_SUFFIX_PATTERN =
            """/@/[A-Za-z0-9]+$""".toRegex()

        internal fun stripCuHashSuffix(cuPath: String): String =
            cuPath.replace(CU_HASH_SUFFIX_PATTERN, "")

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
