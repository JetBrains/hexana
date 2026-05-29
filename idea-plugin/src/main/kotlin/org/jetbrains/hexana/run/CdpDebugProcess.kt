package org.jetbrains.hexana.run

import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.*
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.*
import com.sun.net.httpserver.HttpServer
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.*
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An [XDebugProcess] that communicates with Node.js or Chrome via the
 * Chrome DevTools Protocol (CDP) over WebSocket.
 *
 * Supports breakpoints, stepping (over/into/out), pause/resume,
 * call stack inspection, scope variable inspection, and DWARF-based
 * source position resolution when debug info is available.
 */
internal class CdpDebugProcess(
    session: XDebugSession,
    private val console: ExecutionConsole,
    private val processHandler: ProcessHandler,
    private val wsUrl: String,
    internal val engine: WasmEngine,
    internal var dwarfVariableResolver: WasmDwarfVariableResolver? = null,
    internal var sourceLocationResolver: CdpSourceLocationResolver? = null,
    private val httpServer: HttpServer? = null,
    private val tempUserDataDir: File? = null,
) : XDebugProcess(session) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cdp = CdpClient(scope)

    private val breakpointHandler = CdpBreakpointHandler(this)
    private val stopping = AtomicBoolean(false)
    /** Set to `true` once at least one `wasm://` script has been parsed by V8. */
    private val wasmScriptSeen = AtomicBoolean(false)

    /** Maps CDP breakpoint IDs to the IDE breakpoint objects. */
    internal val cdpBreakpointToIde = ConcurrentHashMap<String, XLineBreakpoint<WasmBreakpointProperties>>()
    internal val ideBreakpointToCdp = ConcurrentHashMap<XLineBreakpoint<WasmBreakpointProperties>, MutableList<String>>()

    /**
     * Breakpoints that need DWARF-based wasm offset mapping but were
     * registered before any wasm script was parsed by V8. They are
     * retried when a `Debugger.scriptParsed` event with a `wasm://`
     * URL arrives.
     */
    private val pendingWasmBreakpoints = Collections.synchronizedList(
        mutableListOf<XLineBreakpoint<WasmBreakpointProperties>>()
    )

    override fun getEditorsProvider(): XDebuggerEditorsProvider = CdpEditorsProvider()

    private val sessionProcessHandler = object : ProcessHandler() {
        private var started = false

        override fun startNotify() {
            if (started) return // Ignore double-start
            started = true
            super.startNotify()
        }

        override fun destroyProcessImpl() {
            processHandler.destroyProcess()
            notifyProcessTerminated(0)
        }

        override fun detachProcessImpl() {
            processHandler.detachProcess()
            notifyProcessDetached()
        }

        override fun detachIsDefault(): Boolean = processHandler.detachIsDefault()
        override fun getProcessInput() = processHandler.processInput

        /** Expose protected method for the process listener below. */
        fun fireProcessTerminated(exitCode: Int) = notifyProcessTerminated(exitCode)
    }.also { wrapper ->
        // Forward termination from the real process handler so the
        // debug session UI reflects the actual process state.
        processHandler.addProcessListener(object : ProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) {
                if (!wrapper.isProcessTerminated && !wrapper.isProcessTerminating) {
                    wrapper.fireProcessTerminated(event.exitCode)
                }
                // Stop the debug session when the debuggee exits
                ApplicationManager.getApplication().invokeLater {
                    if (!session.isStopped) {
                        session.stop()
                    }
                }
            }
        })
    }

    override fun doGetProcessHandler(): ProcessHandler = sessionProcessHandler

    override fun createConsole(): ExecutionConsole = console

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(breakpointHandler)

    override fun sessionInitialized() {
        super.sessionInitialized()

        scope.launch {
            try {
                initializeCdpSession()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Failed to initialize CDP session", e)
                session.reportError("Failed to connect to debug target: ${e.message}")
                session.stop()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun initializeCdpSession() {
        LOG.info("CDP: connecting to $wsUrl")
        cdp.connect(wsUrl)
        LOG.info("CDP: connected")

        // Stop the debug session when the CDP WebSocket disconnects
        // (e.g. the debuggee process was killed externally).
        cdp.onDisconnect = {
            LOG.info("CDP: WebSocket disconnected")
            ApplicationManager.getApplication().invokeLater {
                if (!session.isStopped) {
                    session.stop()
                }
            }
        }

        // Register event handlers before enabling domains
        cdp.on("Debugger.paused") { params ->
            handleDebuggerPaused(params)
        }
        cdp.on("Debugger.resumed") { _ ->
            LOG.info("CDP: Debugger.resumed event received")
        }
        cdp.on("Debugger.scriptParsed") { params ->
            val scriptId = params["scriptId"] as? String
            val url = params["url"] as? String ?: return@on
            LOG.info("CDP: Debugger.scriptParsed scriptId=$scriptId url=$url")
            if (url.startsWith("wasm://")) {
                wasmScriptSeen.set(true)
                LOG.info("CDP: wasm script seen, retrying ${pendingWasmBreakpoints.size} pending breakpoints")
                // A wasm module was compiled — retry any pending breakpoints
                retryPendingWasmBreakpoints()
            }
        }

        // Enable CDP domains
        LOG.info("CDP: sending Runtime.enable")
        cdp.send("Runtime.enable")
        LOG.info("CDP: sending Debugger.enable")
        cdp.send("Debugger.enable")

        // The target was started with --inspect-brk which pauses before
        // user code. We send Runtime.runIfWaitingForDebugger to let the
        // runtime proceed, but the Debugger domain still needs an explicit
        // Debugger.resume (handled in handleDebuggerPaused when the
        // "Break on start" pause event arrives).
        LOG.info("CDP: sending Runtime.runIfWaitingForDebugger")
        cdp.send("Runtime.runIfWaitingForDebugger")

        // Retry any breakpoints that were queued before the WebSocket
        // was connected (breakpoint handler can fire before connect()).
        if (pendingWasmBreakpoints.isNotEmpty()) {
            LOG.info("CDP: retrying ${pendingWasmBreakpoints.size} breakpoints queued before connect")
            retryPendingWasmBreakpoints()
        }

        LOG.info("CDP: initializeCdpSession complete")
    }

    /**
     * Returns `true` when at least one call frame in the pause event
     * belongs to a wasm script (`wasm://` URL). When no wasm frames
     * are present and no Hexana breakpoints were hit, the pause is a
     * pure JS/TS event that we should not interfere with.
     */
    @Suppress("UNCHECKED_CAST")
    private fun hasWasmFrames(callFrames: List<Map<String, Any?>>): Boolean {
        for (frame in callFrames) {
            val location = frame["location"] as? Map<String, Any?> ?: continue
            val scriptId = location["scriptId"] as? String ?: continue
            val scriptInfo = cdp.scripts[scriptId] ?: continue
            if (scriptInfo.url.startsWith("wasm://")) return true
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleDebuggerPaused(params: Map<String, Any?>) {
        val callFrames = params["callFrames"] as? List<Map<String, Any?>>
        val hitBreakpoints = params["hitBreakpoints"] as? List<String>
        val reason = params["reason"] as? String
        val data = params["data"]

        LOG.info("CDP: Debugger.paused reason=$reason hitBreakpoints=$hitBreakpoints " +
            "callFrames.size=${callFrames?.size} data=$data " +
            "wasmScriptSeen=${wasmScriptSeen.get()} " +
            "registeredCdpBreakpoints=${cdpBreakpointToIde.keys} " +
            "pendingWasmBreakpoints=${pendingWasmBreakpoints.size}")

        if (callFrames == null) {
            LOG.warn("Debugger.paused: callFrames is null or not a list")
            return
        }
        if (callFrames.isEmpty()) {
            LOG.warn("Debugger.paused: callFrames is empty")
            return
        }

        // Log top frames for diagnostics
        for ((i, frame) in callFrames.withIndex()) {
            if (i >= 5) break // only log top 5 frames
            val loc = frame["location"] as? Map<String, Any?>
            val sid = loc?.get("scriptId") as? String
            val line = loc?.get("lineNumber")
            val col = loc?.get("columnNumber")
            val fn = frame["functionName"] as? String
            val scriptUrl = sid?.let { cdp.scripts[it]?.url }
            LOG.info("CDP:   frame[$i] fn=$fn scriptId=$sid url=$scriptUrl line=$line col=$col")
        }

        if (reason == "Break on start" && hitBreakpoints.isNullOrEmpty()) {
            // Initial pause from --inspect-brk before user code runs.
            // Runtime.runIfWaitingForDebugger lets the runtime proceed
            // but does not resume the Debugger pause — we must always
            // send Debugger.resume here.
            //
            // Always resume immediately. Pending wasm breakpoints will
            // be registered when the wasm scriptParsed event fires
            // (after JS runs and instantiates the wasm module). V8
            // processes Debugger.setBreakpoint synchronously before
            // entering wasm code, so breakpoints set in the
            // scriptParsed handler will be active in time.
            LOG.info("CDP: auto-resuming from 'Break on start' (pendingWasmBreakpoints=${pendingWasmBreakpoints.size})")
            scope.launch {
                try {
                    cdp.send("Debugger.resume")
                    LOG.info("CDP: Debugger.resume sent for 'Break on start'")
                } catch (e: Exception) {
                    LOG.warn("Failed to auto-resume from 'Break on start'", e)
                }
            }
            return
        }

        // Determine whether any of the hit breakpoints belong to us
        val hasOurBreakpoint = !hitBreakpoints.isNullOrEmpty() &&
            hitBreakpoints.any { cdpBreakpointToIde.containsKey(it) }
        val hasWasm = hasWasmFrames(callFrames)

        LOG.info("CDP: hasOurBreakpoint=$hasOurBreakpoint hasWasmFrames=$hasWasm wasmScriptSeen=${wasmScriptSeen.get()}")

        // If no wasm frames are on the stack and none of our breakpoints
        // were hit, this is a pure JS/TS pause — auto-resume so we don't
        // interfere with other debuggers or block the target.
        // Only apply this guard after at least one wasm script has been
        // parsed; before that, pauses are part of the startup sequence
        // (loading JS that instantiates the wasm module) and resuming
        // them prematurely would skip past breakpoint registration.
        if (wasmScriptSeen.get() && !hasWasm && !hasOurBreakpoint) {
            LOG.info("CDP: auto-resuming pure JS/TS pause (no wasm frames, no our breakpoints)")
            scope.launch {
                try {
                    cdp.send("Debugger.resume")
                } catch (e: Exception) {
                    LOG.warn("Failed to auto-resume from pure JS/TS pause", e)
                }
            }
            return
        }

        val topFrame = callFrames[0]
        val topStackFrame = buildStackFrame(topFrame, 0)

        val allBuiltFrames = callFrames.mapIndexed { index, frame -> buildStackFrame(frame, index) }
        val filteredFrames = filterWasmWrapperFrames(allBuiltFrames)
        val effectiveTopFrame = filteredFrames.firstOrNull() ?: topStackFrame

        val suspendContext = CdpSuspendContext(
            topFrame = effectiveTopFrame,
            allFrames = filteredFrames,
        )

        if (reason == "other" && hitBreakpoints.isNullOrEmpty()) {
            // Pause from debugger statement or other non-breakpoint reason
            LOG.info("CDP: positionReached for reason='other' (debugger statement or similar)")
            ApplicationManager.getApplication().invokeLater {
                session.positionReached(suspendContext)
            }
            return
        }

        if (!hitBreakpoints.isNullOrEmpty()) {
            val cdpBpId = hitBreakpoints[0]
            val ideBp = cdpBreakpointToIde[cdpBpId]
            LOG.info("CDP: hitBreakpoint cdpId=$cdpBpId ideBp=${ideBp != null}")
            if (ideBp != null) {
                // The breakpoint may have been hit in a wasm-bindgen
                // wrapper frame rather than the real function. Use the
                // filtered frame list and pick the real function frame
                // as the top frame so variables come from the correct
                // scope. If the effective top frame's source position
                // doesn't match the breakpoint, override it.
                val bpPosition = ideBp.sourcePosition
                val correctedContext = if (bpPosition != null && effectiveTopFrame.sourcePosition != bpPosition) {
                    val correctedTopFrame = CdpStackFrame(
                        sourcePosition = bpPosition,
                        functionName = effectiveTopFrame.functionName,
                        callFrameId = effectiveTopFrame.callFrameId,
                        scopeChain = effectiveTopFrame.scopeChain,
                        cdp = cdp,
                        scope = scope,
                        frameIndex = 0,
                        scriptId = effectiveTopFrame.scriptId,
                        lineNumber = effectiveTopFrame.lineNumber,
                        columnNumber = effectiveTopFrame.columnNumber,
                        dwarfVariableResolver = dwarfVariableResolver,
                    )
                    val remainingFrames = filteredFrames.drop(1)
                    CdpSuspendContext(
                        topFrame = correctedTopFrame,
                        allFrames = listOf(correctedTopFrame) + remainingFrames,
                    )
                } else {
                    suspendContext
                }
                LOG.info("CDP: breakpointReached for ideBp at ${ideBp.sourcePosition}")
                ApplicationManager.getApplication().invokeLater {
                    session.breakpointReached(ideBp, null, correctedContext)
                }
                return
            }
        }

        LOG.info("CDP: positionReached (fallthrough, reason=$reason hitBreakpoints=$hitBreakpoints)")
        ApplicationManager.getApplication().invokeLater {
            session.positionReached(suspendContext)
        }
    }

    /**
     * Returns `true` when the frame belongs to a wasm-bindgen or
     * similar generated/runtime function that should be hidden from
     * the user. This includes:
     * - Functions with no DWARF subprogram entry (generated wrappers)
     * - Functions whose DWARF source location doesn't resolve to a
     *   file the user can navigate to (runtime/library internals like
     *   `wasm_bindgen::__rt::maybe_catch_unwind`)
     */
    private fun isWasmWrapperFrame(frame: CdpStackFrame): Boolean {
        // Only hide wasm frames (scriptId present, on a wasm:// script)
        val sid = frame.scriptId ?: return false
        val scriptInfo = cdp.scripts[sid] ?: return false
        if (!scriptInfo.url.startsWith("wasm://")) return false
        // Hide frames with blank or "unknown" function names — these
        // are V8 internal frames with no useful debug information.
        if (frame.functionName.isBlank() || frame.functionName == "unknown") return true
        // Hide well-known wasm-bindgen wrapper patterns by name,
        // regardless of whether DWARF info is available.
        if (isWasmBindgenWrapperName(frame.functionName)) return true
        val resolver = dwarfVariableResolver
        if (resolver != null) {
            // Hide frames that have no DWARF subprogram entry — these are
            // generated wrappers (wasm-bindgen closures, thunks, etc.)
            // whose locals are compiler-internal garbage.
            if (!resolver.hasDwarfSubprogram(frame.functionName)) return true
        }
        // Also hide frames whose source position couldn't be resolved
        // (runtime/library functions whose source isn't available).
        if (frame.sourcePosition == null) return true
        return false
    }

    /**
     * Detects wasm-bindgen generated wrapper function names by
     * matching known mangled name patterns. These wrappers are
     * always hidden from the call stack regardless of DWARF info.
     */
    private fun isWasmBindgenWrapperName(name: String): Boolean {
        val n = name.removePrefix("$")
        // wasm-bindgen generated closure wrappers contain
        // `__wasm_bindgen_generated_` in their mangled name
        if ("__wasm_bindgen_generated_" in n) return true
        // wasm-bindgen runtime internals like `maybe_catch_unwind`
        if ("wasm_bindgen" in n && ("maybe_catch_unwind" in n || "__rt" in n)) return true
        return false
    }

    /**
     * Filters out wasm-bindgen wrapper frames from the call stack.
     * Keeps all non-wasm frames and wasm frames that have a DWARF
     * subprogram entry (i.e. real user functions).
     */
    private fun filterWasmWrapperFrames(frames: List<CdpStackFrame>): List<CdpStackFrame> {
        return frames.filter { !isWasmWrapperFrame(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildStackFrame(frame: Map<String, Any?>, frameIndex: Int): CdpStackFrame {
        val functionName = frame["functionName"] as? String ?: "<anonymous>"
        val location = frame["location"] as? Map<String, Any?>
        val scriptId = location?.get("scriptId") as? String
        val lineNumber = (location?.get("lineNumber") as? Number)?.toInt() ?: 0
        val columnNumber = (location?.get("columnNumber") as? Number)?.toInt() ?: 0
        val callFrameId = frame["callFrameId"] as? String ?: ""
        val scopeChain = frame["scopeChain"] as? List<Map<String, Any?>> ?: emptyList()

        val sourcePosition = resolveSourcePosition(scriptId, lineNumber, columnNumber)

        return CdpStackFrame(
            sourcePosition = sourcePosition,
            functionName = functionName,
            callFrameId = callFrameId,
            scopeChain = scopeChain,
            cdp = cdp,
            scope = scope,
            frameIndex = frameIndex,
            scriptId = scriptId,
            lineNumber = lineNumber,
            columnNumber = columnNumber,
            dwarfVariableResolver = dwarfVariableResolver,
        )
    }

    private fun resolveSourcePosition(scriptId: String?, lineNumber: Int, columnNumber: Int): XSourcePosition? {
        if (scriptId == null) return null
        val scriptInfo = cdp.scripts[scriptId] ?: return null
        val url = scriptInfo.url

        // For wasm:// scripts, use DWARF line info to map byte offset → source
        if (url.startsWith("wasm://")) {
            val locResolver = sourceLocationResolver
            if (locResolver != null) {
                // In wasm scripts, columnNumber is the byte offset within the module
                val sourceLoc = locResolver.wasmOffsetToSource(columnNumber.toULong())
                if (sourceLoc != null) {
                    val sourceFile = findSourceFileByName(sourceLoc.file)
                    if (sourceFile != null) {
                        // DWARF lines are 1-based, IDE lines are 0-based
                        return XDebuggerUtil.getInstance().createPosition(sourceFile, sourceLoc.line - 1)
                    }
                }
            }
            return null
        }

        // Try direct file resolution from URL
        val file = resolveFileFromUrl(url) ?: return null
        return XDebuggerUtil.getInstance().createPosition(file, lineNumber)
    }

    private fun resolveFileFromUrl(url: String): VirtualFile? {
        if (url.isBlank()) return null

        // file:// URLs
        if (url.startsWith("file://")) {
            val path = url.removePrefix("file://")
            return LocalFileSystem.getInstance().findFileByPath(path)
        }

        // wasm:// URLs (Chrome uses these for Wasm modules)
        if (url.startsWith("wasm://")) {
            return null // Wasm bytecode — use DWARF mapping instead
        }

        // Try as a local path
        val file = File(url)
        if (file.exists()) {
            return LocalFileSystem.getInstance().findFileByPath(file.absolutePath)
        }

        return null
    }

    /**
     * Finds a source file in the project by matching the file name
     * from DWARF debug info against files in the local file system.
     */
    private fun findSourceFileByName(dwarfPath: String): VirtualFile? {
        // Try as an absolute path first
        val absolute = LocalFileSystem.getInstance().findFileByPath(dwarfPath)
        if (absolute != null) return absolute

        // Try resolving relative to the project base path
        val projectBasePath = session.project.basePath
        if (projectBasePath != null) {
            val resolved = LocalFileSystem.getInstance().findFileByPath("$projectBasePath/$dwarfPath")
            if (resolved != null) return resolved
        }

        // Try resolving relative to each content root
        val fileName = dwarfPath.substringAfterLast('/')
        for (root in com.intellij.openapi.roots.ProjectRootManager.getInstance(session.project).contentRoots) {
            val resolved = root.findFileByRelativePath(dwarfPath)
            if (resolved != null) return resolved
        }

        // Fall back to matching by file name among the source files
        // already known from registered breakpoints — the user placed
        // a breakpoint on a file whose name matches the DWARF path.
        for (bp in cdpBreakpointToIde.values) {
            val bpFile = bp.sourcePosition?.file ?: continue
            if (bpFile.name == fileName && bpFile.path.endsWith(dwarfPath)) {
                return bpFile
            }
        }

        // Search content roots recursively by file name
        for (root in com.intellij.openapi.roots.ProjectRootManager.getInstance(session.project).contentRoots) {
            val found = findFileRecursively(root, fileName, dwarfPath)
            if (found != null) return found
        }

        return null
    }

    private fun findFileRecursively(dir: VirtualFile, fileName: String, dwarfPath: String): VirtualFile? {
        for (child in dir.children) {
            if (child.isDirectory) {
                val found = findFileRecursively(child, fileName, dwarfPath)
                if (found != null) return found
            } else if (child.name == fileName && child.path.endsWith(dwarfPath)) {
                return child
            }
        }
        return null
    }

    // ---- Breakpoint management ----

    internal fun registerBreakpointInCdp(breakpoint: XLineBreakpoint<WasmBreakpointProperties>) {
        val sourcePosition = breakpoint.sourcePosition ?: run {
            LOG.info("CDP: registerBreakpoint skipped — no source position")
            return
        }
        val file = sourcePosition.file
        val line = sourcePosition.line
        LOG.info("CDP: registerBreakpoint file=${file.path} line=$line")

        // If the CDP WebSocket isn't connected yet (breakpoint handler
        // fires during session setup before connect() completes), queue
        // the breakpoint for later registration.
        if (!cdp.connected) {
            LOG.info("CDP: breakpoint queued as pending (WebSocket not connected yet)")
            pendingWasmBreakpoints.add(breakpoint)
            return
        }

        scope.launch {
            registerBreakpointInCdpImpl(breakpoint, file, line)
        }
    }

    /**
     * Suspend version of [registerBreakpointInCdp] used by
     * [retryPendingWasmBreakpoints] so it can await completion
     * before signalling the deferred resume.
     */
    private suspend fun registerBreakpointInCdpSuspend(breakpoint: XLineBreakpoint<WasmBreakpointProperties>) {
        val sourcePosition = breakpoint.sourcePosition ?: run {
            LOG.info("CDP: registerBreakpoint skipped — no source position")
            return
        }
        val file = sourcePosition.file
        val line = sourcePosition.line
        LOG.info("CDP: registerBreakpoint (suspend) file=${file.path} line=$line")
        registerBreakpointInCdpImpl(breakpoint, file, line)
    }

    private suspend fun registerBreakpointInCdpImpl(
        breakpoint: XLineBreakpoint<WasmBreakpointProperties>,
        file: VirtualFile,
        line: Int,
    ) {
        try {
            // If a wasm script is already known, use scriptId-based
            // breakpoints. Otherwise, use URL-regex breakpoints that
            // V8 will apply automatically when the wasm script appears
            // — this avoids the race between breakpoint registration
            // and wasm code execution.
            val wasmScriptId = findWasmScriptId()
            val results = if (wasmScriptId != null) {
                trySetBreakpointsViaDwarf(file, line)
            } else {
                trySetBreakpointsViaDwarfByUrl(file, line)
            }

            LOG.info("CDP: DWARF breakpoint results count=${results.size} (byUrl=${wasmScriptId == null})")
            if (results.isNotEmpty()) {
                val cdpIds = ideBreakpointToCdp.getOrPut(breakpoint) { mutableListOf() }
                for (result in results) {
                    val breakpointId = result["breakpointId"] as? String ?: continue
                    LOG.info("CDP: registered DWARF breakpoint cdpId=$breakpointId for ${file.name}:$line")
                    cdpBreakpointToIde[breakpointId] = breakpoint
                    cdpIds.add(breakpointId)
                }
                session.updateBreakpointPresentation(
                    breakpoint, null, null
                )
            } else {
                // Fall back to URL-based breakpoint for non-wasm scripts
                val urlResult = trySetBreakpointByUrl(file, line)
                val breakpointId = urlResult?.get("breakpointId") as? String
                if (breakpointId != null) {
                    cdpBreakpointToIde[breakpointId] = breakpoint
                    ideBreakpointToCdp.getOrPut(breakpoint) { mutableListOf() }.add(breakpointId)
                    session.updateBreakpointPresentation(
                        breakpoint, null, null
                    )
                } else {
                    session.updateBreakpointPresentation(
                        breakpoint,
                        com.intellij.icons.AllIcons.Debugger.Db_invalid_breakpoint,
                        "No DWARF mapping found for this source location",
                    )
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed to set CDP breakpoint at ${file.path}:$line", e)
            session.updateBreakpointPresentation(
                breakpoint,
                com.intellij.icons.AllIcons.Debugger.Db_invalid_breakpoint,
                "Failed to set breakpoint: ${e.message}",
            )
        }
    }

    private fun retryPendingWasmBreakpoints() {
        val pending = mutableListOf<XLineBreakpoint<WasmBreakpointProperties>>()
        synchronized(pendingWasmBreakpoints) {
            pending.addAll(pendingWasmBreakpoints)
            pendingWasmBreakpoints.clear()
        }
        if (pending.isEmpty()) return
        scope.launch {
            for (bp in pending) {
                registerBreakpointInCdpSuspend(bp)
            }
        }
    }

    /**
     * Sets wasm breakpoints using `Debugger.setBreakpointByUrl` with a
     * URL regex that matches any `wasm://` script. Unlike
     * `Debugger.setBreakpoint` (which requires a specific scriptId),
     * this variant tells V8 to apply the breakpoint to any matching
     * script — including scripts that haven't been parsed yet. This
     * eliminates the race between breakpoint registration and wasm
     * code execution.
     */
    private suspend fun trySetBreakpointsViaDwarfByUrl(
        file: VirtualFile,
        line: Int,
    ): List<Map<String, Any?>> {
        val locResolver = sourceLocationResolver ?: return emptyList()

        val wasmOffsets = locResolver.sourceToAllWasmOffsets(file.name, line)
        if (wasmOffsets.isEmpty()) return emptyList()

        val results = mutableListOf<Map<String, Any?>>()
        for (offset in wasmOffsets) {
            try {
                val result = cdp.send("Debugger.setBreakpointByUrl", mapOf(
                    "urlRegex" to "^wasm://",
                    "lineNumber" to 0,
                    "columnNumber" to offset.toInt(),
                ))
                if (result["breakpointId"] != null) {
                    results.add(result)
                }
            } catch (e: Exception) {
                LOG.debug("Failed to set DWARF breakpoint by URL at offset $offset: ${e.message}")
            }
        }
        return results
    }

    /**
     * Tries to set breakpoints using DWARF source-to-offset mapping.
     * For source files (C, Rust, etc.), this translates the source
     * file + line into Wasm byte offsets and sets breakpoints on the
     * wasm script using `Debugger.setBreakpoint` with the byte
     * offset as columnNumber.
     *
     * Returns breakpoints for ALL matching offsets. When wasm-bindgen
     * or similar tools generate wrapper functions that reference the
     * same source line, multiple offsets exist — setting breakpoints
     * at all of them ensures the debugger pauses in the correct
     * function (the real function body, not just the wrapper).
     */
    private suspend fun trySetBreakpointsViaDwarf(
        file: VirtualFile,
        line: Int,
    ): List<Map<String, Any?>> {
        val locResolver = sourceLocationResolver ?: return emptyList()

        val wasmOffsets = locResolver.sourceToAllWasmOffsets(file.name, line)
        if (wasmOffsets.isEmpty()) return emptyList()

        // Find the wasm script in CDP — V8 reports wasm modules with wasm:// URLs
        val wasmScriptId = findWasmScriptId() ?: return emptyList()

        val results = mutableListOf<Map<String, Any?>>()
        for (offset in wasmOffsets) {
            try {
                val result = cdp.send("Debugger.setBreakpoint", mapOf(
                    "location" to mapOf(
                        "scriptId" to wasmScriptId,
                        "lineNumber" to 0,
                        "columnNumber" to offset.toInt(),
                    ),
                ))
                if (result["breakpointId"] != null) {
                    results.add(result)
                }
            } catch (e: Exception) {
                LOG.debug("Failed to set DWARF breakpoint at offset $offset: ${e.message}")
            }
        }
        return results
    }

    /**
     * Falls back to setting a breakpoint by URL for non-wasm scripts
     * (e.g. JavaScript files loaded by Node.js or the browser).
     */
    private suspend fun trySetBreakpointByUrl(
        file: VirtualFile,
        line: Int,
    ): Map<String, Any?>? {
        // Convert IDE VFS URL to a file:// URL that V8 can match
        val fileUrl = if (file.path.startsWith("/")) {
            "file://${file.path}"
        } else {
            file.url
        }

        return cdp.send("Debugger.setBreakpointByUrl", mapOf(
            "lineNumber" to line,
            "url" to fileUrl,
            "columnNumber" to 0,
        ))
    }

    /**
     * Finds the scriptId of the first wasm:// script reported by V8.
     * V8 emits `Debugger.scriptParsed` events with `wasm://` URLs
     * when a WebAssembly module is compiled and instantiated.
     */
    private fun findWasmScriptId(): String? {
        return cdp.scripts.values.firstOrNull { it.url.startsWith("wasm://") }?.scriptId
    }

    internal fun unregisterBreakpointInCdp(breakpoint: XLineBreakpoint<WasmBreakpointProperties>) {
        val cdpIds = ideBreakpointToCdp.remove(breakpoint) ?: return
        for (cdpId in cdpIds) {
            cdpBreakpointToIde.remove(cdpId)
        }

        scope.launch {
            for (cdpId in cdpIds) {
                try {
                    cdp.send("Debugger.removeBreakpoint", mapOf("breakpointId" to cdpId))
                } catch (e: Exception) {
                    LOG.debug("Failed to remove CDP breakpoint $cdpId: ${e.message}")
                }
            }
        }
    }

    // ---- Execution control ----

    override fun resume(context: XSuspendContext?) {
        scope.launch {
            try {
                cdp.send("Debugger.resume")
            } catch (e: Exception) {
                LOG.warn("Failed to resume", e)
            }
        }
    }

    override fun startPausing() {
        scope.launch {
            try {
                cdp.send("Debugger.pause")
            } catch (e: Exception) {
                LOG.warn("Failed to pause", e)
            }
        }
    }

    override fun startStepOver(context: XSuspendContext?) {
        scope.launch {
            try {
                cdp.send("Debugger.stepOver")
            } catch (e: Exception) {
                LOG.warn("Failed to step over", e)
            }
        }
    }

    override fun startStepInto(context: XSuspendContext?) {
        scope.launch {
            try {
                cdp.send("Debugger.stepInto")
            } catch (e: Exception) {
                LOG.warn("Failed to step into", e)
            }
        }
    }

    override fun startStepOut(context: XSuspendContext?) {
        scope.launch {
            try {
                cdp.send("Debugger.stepOut")
            } catch (e: Exception) {
                LOG.warn("Failed to step out", e)
            }
        }
    }

    override fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        runBlocking {
            try {
                cdp.close()
            } catch (_: Exception) {
            }
        }
        httpServer?.stop(0)
        if (tempUserDataDir != null) {
            try {
                tempUserDataDir.deleteRecursively()
            } catch (e: Exception) {
                LOG.debug("Failed to delete temp Chrome user-data dir: ${e.message}")
            }
        }
        if (!processHandler.isProcessTerminated) {
            processHandler.destroyProcess()
        }
        // Ensure the wrapper process handler is marked as terminated
        // so the debug session UI finishes cleanly.
        if (!sessionProcessHandler.isProcessTerminated && !sessionProcessHandler.isProcessTerminating) {
            sessionProcessHandler.fireProcessTerminated(0)
        }
        scope.cancel()
    }

    companion object {
        private val LOG = Logger.getInstance(CdpDebugProcess::class.java)
    }
}

// ---- CDP-specific suspend context and stack frames ----

internal class CdpSuspendContext(
    private val topFrame: CdpStackFrame,
    private val allFrames: List<CdpStackFrame>,
) : XSuspendContext() {

    private val executionStack = CdpExecutionStack(topFrame, allFrames)

    override fun getActiveExecutionStack(): XExecutionStack = executionStack
    override fun getExecutionStacks(): Array<XExecutionStack> = arrayOf(executionStack)
}

internal class CdpExecutionStack(
    private val topFrame: CdpStackFrame,
    private val allFrames: List<CdpStackFrame>,
) : XExecutionStack("Wasm Thread") {

    override fun getTopFrame(): XStackFrame = topFrame

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer?) {
        if (container == null) return
        val result = if (firstFrameIndex < allFrames.size) {
            allFrames.subList(firstFrameIndex, allFrames.size)
        } else {
            emptyList()
        }
        container.addStackFrames(result, true)
    }
}

@Suppress("UNCHECKED_CAST")
internal class CdpStackFrame(
    private val sourcePosition: XSourcePosition?,
    internal val functionName: String,
    internal val callFrameId: String,
    internal val scopeChain: List<Map<String, Any?>>,
    private val cdp: CdpClient,
    private val scope: CoroutineScope,
    private val frameIndex: Int,
    internal val scriptId: String?,
    internal val lineNumber: Int,
    internal val columnNumber: Int = 0,
    private val dwarfVariableResolver: WasmDwarfVariableResolver? = null,
) : XStackFrame() {

    /** For wasm frames, columnNumber is the byte offset within the module. */
    private val wasmByteOffset: Int?
        get() = if (scriptId != null) columnNumber else null

    override fun getSourcePosition(): XSourcePosition? = sourcePosition

    override fun getEvaluator(): XDebuggerEvaluator = CdpDebuggerEvaluator(
        cdp, callFrameId, scope,
        dwarfVariableResolver?.buildWasmLocalToNameMap(functionName, wasmByteOffset) ?: emptyMap(),
    )

    override fun customizePresentation(component: com.intellij.ui.ColoredTextContainer) {
        val name = functionName.ifBlank { sourcePosition?.file?.name ?: "unknown" }
        val line = sourcePosition?.let { ":${it.line + 1}" } ?: ""
        component.append("$name$line", com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    override fun computeChildren(node: XCompositeNode) {
        val localToName = dwarfVariableResolver?.buildWasmLocalToNameMap(functionName, wasmByteOffset) ?: emptyMap()
        // Check whether this function has a DWARF subprogram entry.
        // Wrapper functions (e.g. wasm-bindgen generated) have no
        // DWARF subprogram — their locals are compiler-internal
        // (stack pointers, etc.) and show garbage values.
        val hasDwarfMatch = dwarfVariableResolver?.hasDwarfSubprogram(functionName) ?: false
        scope.launch {
            try {
                val list = XValueChildrenList()
                var localIndex = 0L
                for (scopeEntry in scopeChain) {
                    val type = scopeEntry["type"] as? String ?: continue
                    // Skip global and module scopes — too noisy and not
                    // useful for source-level debugging (module scope
                    // contains instance, module, functions, globals, etc.)
                    if (type == "global" || type == "module") continue

                    val objectRef = scopeEntry["object"] as? Map<String, Any?> ?: continue
                    val objectId = objectRef["objectId"] as? String ?: continue

                    val result = cdp.send("Runtime.getProperties", mapOf(
                        "objectId" to objectId,
                        "ownProperties" to true,
                    ))
                    val properties = result["result"] as? List<Map<String, Any?>> ?: continue
                    for (prop in properties) {
                        val name = prop["name"] as? String ?: continue
                        val value = prop["value"] as? Map<String, Any?> ?: continue
                        val subtype = value["subtype"] as? String

                        // Resolve display name: try $varN pattern first,
                        // then fall back to positional index for non-$varN
                        // names (e.g. wasm name section names like "arg0_1")
                        val displayName = resolveVariableDisplayName(name, localToName, localIndex)

                        // Hide variables in two cases:
                        // 1. Function has no DWARF subprogram (wrapper/
                        //    generated function) — all its locals are
                        //    compiler-internal garbage (stack pointers etc.)
                        // 2. Function has DWARF info and localToName is
                        //    non-empty but this specific local has no
                        //    mapping — it's a compiler-internal spill slot
                        if (dwarfVariableResolver != null && !hasDwarfMatch && subtype == "wasmvalue") {
                            localIndex++
                            continue
                        }
                        if (localToName.isNotEmpty() && displayName == name) {
                            localIndex++
                            continue
                        }

                        if (subtype == "wasmvalue") {
                            // Flatten wasmvalue objects: extract the inner
                            // type and value and present them directly
                            val wasmType = value["description"] as? String
                            val innerValue = resolveWasmValue(value, cdp)
                            list.add(displayName, CdpWasmLocalValue(displayName, wasmType, innerValue, value, cdp, scope))
                        } else {
                            list.add(displayName, CdpValue(displayName, value, cdp, scope))
                        }
                        localIndex++
                    }
                }
                node.addChildren(list, true)
            } catch (e: Exception) {
                node.setErrorMessage("Failed to retrieve variables: ${e.message}")
            }
        }
    }

    companion object {
        /** Pattern matching V8's internal wasm local variable names like `$var0`, `$var1`. */
        private val WASM_VAR_PATTERN = """\${'$'}var(\d+)""".toRegex()

        /**
         * Resolves a V8 internal variable name (e.g. `$var0`) to its
         * DWARF source-level name using the local-index-to-name map.
         *
         * V8 may report wasm locals as `$varN` (numeric index) or
         * using names from the wasm name section (e.g. `arg0_1`).
         * For `$varN` names, the index is extracted directly. For
         * other names, the positional index is used as a fallback.
         *
         * Returns the original name if no mapping is found.
         */
        internal fun resolveVariableDisplayName(
            name: String,
            localToName: Map<Long, String>,
            positionalIndex: Long = -1,
        ): String {
            if (localToName.isEmpty()) return name
            // Try extracting index from $varN pattern
            val match = WASM_VAR_PATTERN.matchEntire(name)
            if (match != null) {
                val index = match.groupValues[1].toLongOrNull() ?: return name
                return localToName[index] ?: name
            }
            // For non-$varN names (wasm name section), use positional index
            if (positionalIndex >= 0) {
                return localToName[positionalIndex] ?: name
            }
            return name
        }

        /**
         * Resolves the inner value of a wasmvalue object by fetching
         * its properties and extracting the `value` field.
         */
        @Suppress("UNCHECKED_CAST")
        private suspend fun resolveWasmValue(
            valueDescriptor: Map<String, Any?>,
            cdp: CdpClient,
        ): String? {
            val objectId = valueDescriptor["objectId"] as? String ?: return null
            return try {
                val result = cdp.send("Runtime.getProperties", mapOf(
                    "objectId" to objectId,
                    "ownProperties" to true,
                ))
                val properties = result["result"] as? List<Map<String, Any?>> ?: return null
                for (prop in properties) {
                    if (prop["name"] == "value") {
                        val innerValue = prop["value"] as? Map<String, Any?> ?: continue
                        val v = innerValue["value"]
                        if (v != null) {
                            // Format numeric values: drop trailing .0 for integers
                            val str = v.toString()
                            return if (v is Number && str.endsWith(".0")) {
                                str.removeSuffix(".0")
                            } else {
                                str
                            }
                        }
                    }
                }
                null
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Presents a wasm local/parameter value with its type and resolved
 * inner value, avoiding the need to expand the wasmvalue object.
 */
@Suppress("UNCHECKED_CAST")
internal class CdpWasmLocalValue(
    private val name: String,
    private val wasmType: String?,
    private val resolvedValue: String?,
    private val valueDescriptor: Map<String, Any?>,
    private val cdp: CdpClient,
    private val scope: CoroutineScope,
) : XNamedValue(name) {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val displayType = wasmType ?: "wasmvalue"
        val displayValue = resolvedValue ?: valueDescriptor["description"]?.toString() ?: "?"
        node.setPresentation(null, displayType, displayValue, false)
    }
}

@Suppress("UNCHECKED_CAST")
internal class CdpValue(
    private val name: String,
    private val valueDescriptor: Map<String, Any?>,
    private val cdp: CdpClient,
    private val scope: CoroutineScope,
) : XNamedValue(name) {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val type = valueDescriptor["type"] as? String ?: "unknown"
        val subtype = valueDescriptor["subtype"] as? String
        val description = valueDescriptor["description"] as? String
        val value = valueDescriptor["value"]

        val displayType = subtype ?: type
        val displayValue = when {
            value != null -> value.toString()
            description != null -> description
            else -> type
        }

        val objectId = valueDescriptor["objectId"] as? String
        val hasChildren = objectId != null && (type == "object" || type == "function")

        node.setPresentation(null, displayType, displayValue, hasChildren)
    }

    override fun computeChildren(node: XCompositeNode) {
        val objectId = valueDescriptor["objectId"] as? String
        if (objectId == null) {
            node.addChildren(XValueChildrenList.EMPTY, true)
            return
        }

        scope.launch {
            try {
                val result = cdp.send("Runtime.getProperties", mapOf(
                    "objectId" to objectId,
                    "ownProperties" to true,
                ))
                val properties = result["result"] as? List<Map<String, Any?>> ?: emptyList()
                val list = XValueChildrenList()
                for (prop in properties) {
                    val propName = prop["name"] as? String ?: continue
                    val propValue = prop["value"] as? Map<String, Any?> ?: continue
                    list.add(propName, CdpValue(propName, propValue, cdp, scope))
                }
                node.addChildren(list, true)
            } catch (e: Exception) {
                node.setErrorMessage("Failed to retrieve properties: ${e.message}")
            }
        }
    }
}

private class CdpEditorsProvider : XDebuggerEditorsProvider() {
    override fun getFileType(): FileType = PlainTextFileType.INSTANCE

    override fun createDocument(
        project: Project,
        expression: XExpression,
        sourcePosition: XSourcePosition?,
        mode: EvaluationMode,
    ): Document {
        return EditorFactory.getInstance().createDocument(expression.expression)
    }
}

internal class CdpDebuggerEvaluator(
    private val cdp: CdpClient,
    private val callFrameId: String,
    private val scope: CoroutineScope,
    private val localToName: Map<Long, String> = emptyMap(),
) : XDebuggerEvaluator() {

    /** Reverse map: DWARF source name → V8 internal `$varN` name. */
    private val nameToVar: Map<String, String> by lazy {
        localToName.entries.associate { (index, name) -> name to "\$var$index" }
    }

    @Suppress("UNCHECKED_CAST")
    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        // Translate source-level variable names to V8 internal names
        // so that the user can evaluate expressions using DWARF names.
        val resolvedExpression = if (nameToVar.isNotEmpty()) {
            nameToVar.entries.fold(expression) { expr, (sourceName, varName) ->
                expr.replace("""\b${Regex.escape(sourceName)}\b""".toRegex(), varName)
            }
        } else {
            expression
        }

        scope.launch {
            try {
                val result = cdp.send("Debugger.evaluateOnCallFrame", mapOf(
                    "callFrameId" to callFrameId,
                    "expression" to resolvedExpression,
                    "returnByValue" to false,
                ))
                val resultObj = result["result"] as? Map<String, Any?>
                if (resultObj != null) {
                    val exceptionDetails = result["exceptionDetails"] as? Map<String, Any?>
                    if (exceptionDetails != null) {
                        val text = (exceptionDetails["text"] as? String) ?: "Evaluation error"
                        callback.errorOccurred(text)
                    } else {
                        callback.evaluated(CdpValue("result", resultObj, cdp, scope))
                    }
                } else {
                    callback.errorOccurred("No result")
                }
            } catch (e: Exception) {
                callback.errorOccurred(e.message ?: "Evaluation failed")
            }
        }
    }
}
