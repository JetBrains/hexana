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
        lldb?.resume() ?: LOG.debug("No LLDB communicator — cannot resume")
    }

    override fun startPausing() {
        lldb?.pause() ?: LOG.debug("No LLDB communicator — cannot pause")
    }

    override fun startStepOver(context: XSuspendContext?) {
        lldb?.stepOver() ?: LOG.debug("No LLDB communicator — cannot step over")
    }

    override fun startStepInto(context: XSuspendContext?) {
        lldb?.stepInto() ?: LOG.debug("No LLDB communicator — cannot step into")
    }

    override fun startStepOut(context: XSuspendContext?) {
        lldb?.stepOut() ?: LOG.debug("No LLDB communicator — cannot step out")
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
                        is LldbCommunicator.StopEvent.BreakpointHit -> handleStop()
                        is LldbCommunicator.StopEvent.Stepped -> handleStop()
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
    private fun handleStop() {
        // Suppress stop-event detection while we issue informational
        // commands (`frame info`). Without this, the `frame info` output
        // echoes `stop reason = breakpoint ...` which would be
        // misinterpreted as a brand-new stop event, re-entering
        // handleStop() and creating an infinite loop.
        val communicator = lldb ?: return
        communicator.withStopEventsSuppressed {
            handleStopImpl(communicator)
        }
    }

    private fun handleStopImpl(communicator: LldbCommunicator) {
        // Use sendCommandAndCollectOutput so the response is fenced
        // and stop-event detection is suppressed for the output lines.
        val lines = communicator.sendCommandAndCollectOutput("frame info")

        val sourcePosition = parseSourcePosition(lines)
        val functionName = parseFunctionName(lines)

        LOG.debug("handleStop: sourcePosition=$sourcePosition, function=$functionName, lines=$lines")

        // Find the breakpoint that was hit by comparing the resolved
        // stop location against registered breakpoint paths. Because
        // LLDB breakpoints use basenames, a stop may land in the wrong
        // file (same basename, different directory). We use suffix
        // matching with a path-separator boundary so that
        // "calculator/src/lib.rs" does not match "adder/src/lib.rs".
        if (sourcePosition != null) {
            val stoppedPath = sourcePosition.file.path
            val hitBreakpoint = registeredBreakpoints.entries.firstOrNull { (_, loc) ->
                loc.second == sourcePosition.line + 1 &&
                    pathMatchesBreakpointLocation(stoppedPath, loc.first)
            }?.key

            if (hitBreakpoint != null) {
                val frame = WasmStackFrame(sourcePosition, functionName, lldb)
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
                loc.second == sourcePosition.line + 1 &&
                    stoppedPath.substringAfterLast('/') == loc.first.substringAfterLast('/') &&
                    !pathMatchesBreakpointLocation(stoppedPath, loc.first)
            }
            if (isSpuriousBreakpoint) {
                LOG.info(
                    "Skipping spurious breakpoint hit in wrong file: " +
                        "stopped at $stoppedPath:${sourcePosition.line + 1}, " +
                        "no matching IDE breakpoint — resuming"
                )
                communicator.resume()
                return
            }
        }

        val frame = WasmStackFrame(sourcePosition, functionName, lldb)
        val context = WasmSuspendContext(frame, lldb, this)
        ApplicationManager.getApplication().invokeLater {
            session.positionReached(context)
        }
    }

    /**
     * Checks whether [stoppedPath] (the resolved file path from the
     * stop location) matches the [breakpointPath] (the full IDE path
     * stored when the breakpoint was registered).
     *
     * An exact match is tried first. When the stopped path comes from
     * DWARF (possibly relative), it is also accepted if the breakpoint
     * path ends with it preceded by a path separator, so that DWARF
     * "src/lib.rs" matches IDE "/home/user/calculator/src/lib.rs" but
     * not "/home/user/adder/src/lib.rs".
     */
    private fun pathMatchesBreakpointLocation(stoppedPath: String, breakpointPath: String): Boolean {
        if (stoppedPath == breakpointPath) return true
        // stoppedPath is a suffix of breakpointPath (DWARF relative resolved to IDE absolute)
        if (breakpointPath.endsWith("/$stoppedPath")) return true
        // breakpointPath is a suffix of stoppedPath (IDE path is a suffix of resolved path)
        if (stoppedPath.endsWith("/$breakpointPath")) return true
        return false
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
     */
    internal fun resolveFileByDwarfPath(dwarfPath: String): com.intellij.openapi.vfs.VirtualFile? {
        val normalised = dwarfPath.replace('\\', '/')
        for ((_, loc) in registeredBreakpoints) {
            val idePath = loc.first
            // The IDE path ends with the DWARF path preceded by a separator
            // (e.g. IDE "/home/user/calculator/src/lib.rs" ends with
            // "/src/lib.rs" when DWARF reports "src/lib.rs").
            if (idePath.endsWith("/$normalised") || idePath == normalised) {
                return LocalFileSystem.getInstance().findFileByPath(idePath)
            }
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
     * Parses function name from LLDB frame output.
     */
    private fun parseFunctionName(lines: List<String>): String? {
        for (line in lines.asReversed()) {
            val match = FUNCTION_NAME_PATTERN.find(line) ?: continue
            return match.groupValues[1]
        }
        return null
    }

    companion object {
        private val LOG = Logger.getInstance(WasmDebugProcess::class.java)

        /**
         * Matches LLDB frame location: `at /path/to/file.c:42` or `at /path/to/file.c:42:5`
         */
        private val FRAME_LOCATION_PATTERN =
            """\bat\s+(.+?):(\d+)(?::\d+)?""".toRegex()

        /**
         * Matches function name in LLDB frame output like:
         * `frame #0: 0x... module`func_name`
         */
        private val FUNCTION_NAME_PATTERN =
            """`(\S+)\s+(?:at|[+])""".toRegex()
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
