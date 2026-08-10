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
import org.jetbrains.hexana.dwarf.DwarfInlineFrame
import org.jetbrains.hexana.shared.CdpMemoryVariable
import org.jetbrains.hexana.shared.CdpSourceLocationResolver
import org.jetbrains.hexana.shared.DwarfMemoryDecoder

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

    /**
     * All breakpoints that have been registered via CDP, tracked so
     * they can be re-registered with a scriptId when a wasm script
     * appears (URL-regex breakpoints may not work for wasm scripts).
     */
    private val allRegisteredBreakpoints = Collections.synchronizedList(
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
        LOG.warn("CDP: connecting to $wsUrl")
        cdp.connect(wsUrl)
        LOG.warn("CDP: connected")

        // Stop the debug session when the CDP WebSocket disconnects
        // (e.g. the debuggee process was killed externally).
        cdp.onDisconnect = {
            LOG.debug("CDP: WebSocket disconnected")
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
            LOG.debug("CDP: Debugger.resumed event received")
        }
        cdp.on("Debugger.scriptParsed") { params ->
            val scriptId = params["scriptId"] as? String
            val url = params["url"] as? String ?: return@on
            LOG.warn("CDP: Debugger.scriptParsed scriptId=$scriptId url=$url")
            if (url.startsWith("wasm://")) {
                wasmScriptSeen.set(true)
                LOG.warn("CDP: wasm script seen, retrying ${pendingWasmBreakpoints.size} pending breakpoints, re-registering ${allRegisteredBreakpoints.size} existing breakpoints")
                // Breakpoint re-registration is handled by the
                // instrumentation breakpoint pause (reason=instrumentation)
                // which fires BEFORE V8 executes the wasm script.
            }
        }

        // When the runtime finishes executing (e.g. Node.js main script
        // completes), it emits executionContextDestroyed but keeps the
        // process alive with "Waiting for the debugger to disconnect..."
        // until the CDP connection is closed. Detect this and
        // proactively disconnect so the debug session finishes cleanly.
        cdp.on("Runtime.executionContextDestroyed") { params ->
            LOG.warn("CDP: Runtime.executionContextDestroyed params=$params")
            // Disable the debugger domain first so Node.js doesn't
            // stay paused, then close the CDP connection which lets
            // the Node.js process exit naturally.
            // Delay briefly to allow any in-flight breakpoint re-
            // registration (from scriptParsed) to complete before
            // tearing down the connection.
            scope.launch {
                delay(2_000)
                try {
                    cdp.sendFireAndForget("Debugger.disable")
                    cdp.sendFireAndForget("Runtime.disable")
                } catch (_: Exception) {
                }
                try {
                    cdp.close()
                } catch (_: Exception) {
                }
            }
        }

        // Enable CDP domains
        cdp.send("Runtime.enable")
        cdp.send("Debugger.enable")

        // Set an instrumentation breakpoint so V8 pauses BEFORE
        // executing any newly compiled script — including wasm modules.
        // This guarantees we can set scriptId-based breakpoints before
        // any wasm code runs (unlike Debugger.pause which may arrive
        // after V8 has already entered the wasm function).
        try {
            cdp.send("Debugger.setInstrumentationBreakpoint", mapOf(
                "instrumentation" to "beforeScriptExecution",
            ))
            LOG.warn("CDP: instrumentation breakpoint set for beforeScriptExecution")
        } catch (e: Exception) {
            LOG.warn("CDP: failed to set instrumentation breakpoint (older V8?)", e)
        }

        // The target was started with --inspect-brk which pauses before
        // user code. We send Runtime.runIfWaitingForDebugger to let the
        // runtime proceed, but the Debugger domain still needs an explicit
        // Debugger.resume (handled in handleDebuggerPaused when the
        // "Break on start" pause event arrives).
        cdp.send("Runtime.runIfWaitingForDebugger")

        // Retry any breakpoints that were queued before the WebSocket
        // was connected (breakpoint handler can fire before connect()).
        LOG.warn("CDP: sourceLocationResolver=${sourceLocationResolver != null} dwarfVariableResolver=${dwarfVariableResolver != null}")
        if (pendingWasmBreakpoints.isNotEmpty()) {
            LOG.warn("CDP: retrying ${pendingWasmBreakpoints.size} breakpoints queued before connect")
            retryPendingWasmBreakpoints()
        }

        LOG.warn("CDP: initializeCdpSession complete")
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

        LOG.warn("CDP: Debugger.paused reason=$reason hitBreakpoints=$hitBreakpoints " +
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

        // V8 instrumentation breakpoint: pauses before executing a
        // newly compiled script. For wasm scripts, register breakpoints
        // with the concrete scriptId and then resume.
        if (reason == "instrumentation") {
            LOG.warn("CDP: instrumentation pause — checking for wasm breakpoints to register")
            if (wasmScriptSeen.get() && (allRegisteredBreakpoints.isNotEmpty() || pendingWasmBreakpoints.isNotEmpty())) {
                scope.launch {
                    try {
                        reRegisterBreakpointsForWasmScriptAndResume()
                    } catch (e: Exception) {
                        LOG.warn("CDP: failed during instrumentation re-registration", e)
                        try { cdp.send("Debugger.resume") } catch (_: Exception) {}
                    }
                }
            } else {
                // Not a wasm-related pause — just resume
                scope.launch {
                    try { cdp.send("Debugger.resume") } catch (_: Exception) {}
                }
            }
            return
        }

        if (reason == "Break on start" && hitBreakpoints.isNullOrEmpty()) {
            // Initial pause from --inspect-brk before user code runs.
            // Runtime.runIfWaitingForDebugger lets the runtime proceed
            // but does not resume the Debugger pause — we must always
            // send Debugger.resume here.
            //
            // Register all pending breakpoints BEFORE resuming so that
            // URL-regex breakpoints are in place when V8 compiles the
            // wasm module. Without this, a race between the resume and
            // breakpoint registration can cause breakpoints to be missed.
            LOG.warn("CDP: registering pending breakpoints before resuming from 'Break on start'")
            scope.launch {
                try {
                    registerPendingBreakpointsAndResume()
                } catch (e: Exception) {
                    LOG.warn("Failed to register breakpoints and resume from 'Break on start'", e)
                }
            }
            return
        }

        // Determine whether any of the hit breakpoints belong to us
        val hasOurBreakpoint = !hitBreakpoints.isNullOrEmpty() &&
            hitBreakpoints.any { cdpBreakpointToIde.containsKey(it) }
        val hasWasm = hasWasmFrames(callFrames)

        LOG.warn("CDP: hasOurBreakpoint=$hasOurBreakpoint hasWasmFrames=$hasWasm wasmScriptSeen=${wasmScriptSeen.get()}")

        // If no wasm frames are on the stack and none of our breakpoints
        // were hit, this is a pure JS/TS pause — auto-resume so we don't
        // interfere with other debuggers or block the target.
        // Only apply this guard after at least one wasm script has been
        // parsed; before that, pauses are part of the startup sequence
        // (loading JS that instantiates the wasm module) and resuming
        // them prematurely would skip past breakpoint registration.
        if (wasmScriptSeen.get() && !hasWasm && !hasOurBreakpoint) {
            LOG.warn("CDP: auto-resuming pure JS/TS pause (no wasm frames, no our breakpoints)")
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
        // buildStackFrame returns 1+ frames: synthetic inlined frames (innermost first)
        // followed by the physical frame. We flatten all physical frames into one list.
        val topFrameList = buildStackFrame(topFrame, 0)
        val topStackFrame = topFrameList.first()

        val allBuiltFrames = callFrames.flatMapIndexed { index, frame -> buildStackFrame(frame, index) }
        val filteredFrames = filterWasmWrapperFrames(allBuiltFrames)
        val effectiveTopFrame = filteredFrames.firstOrNull() ?: topStackFrame

        val suspendContext = CdpSuspendContext(
            topFrame = effectiveTopFrame,
            allFrames = filteredFrames,
        )

        if (reason == "other" && hitBreakpoints.isNullOrEmpty()) {
            // Pause from debugger statement or other non-breakpoint reason
            LOG.debug("CDP: positionReached for reason='other'")
            ApplicationManager.getApplication().invokeLater {
                session.positionReached(suspendContext)
            }
            return
        }

        if (!hitBreakpoints.isNullOrEmpty()) {
            val cdpBpId = hitBreakpoints[0]
            val ideBp = cdpBreakpointToIde[cdpBpId]
            LOG.warn("CDP: hitBreakpoint cdpId=$cdpBpId ideBp=${ideBp != null}")
            if (ideBp != null) {
                // The breakpoint may have been hit in a wasm-bindgen
                // wrapper frame rather than the real function. Use the
                // filtered frame list and pick the real function frame
                // as the top frame so variables come from the correct
                // scope. If the effective top frame's source position
                // doesn't match the breakpoint, override it.
                val bpPosition = ideBp.sourcePosition
                // When the effective top frame is a synthetic inlined frame, its source
                // position already comes from DWARF and is reliable — skip the override.
                // Only apply the correction when a physical CdpStackFrame is on top and
                // its resolved position differs from the breakpoint position.
                val correctedContext = if (
                    bpPosition != null &&
                    effectiveTopFrame is CdpStackFrame &&
                    effectiveTopFrame.sourcePosition != bpPosition
                ) {
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
                        sourceLocationResolver = sourceLocationResolver,
                    )
                    val remainingFrames = filteredFrames.drop(1)
                    CdpSuspendContext(
                        topFrame = correctedTopFrame,
                        allFrames = listOf(correctedTopFrame) + remainingFrames,
                    )
                } else {
                    suspendContext
                }
                LOG.warn("CDP: breakpointReached for ideBp at ${ideBp.sourcePosition}")
                ApplicationManager.getApplication().invokeLater {
                    session.breakpointReached(ideBp, null, correctedContext)
                }
                return
            }
        }

        LOG.warn("CDP: positionReached (fallthrough, reason=$reason hitBreakpoints=$hitBreakpoints)")
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
        if (frame.functionName.isBlank() || frame.functionName == "unknown") {
            LOG.debug("CDP: isWasmWrapperFrame fn='${frame.functionName}' -> true (blank/unknown)")
            return true
        }
        // Hide well-known wasm-bindgen wrapper patterns by name,
        // regardless of whether DWARF info is available.
        if (isWasmBindgenWrapperName(frame.functionName)) {
            LOG.debug("CDP: isWasmWrapperFrame fn='${frame.functionName}' -> true (wasm-bindgen wrapper name)")
            return true
        }
        val resolver = dwarfVariableResolver
        if (resolver != null) {
            // Hide frames that have no DWARF subprogram entry — these are
            // generated wrappers (wasm-bindgen closures, thunks, etc.)
            // whose locals are compiler-internal garbage.
            val hasSp = resolver.hasDwarfSubprogram(frame.functionName)
            if (!hasSp) {
                LOG.debug("CDP: isWasmWrapperFrame fn='${frame.functionName}' -> true (no DWARF subprogram)")
                return true
            }
        }
        // Also hide frames whose source position couldn't be resolved
        // (runtime/library functions whose source isn't available).
        if (frame.sourcePosition == null) {
            LOG.debug("CDP: isWasmWrapperFrame fn='${frame.functionName}' -> true (no source position)")
            return true
        }
        LOG.debug("CDP: isWasmWrapperFrame fn='${frame.functionName}' -> false")
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
     * Keeps all non-wasm frames, all synthetic inlined frames, and wasm frames
     * that have a DWARF subprogram entry (i.e. real user functions).
     *
     * [CdpInlinedStackFrame] instances are never filtered: they are already derived
     * from a physical frame that passed the wrapper check.
     */
    private fun filterWasmWrapperFrames(frames: List<XStackFrame>): List<XStackFrame> {
        return frames.filter { frame ->
            // Synthetic inlined frames are never wrapper frames — always keep them.
            if (frame is CdpInlinedStackFrame) return@filter true
            if (frame is CdpStackFrame) !isWasmWrapperFrame(frame) else true
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildStackFrame(frame: Map<String, Any?>, frameIndex: Int): List<XStackFrame> {
        val functionName = frame["functionName"] as? String ?: "<anonymous>"
        val location = frame["location"] as? Map<String, Any?>
        val scriptId = location?.get("scriptId") as? String
        val lineNumber = (location?.get("lineNumber") as? Number)?.toInt() ?: 0
        val columnNumber = (location?.get("columnNumber") as? Number)?.toInt() ?: 0
        val callFrameId = frame["callFrameId"] as? String ?: ""
        val scopeChain = frame["scopeChain"] as? List<Map<String, Any?>> ?: emptyList()

        val sourcePosition = resolveSourcePosition(scriptId, lineNumber, columnNumber)

        val physical = CdpStackFrame(
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
            sourceLocationResolver = sourceLocationResolver,
        )

        // Only look up inlined frames for wasm:// frames that have a DWARF resolver.
        val locResolver = sourceLocationResolver
        if (locResolver == null || scriptId == null) return listOf(physical)
        val scriptInfo = cdp.scripts[scriptId] ?: return listOf(physical)
        if (!scriptInfo.url.startsWith("wasm://")) return listOf(physical)

        val chain: List<DwarfInlineFrame> = locResolver.inlinedFramesAt(columnNumber.toULong())
        if (chain.isEmpty()) return listOf(physical)

        // Build synthetic frames for the inline chain.
        // chain[0] is the innermost (paused) frame; chain[n-1] is the outermost.
        // Frame presentation:
        //   - synthetic[0] (innermost): shows the actual pause source position
        //   - synthetic[i] (i > 0): shows the call site of the frame above it,
        //     i.e. chain[i-1].callFile / callLine
        //   - physical frame: keeps its own name, shows the call site of chain.last()
        val syntheticFrames = mutableListOf<XStackFrame>()
        for (i in chain.indices) {
            val inlined = chain[i]
            val frameSourcePosition: XSourcePosition? = if (i == 0) {
                // Innermost: paused at the actual DWARF source position (same as physical)
                sourcePosition
            } else {
                // Each subsequent frame shows where the frame above it was called
                val caller = chain[i - 1]
                resolveInlinedSourcePosition(caller.callFile, caller.callLine)
            }
            val name = (inlined.name ?: functionName) + " (inlined)"
            syntheticFrames.add(CdpInlinedStackFrame(frameSourcePosition, name, physical))
        }

        // The physical frame's display position becomes the outermost call site.
        // We rebuild it with the outermost call site so it shows where the inlined
        // chain begins in the real subprogram, keeping the physical frame's own name.
        val outerCallSite = resolveInlinedSourcePosition(chain.last().callFile, chain.last().callLine)
        val physicalWithCallSite = CdpStackFrame(
            sourcePosition = outerCallSite ?: sourcePosition,
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
            sourceLocationResolver = sourceLocationResolver,
        )

        return syntheticFrames + physicalWithCallSite
    }

    /**
     * Resolves a DWARF call-site file path and 1-based line number to an
     * [XSourcePosition], or `null` when either argument is absent or the
     * file cannot be found on disk.
     */
    private fun resolveInlinedSourcePosition(callFile: String?, callLine: Int?): XSourcePosition? {
        if (callFile == null || callLine == null) return null
        val vf = findSourceFileByName(callFile) ?: return null
        // DWARF call_line is 1-based; IDE positions are 0-based.
        return XDebuggerUtil.getInstance().createPosition(vf, callLine - 1)
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

        // file:// URLs — handle both Unix (file:///path) and
        // Windows (file:///C:/path) forms.
        if (url.startsWith("file://")) {
            var path = url.removePrefix("file://")
            // Strip the leading slash before a Windows drive letter
            // (e.g. "/C:/Users/..." → "C:/Users/...").
            if (path.length >= 3 && path[0] == '/' && path[2] == ':') {
                path = path.substring(1)
            }
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
     * Finds a source file in the project by matching the DWARF-reported
     * file path against files in the local file system.
     *
     * Resolution strategy (first match wins):
     * (a) Absolute path: try the DWARF path directly on disk.
     * (b) Project-relative: resolve against the project base path.
     * (c) Content-root relative: try each content root.
     * (d) Breakpoint cache: check already-open breakpoint files whose
     *     path ends with the DWARF path suffix.
     * (e) Recursive content-root search with longest-path-suffix-wins:
     *     among all files whose name matches the basename, prefer the
     *     one whose project-relative path shares the longest suffix
     *     with the DWARF path.
     */
    private fun findSourceFileByName(dwarfPath: String): VirtualFile? {
        val normalized = dwarfPath.replace('\\', '/')

        // (a) Try as an absolute path first
        val absolute = LocalFileSystem.getInstance().findFileByPath(normalized)
        if (absolute != null) return absolute

        // (b) Try resolving relative to the project base path
        val projectBasePath = session.project.basePath
        if (projectBasePath != null) {
            val resolved = LocalFileSystem.getInstance().findFileByPath("$projectBasePath/$normalized")
            if (resolved != null) return resolved
        }

        // (c) Try resolving relative to each content root
        for (root in com.intellij.openapi.roots.ProjectRootManager.getInstance(session.project).contentRoots) {
            val resolved = root.findFileByRelativePath(normalized)
            if (resolved != null) return resolved
        }

        val fileName = normalized.substringAfterLast('/')

        // (d) Check already-registered breakpoint files with path-suffix match
        for (bp in cdpBreakpointToIde.values) {
            val bpFile = bp.sourcePosition?.file ?: continue
            if (bpFile.name == fileName && pathSuffixMatches(bpFile.path.replace('\\', '/'), normalized)) {
                return bpFile
            }
        }

        // (e) Search content roots recursively, collect all name matches,
        //     then pick the one with the longest matching path suffix.
        val candidates = mutableListOf<VirtualFile>()
        for (root in com.intellij.openapi.roots.ProjectRootManager.getInstance(session.project).contentRoots) {
            findFileRecursively(root, fileName, candidates)
        }
        return pickBestSuffixMatch(candidates, normalized)
    }

    /**
     * Collects all files under [dir] whose name equals [fileName]
     * into [results]. Used by [findSourceFileByName] to gather
     * candidates for longest-path-suffix selection.
     */
    private fun findFileRecursively(dir: VirtualFile, fileName: String, results: MutableList<VirtualFile>) {
        for (child in dir.children) {
            if (child.isDirectory) {
                findFileRecursively(child, fileName, results)
            } else if (child.name == fileName) {
                results.add(child)
            }
        }
    }

    /**
     * Returns `true` when [candidatePath] ends with [dwarfSuffix]
     * at a path-segment boundary (i.e. the character before the
     * suffix is a `/` or the suffix is the full path).
     */
    private fun pathSuffixMatches(candidatePath: String, dwarfSuffix: String): Boolean {
        if (!candidatePath.endsWith(dwarfSuffix)) return false
        val prefixLen = candidatePath.length - dwarfSuffix.length
        return prefixLen == 0 || candidatePath[prefixLen - 1] == '/'
    }

    /**
     * Picks the best candidate from [files] by finding the longest
     * suffix of [dwarfPath] (split at `/` boundaries) that matches
     * each candidate's normalized path. Returns the candidate with
     * the most matching path segments, or the first file if no
     * suffix at all matches (bare-name fallback).
     */
    private fun pickBestSuffixMatch(files: List<VirtualFile>, dwarfPath: String): VirtualFile? {
        if (files.isEmpty()) return null
        if (files.size == 1) return files[0]

        val segments = dwarfPath.split('/')
        var bestFile: VirtualFile? = null
        var bestMatchLen = -1

        for (f in files) {
            val fPath = f.path.replace('\\', '/')
            // Find the longest suffix of dwarfPath that matches the end of fPath
            var matchLen = 0
            for (startIdx in 0 until segments.size) {
                val suffix = segments.drop(startIdx).joinToString("/")
                if (pathSuffixMatches(fPath, suffix)) {
                    matchLen = segments.size - startIdx
                    break
                }
            }
            if (matchLen > bestMatchLen) {
                bestMatchLen = matchLen
                bestFile = f
            }
        }
        return bestFile ?: files[0]
    }

    // ---- Breakpoint management ----

    /**
     * Translates a raw condition expression for use at [wasmOffset].
     *
     * When [dwarfVariableResolver] is available, looks up the DWARF
     * subprogram covering [wasmOffset], builds the local-index-to-name
     * map, inverts it to a name-to-$varN map, and substitutes source
     * variable names in [condition] with V8 internal names. When no
     * resolver is available, or the offset matches no subprogram, the
     * condition is returned unchanged.
     *
     * Returns `null` when [condition] is `null`.
     */
    private fun resolveBreakpointCondition(condition: String?, wasmOffset: Int): String? {
        condition ?: return null
        val resolver = dwarfVariableResolver ?: return condition
        val localToName = resolver.buildWasmLocalToNameMapByOffset(wasmOffset)
        if (localToName.isEmpty()) return condition
        val nameToVar = localToName.entries.associate { (index, name) -> name to "\$var$index" }
        return translateCondition(condition, nameToVar)
    }

    internal fun registerBreakpointInCdp(breakpoint: XLineBreakpoint<WasmBreakpointProperties>) {
        val sourcePosition = breakpoint.sourcePosition ?: run {
            LOG.debug("CDP: registerBreakpoint skipped — no source position")
            return
        }
        val file = sourcePosition.file
        val line = sourcePosition.line
        LOG.warn("CDP: registerBreakpoint file=${file.path} line=$line")

        // If the CDP WebSocket isn't connected yet (breakpoint handler
        // fires during session setup before connect() completes), queue
        // the breakpoint for later registration.
        if (!cdp.connected) {
            LOG.warn("CDP: breakpoint queued as pending (WebSocket not connected yet)")
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
            LOG.debug("CDP: registerBreakpoint skipped — no source position")
            return
        }
        val file = sourcePosition.file
        val line = sourcePosition.line
        LOG.warn("CDP: registerBreakpoint (suspend) file=${file.path} line=$line")
        registerBreakpointInCdpImpl(breakpoint, file, line)
    }

    private suspend fun registerBreakpointInCdpImpl(
        breakpoint: XLineBreakpoint<WasmBreakpointProperties>,
        file: VirtualFile,
        line: Int,
    ) {
        try {
            val condition = breakpoint.conditionExpression?.expression?.takeUnless { it.isBlank() }

            // If a wasm script is already known, use scriptId-based
            // breakpoints. Otherwise, use URL-regex breakpoints that
            // V8 will apply automatically when the wasm script appears
            // — this avoids the race between breakpoint registration
            // and wasm code execution.
            val wasmScriptId = findWasmScriptId()
            val results = if (wasmScriptId != null) {
                trySetBreakpointsViaDwarf(file, line, condition)
            } else {
                trySetBreakpointsViaDwarfByUrl(file, line, condition)
            }

            LOG.warn("CDP: DWARF breakpoint results count=${results.size} (byUrl=${wasmScriptId == null})")
            if (results.isNotEmpty()) {
                val cdpIds = ideBreakpointToCdp.getOrPut(breakpoint) { mutableListOf() }
                for (result in results) {
                    val breakpointId = result["breakpointId"] as? String ?: continue
                    LOG.warn("CDP: registered DWARF breakpoint cdpId=$breakpointId for ${file.name}:$line")
                    cdpBreakpointToIde[breakpointId] = breakpoint
                    cdpIds.add(breakpointId)
                }
                // Track for re-registration when a wasm script appears
                if (breakpoint !in allRegisteredBreakpoints) {
                    allRegisteredBreakpoints.add(breakpoint)
                }
                session.updateBreakpointPresentation(
                    breakpoint, null, null
                )
            } else {
                // Fall back to URL-based breakpoint for non-wasm scripts
                val urlResult = trySetBreakpointByUrl(file, line, condition)
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

    /**
     * Registers all pending breakpoints and then sends `Debugger.resume`.
     * Called from the "Break on start" handler to ensure breakpoints are
     * set before execution proceeds.
     *
     * V8 does not apply `urlRegex`-based breakpoints to wasm scripts,
     * so we must wait for the wasm `scriptParsed` event and re-register
     * breakpoints with a concrete scriptId before resuming. The JS that
     * instantiates the wasm module runs after resume, so we:
     * 1. Register URL-regex breakpoints (as a best-effort fallback)
     * 2. Resume from the "Break on start" pause
     * 3. Wait for the wasm scriptParsed event (with timeout)
     * 4. Pause V8, re-register breakpoints with scriptId, then resume
     */
    private suspend fun registerPendingBreakpointsAndResume() {
        val pending = drainPendingBreakpoints()
        for (bp in pending) {
            registerBreakpointInCdpSuspend(bp)
        }
        LOG.warn("CDP: ${pending.size} pending breakpoints registered, sending Debugger.resume")
        cdp.send("Debugger.resume")
        LOG.warn("CDP: Debugger.resume sent for 'Break on start'")
        // The scriptParsed handler will pause V8 when the wasm script
        // appears, re-register breakpoints with the concrete scriptId,
        // and resume — no additional work needed here.
    }

    private fun drainPendingBreakpoints(): List<XLineBreakpoint<WasmBreakpointProperties>> {
        val pending = mutableListOf<XLineBreakpoint<WasmBreakpointProperties>>()
        synchronized(pendingWasmBreakpoints) {
            pending.addAll(pendingWasmBreakpoints)
            pendingWasmBreakpoints.clear()
        }
        return pending
    }

    /**
     * Re-registers all known breakpoints using scriptId-based CDP calls
     * when a wasm script appears, then resumes execution. Called from
     * the instrumentation breakpoint handler where V8 is already paused
     * BEFORE executing the wasm script.
     */
    private suspend fun reRegisterBreakpointsForWasmScriptAndResume() {
        val pending = drainPendingBreakpoints()
        val existing: List<XLineBreakpoint<WasmBreakpointProperties>>
        synchronized(allRegisteredBreakpoints) {
            existing = allRegisteredBreakpoints.toList()
        }
        val toRegister = (pending + existing).distinct()
        if (toRegister.isEmpty()) {
            cdp.send("Debugger.resume")
            return
        }
        LOG.warn("CDP: re-registering ${toRegister.size} breakpoints for wasm script (pending=${pending.size}, existing=${existing.size})")
        for (bp in toRegister) {
            registerBreakpointInCdpSuspend(bp)
        }
        LOG.warn("CDP: ${toRegister.size} breakpoints re-registered with scriptId, resuming")
        cdp.send("Debugger.resume")
    }

    private fun retryPendingWasmBreakpoints() {
        val pending = drainPendingBreakpoints()
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
        condition: String? = null,
    ): List<Map<String, Any?>> {
        val locResolver = sourceLocationResolver ?: return emptyList()

        val wasmOffsets = locResolver.sourceToAllWasmOffsets(file.name, line)
        LOG.warn("CDP: trySetBreakpointsViaDwarfByUrl file=${file.name} line=$line offsets=$wasmOffsets condition=${condition != null}")
        if (wasmOffsets.isEmpty()) return emptyList()

        val results = mutableListOf<Map<String, Any?>>()
        for (offset in wasmOffsets) {
            try {
                val resolvedCondition = resolveBreakpointCondition(condition, offset.toInt())
                val params = buildMap<String, Any?> {
                    put("urlRegex", "^wasm://")
                    put("lineNumber", 0)
                    put("columnNumber", offset.toInt())
                    if (resolvedCondition != null) put("condition", resolvedCondition)
                }
                val result = cdp.send("Debugger.setBreakpointByUrl", params)
                LOG.warn("CDP: setBreakpointByUrl offset=$offset result=$result")
                if (result["breakpointId"] != null) {
                    results.add(result)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to set DWARF breakpoint by URL at offset $offset: ${e.message}")
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
     *
     * Passes the full VirtualFile path to the resolver so that
     * path-suffix matching can disambiguate files with the same name
     * in multi-crate Rust projects (e.g. several `lib.rs` files).
     */
    private suspend fun trySetBreakpointsViaDwarf(
        file: VirtualFile,
        line: Int,
        condition: String? = null,
    ): List<Map<String, Any?>> {
        val locResolver = sourceLocationResolver ?: return emptyList()

        val wasmOffsets = locResolver.sourceToAllWasmOffsets(file.path, line)
        LOG.warn("CDP: trySetBreakpointsViaDwarf file=${file.path} line=$line offsets=$wasmOffsets condition=${condition != null}")
        if (wasmOffsets.isEmpty()) return emptyList()

        // Find the wasm script in CDP — V8 reports wasm modules with wasm:// URLs
        val wasmScriptId = findWasmScriptId() ?: return emptyList()
        LOG.warn("CDP: trySetBreakpointsViaDwarf wasmScriptId=$wasmScriptId")

        val results = mutableListOf<Map<String, Any?>>()
        for (offset in wasmOffsets) {
            try {
                val resolvedCondition = resolveBreakpointCondition(condition, offset.toInt())
                val params = buildMap<String, Any?> {
                    put("location", mapOf(
                        "scriptId" to wasmScriptId,
                        "lineNumber" to 0,
                        "columnNumber" to offset.toInt(),
                    ))
                    if (resolvedCondition != null) put("condition", resolvedCondition)
                }
                val result = cdp.send("Debugger.setBreakpoint", params)
                LOG.warn("CDP: setBreakpoint scriptId=$wasmScriptId offset=$offset result=$result")
                if (result["breakpointId"] != null) {
                    results.add(result)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to set DWARF breakpoint at offset $offset: ${e.message}")
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
        condition: String? = null,
    ): Map<String, Any?>? {
        // Convert IDE VFS path to a file:// URL that V8 can match.
        // On Unix, paths start with "/"; on Windows, with a drive
        // letter like "C:\". V8 expects "file:///path" on both OSes.
        val fileUrl = if (file.path.startsWith("/")) {
            "file://${file.path}"
        } else {
            // Windows path — needs three slashes: file:///C:/...
            "file:///" + file.path.replace('\\', '/')
        }

        val params = buildMap<String, Any?> {
            put("lineNumber", line)
            put("url", fileUrl)
            put("columnNumber", 0)
            // For non-wasm file-URL breakpoints, no offset is known so
            // the condition is passed raw (no DWARF name translation).
            if (condition != null) put("condition", condition)
        }
        return cdp.send("Debugger.setBreakpointByUrl", params)
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
        allRegisteredBreakpoints.remove(breakpoint)
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

// ---- Condition translation ----

/**
 * Translates DWARF source variable names in [condition] to their V8
 * internal `$varN` equivalents using [nameToVar].
 *
 * Each key in [nameToVar] is a source-level name (e.g. `x`); the
 * value is the corresponding V8 name (e.g. `$var0`). Substitution
 * uses word boundaries so that `x` does not match `max` or `px`.
 *
 * The function is pure and has no side effects, making it directly
 * unit-testable.
 */
internal fun translateCondition(condition: String, nameToVar: Map<String, String>): String {
    if (nameToVar.isEmpty()) return condition
    return nameToVar.entries.fold(condition) { expr, (sourceName, varName) ->
        // escapeReplacement is required because varName contains '$' (e.g. "$var0"),
        // which would otherwise be interpreted as a regex group reference.
        expr.replace("""\b${Regex.escape(sourceName)}\b""".toRegex(), Regex.escapeReplacement(varName))
    }
}

// ---- CDP-specific suspend context and stack frames ----

internal class CdpSuspendContext(
    private val topFrame: XStackFrame,
    private val allFrames: List<XStackFrame>,
) : XSuspendContext() {

    private val executionStack = CdpExecutionStack(topFrame, allFrames)

    override fun getActiveExecutionStack(): XExecutionStack = executionStack
    override fun getExecutionStacks(): Array<XExecutionStack> = arrayOf(executionStack)
}

internal class CdpExecutionStack(
    private val topFrame: XStackFrame,
    private val allFrames: List<XStackFrame>,
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


/**
 * A synthetic stack frame that represents one level in a DWARF inlined call chain.
 *
 * Synthetic frames are display-only: variable scopes and evaluation remain attached
 * to the physical [physicalFrame] so that locals are visible when this frame is
 * selected in the IDE. Stepping semantics are unaffected — the debugger always steps
 * through the physical wasm frame.
 *
 * @param sourcePosition the source location shown for this inlined call site
 * @param displayName the inlined function name, suffixed with " (inlined)"
 * @param physicalFrame the real CDP frame whose scopes/evaluator this frame delegates to
 */
internal class CdpInlinedStackFrame(
    private val sourcePosition: XSourcePosition?,
    private val displayName: String,
    private val physicalFrame: CdpStackFrame,
) : XStackFrame() {

    override fun getSourcePosition(): XSourcePosition? = sourcePosition

    override fun getEvaluator(): XDebuggerEvaluator = physicalFrame.getEvaluator()

    override fun customizePresentation(component: com.intellij.ui.ColoredTextContainer) {
        val line = sourcePosition?.let { ":${it.line + 1}" } ?: ""
        component.append("$displayName$line", com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    override fun computeChildren(node: XCompositeNode) = physicalFrame.computeChildren(node)
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
    private val sourceLocationResolver: CdpSourceLocationResolver? = null,
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
        LOG.debug("CDP: computeChildren fn=$functionName scriptId=$scriptId " +
            "hasDwarfMatch=$hasDwarfMatch localToName.size=${localToName.size} " +
            "scopeChain.size=${scopeChain.size} " +
            "scopeTypes=${scopeChain.map { it["type"] }}")
        scope.launch {
            try {
                val list = XValueChildrenList()
                // Collect wasm local values for frame-base resolution (keyed by $varN index).
                // Maps V8 local index -> numeric value (used to resolve the shadow stack pointer).
                val wasmLocalValues = mutableMapOf<Long, Long>()
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

                        // Collect numeric values from wasmvalue locals for frame-base resolution.
                        // These are needed to compute the shadow-stack-pointer address.
                        if (subtype == "wasmvalue") {
                            val match = WASM_VAR_PATTERN.matchEntire(name)
                            if (match != null) {
                                val idx = match.groupValues[1].toLongOrNull()
                                if (idx != null) {
                                    val innerVal = resolveWasmValue(value, cdp)?.toLongOrNull()
                                    if (innerVal != null) wasmLocalValues[idx] = innerVal
                                }
                            }
                        }

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

                // Append DWARF linear-memory variables if the source location resolver is available.
                appendMemoryVariables(list, wasmLocalValues)

                node.addChildren(list, true)
            } catch (e: Exception) {
                node.setErrorMessage("Failed to retrieve variables: ${e.message}")
            }
        }
    }

    /**
     * Reads DWARF linear-memory variables for this frame and appends them to [list].
     *
     * For each memory variable:
     * 1. Compute the linear-memory address:
     *    - Frame-relative: base = value of wasm local [frameBaseLocalIndex] from [wasmLocalValues],
     *      address = base + fbregOffset.
     *    - Absolute: address = absoluteAddress.
     * 2. Read [byteSize] bytes from `memories[0]` using `Debugger.evaluateOnCallFrame` on a
     *    JS expression that slices the memory ArrayBuffer. V8 exposes the wasm instance's linear
     *    memory as `memories[0]` in the wasm frame's evaluate context (available since Node 12).
     *    We use `new Uint8Array(memories[0].buffer, addr, size)` and convert to a hex string;
     *    this round-trips as a string property on the returned object, avoiding binary encoding.
     * 3. Decode the bytes using [DwarfMemoryDecoder] and present the result.
     *
     * Failures are logged once and silently omitted (never throw).
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun appendMemoryVariables(
        list: XValueChildrenList,
        wasmLocalValues: Map<Long, Long>,
    ) {
        val locResolver = sourceLocationResolver ?: return
        val memVars = locResolver.memoryVariablesAt(columnNumber.toULong())
        if (memVars.isEmpty()) return

        var loggedMemoryReadFailure = false
        for (v in memVars) {
            try {
                val address: Long = if (v.absoluteAddress != null) {
                    v.absoluteAddress!!
                } else {
                    // Frame-relative: frameBaseLocalIndex holds local index (negative = global, not yet supported).
                    val fbLocalIdx = v.frameBaseLocalIndex ?: continue
                    if (fbLocalIdx < 0) {
                        // Wasm-global frame base — not resolvable from wasmLocalValues; skip.
                        continue
                    }
                    val frameBase = wasmLocalValues[fbLocalIdx] ?: continue
                    frameBase + (v.fbregOffset ?: 0L)
                }

                val byteSize = v.byteSize
                if (byteSize <= 0) continue

                // Read bytes from linear memory via Debugger.evaluateOnCallFrame.
                // The expression builds a Uint8Array view and converts it to a hex string.
                // V8 exposes wasm memories[0] in the evaluate context of a wasm frame.
                val hexExpr = """(function(){
                    var mem = memories[0].buffer;
                    var arr = new Uint8Array(mem, $address, $byteSize);
                    var hex = '';
                    for(var i=0;i<arr.length;i++){var b=arr[i].toString(16);hex+=(b.length<2?'0'+b:b);}
                    return hex;
                })()""".trimIndent()

                val evalResult = try {
                    cdp.send("Debugger.evaluateOnCallFrame", mapOf(
                        "callFrameId" to callFrameId,
                        "expression" to hexExpr,
                        "returnByValue" to true,
                    ))
                } catch (e: Exception) {
                    if (!loggedMemoryReadFailure) {
                        LOG.warn("CDP: linear-memory read failed for '${v.name}': ${e.message}")
                        loggedMemoryReadFailure = true
                    }
                    continue
                }

                val resultObj = evalResult["result"] as? Map<String, Any?> ?: continue
                val hexStr = resultObj["value"] as? String ?: continue

                // Decode hex string to byte array
                if (hexStr.length != byteSize * 2) continue
                val bytes = ByteArray(byteSize) { i ->
                    hexStr.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }

                val rendered = DwarfMemoryDecoder.decode(bytes, 0, v.type) ?: continue
                list.add(v.name, CdpMemoryValue(v.name, v.type, rendered, bytes, cdp, scope, callFrameId, address))
            } catch (e: Exception) {
                LOG.warn("CDP: failed to render memory variable '${v.name}': ${e.message}")
            }
        }
    }

    companion object {
        private val LOG = Logger.getInstance(CdpStackFrame::class.java)
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
        val resolvedExpression = translateCondition(expression, nameToVar)

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

/**
 * Presents a DWARF linear-memory variable decoded from wasm linear memory.
 *
 * Scalars (base types, enums, pointers) are shown inline. Composite types
 * (structs, arrays) are shown with expandable children built from the already-decoded
 * [DwarfMemoryDecoder.RenderedValue] tree — no additional CDP round-trips for the
 * initial slab. Pointer dereferences are lazy (one level on expansion, not implemented
 * here: the expand slot shows "dereference not available in CDP mode").
 */
@Suppress("UNCHECKED_CAST")
internal class CdpMemoryValue(
    private val name: String,
    private val type: org.jetbrains.hexana.dwarf.DwarfTypeInfo,
    private val rendered: DwarfMemoryDecoder.RenderedValue,
    @Suppress("unused") private val rawBytes: ByteArray,
    @Suppress("unused") private val cdp: CdpClient,
    private val scope: CoroutineScope,
    @Suppress("unused") private val callFrameId: String,
    @Suppress("unused") private val baseAddress: Long,
) : XNamedValue(name) {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val typeName = when (type) {
            is org.jetbrains.hexana.dwarf.DwarfTypeInfo.Base -> type.name
            is org.jetbrains.hexana.dwarf.DwarfTypeInfo.Struct -> type.name ?: "struct"
            is org.jetbrains.hexana.dwarf.DwarfTypeInfo.Array -> "array"
            is org.jetbrains.hexana.dwarf.DwarfTypeInfo.Enumeration -> type.name ?: "enum"
            is org.jetbrains.hexana.dwarf.DwarfTypeInfo.Pointer -> "pointer"
            is org.jetbrains.hexana.dwarf.DwarfTypeInfo.Unknown -> "?"
        }
        val hasChildren = rendered.children.isNotEmpty()
        node.setPresentation(null, typeName, rendered.summary, hasChildren)
    }

    override fun computeChildren(node: XCompositeNode) {
        if (rendered.children.isEmpty()) {
            node.addChildren(XValueChildrenList.EMPTY, true)
            return
        }
        scope.launch {
            val list = XValueChildrenList()
            for (child in rendered.children) {
                list.add(child.name, CdpMemoryLeafValue(child.name, child.value))
            }
            if (rendered.truncated) {
                list.add("<truncated>", CdpMemoryLeafValue("<truncated>",
                    DwarfMemoryDecoder.RenderedValue("... (${DwarfMemoryDecoder.MAX_ARRAY_CHILDREN} of ${rendered.summary})")))
            }
            node.addChildren(list, true)
        }
    }
}

/** A non-expandable leaf value for struct members or array elements. */
internal class CdpMemoryLeafValue(
    name: String,
    private val rendered: DwarfMemoryDecoder.RenderedValue,
) : XNamedValue(name) {
    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(null, null, rendered.summary, rendered.children.isNotEmpty())
    }

    override fun computeChildren(node: XCompositeNode) {
        if (rendered.children.isEmpty()) {
            node.addChildren(XValueChildrenList.EMPTY, true)
            return
        }
        val list = XValueChildrenList()
        for (child in rendered.children) {
            list.add(child.name, CdpMemoryLeafValue(child.name, child.value))
        }
        node.addChildren(list, true)
    }
}
