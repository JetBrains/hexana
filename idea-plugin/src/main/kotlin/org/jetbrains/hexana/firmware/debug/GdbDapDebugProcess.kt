package org.jetbrains.hexana.firmware.debug

import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.ui.XDebugTabLayouter
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.impl.XDebuggerUtilImpl
import kotlinx.serialization.json.*
import org.jetbrains.hexana.breakpoints.BinaryExecutionPosition
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.breakpoints.BinaryIdentityProvider
import org.jetbrains.hexana.breakpoints.BreakpointMaterializationCoordinator
import org.jetbrains.hexana.breakpoints.GdbElfMaterializer
import org.jetbrains.hexana.breakpoints.MaterializationSession
import org.jetbrains.hexana.shared.BinaryIdentity
import org.jetbrains.hexana.firmware.run.StopAtEntry
import org.jetbrains.hexana.run.ConsoleSessionKind
import org.jetbrains.hexana.run.DebuggerConsoleTransport
import org.jetbrains.hexana.run.InterceptAction
import org.jetbrains.hexana.run.buildDebuggerConsolePanel
import java.util.UUID

/**
 * Debug process for ELF firmware running under a GDB-stub emulator, using
 * [GdbDapClient] to drive `gdb -i=dap`.
 *
 * Transport selection: this process is used when [org.jetbrains.hexana.firmware.EmulatorDetection.detectGdb]
 * returns a valid tool (GDB >= 14 with Python). The LLDB path ([FirmwareDebugProcess]) remains
 * the fallback.
 *
 * ### Session flow
 * 1. Spawn `<gdb> -i=dap` (KillableProcessHandler, registerNoOrphan).
 * 2. `initialize` → `attach {"program": <elfPath>, "target": "localhost:<port>"}`.
 * 3. Wait for `initialized` event.
 * 4. Apply breakpoints based on stopAtEntry:
 *    - MAIN → `setFunctionBreakpoints [{"name":"main"}]` then `configurationDone` then `continue`.
 *    - ENTRY → `configurationDone` (QEMU -S already stopped at reset).
 *    - NONE → `configurationDone` then `continue`.
 * 5. Handle `stopped` events → threads/stackTrace via DAP → XDebugProcess positions.
 * 6. Resume/step via DAP requests.
 * 7. stop() → DAP `disconnect` (terminateDebuggee false) → destroy gdb → destroy emulator.
 *
 * **Line breakpoints**: not wired this iteration (no new XBreakpointType). Function-breakpoint
 * stop-at-main is the shipping baseline. This is documented in the class KDoc and in
 * [FirmwareDebugRunner]'s KDoc.
 */
class GdbDapDebugProcess(
    session: XDebugSession,
    private val console: ExecutionConsole,
    private val gdbHandler: KillableProcessHandler,
    private val emulatorHandler: ProcessHandler,
    private val client: GdbDapClient,
    private val elfPath: String,
    private val stubPort: Int,
    private val stopAtEntry: StopAtEntry,
    /**
     * True for Renode: its gdb server is started with autostart=false (a `true` flag would
     * start emulation at gdb TCP connect, racing breakpoint materialization), so emulation
     * must be started explicitly with `monitor start` after breakpoints are in place. The
     * CPU then comes up halted under gdb at the reset vector. False for QEMU (`-S` freezes).
     */
    private val requiresMonitorStart: Boolean = false,
) : XDebugProcess(session) {

    private val LOG = Logger.getInstance(GdbDapDebugProcess::class.java)

    @Volatile private var currentThreadId: Int = 1

    /**
     * True while the debuggee is stopped (stopped event received, not yet resumed).
     * Used by the console transport's [DebuggerConsoleTransport.isAcceptingCommands].
     * Every stopped-event path must route through handleStop (or set isStopped)
     * or the console locks out permanently.
     */
    @Volatile private var isStopped: Boolean = false

    /** Parent [com.intellij.openapi.Disposable] for the debugger console tab's [ConsoleView][com.intellij.execution.ui.ConsoleView]. */
    private val consoleDisposable = Disposer.newDisposable("HexanaDebuggerConsole")

    /** Stable id for the binary-breakpoint session; constant for the lifetime of this process. */
    private val breakpointSessionId: String = UUID.randomUUID().toString()

    /** Set to true once [startBinaryBreakpointSession] has registered with the coordinator. */
    @Volatile private var binarySessionStarted: Boolean = false

    /** Identity established during [startBinaryBreakpointSession]; used by [notifyBinaryBreakpointHit]. */
    @Volatile private var binaryIdentity: BinaryIdentity? = null

    override fun getEditorsProvider(): XDebuggerEditorsProvider = SimpleEditorsProvider

    override fun doGetProcessHandler(): ProcessHandler = gdbHandler

    override fun createConsole(): ExecutionConsole = console

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = emptyArray()

    override fun sessionInitialized() {
        ApplicationManager.getApplication().executeOnPooledThread {
            runSession()
        }
    }

    private fun runSession() {
        try {
            LOG.info("GdbDapDebugProcess: sending initialize")
            val initResponse = client.request("initialize", buildJsonObject {
                put("adapterId", "hexana-firmware")
                put("linesStartAt1", true)
                put("columnsStartAt1", true)
                put("pathFormat", "path")
            })
            // Capabilities live in the initialize response body (DAP spec §4.1).
            val capabilities = initResponse["body"]?.jsonObject ?: initResponse

            // GDB DAP quirk: the `attach` response is only delivered AFTER `configurationDone`,
            // so we fire attach without blocking, run configuration, then await the future.
            LOG.info("GdbDapDebugProcess: sending attach (non-blocking) to localhost:$stubPort")
            val (_, attachFuture) = client.send("attach", buildJsonObject {
                put("program", elfPath)
                put("target", "localhost:$stubPort")
            })

            LOG.info("GdbDapDebugProcess: waiting for initialized event")
            val initialized = client.waitForEvent("initialized", timeoutMs = 15_000)
            if (initialized == null) {
                LOG.warn("GdbDapDebugProcess: timeout waiting for initialized event")
            }

            applyConfigurationPhase()

            // Now the attach response should have arrived.
            try {
                client.awaitResponse(attachFuture, timeoutMs = 10_000)
            } catch (e: Exception) {
                LOG.warn("GdbDapDebugProcess: attach response not received", e)
            }

            // Materialize binary-view breakpoints WHILE THE TARGET IS STILL FROZEN and only
            // then issue the initial continue. Fast firmware (hello_world) reaches the idle
            // loop in under a millisecond of emulated time; a breakpoint inserted after the
            // continue can never fire.
            startBinaryBreakpointSession(capabilities)
            startExecutionPolicy()
            startEventLoop()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            LOG.warn("GdbDapDebugProcess session setup failed", e)
        }
    }

    /**
     * Registers a [MaterializationSession] with the project-level
     * [BreakpointMaterializationCoordinator] so binary-view breakpoints survive
     * into this firmware debug session.
     *
     * Must be called on the pooled session thread, after configuration is complete.
     * Failure is logged and never breaks existing debugging.
     */
    private fun startBinaryBreakpointSession(capabilities: JsonObject) {
        try {
            val transport = GdbDapBreakpointTransport(client, capabilities)
            val runtimeName = "gdb-dap"
            val materializer = GdbElfMaterializer(runtimeName, transport)
            val coordinator = session.project.service<BreakpointMaterializationCoordinator>()
            val vFile = LocalFileSystem.getInstance().findFileByPath(elfPath)
            val identity = if (vFile != null) {
                // identityFor must run on Dispatchers.IO; we are already on pooled thread.
                BinaryIdentityProvider.identityFor(vFile, elfBuildId = null)
            } else null
            if (identity == null) {
                LOG.warn("GdbDapDebugProcess: binary identity unavailable for $elfPath, breakpoint session not started")
                return
            }
            // rehomer is null in v1: constructing ElfBinaryProvider here requires parsing the
            // full ELF and is deferred to a follow-up.
            coordinator.sessionStartedAndAwaitInitialPush(
                MaterializationSession(
                    sessionId = breakpointSessionId,
                    identity = identity,
                    launchedPath = elfPath,
                    materializer = materializer,
                    rehomer = null,
                ),
            )
            binaryIdentity = identity
            binarySessionStarted = true
            LOG.info("GdbDapDebugProcess: binary breakpoint session started ($breakpointSessionId)")
        } catch (e: Exception) {
            LOG.warn("GdbDapDebugProcess: binary breakpoint session start failed", e)
        }
    }

    /**
     * Configuration phase: function breakpoints (stop-at-main) plus configurationDone.
     * Deliberately does NOT resume the target; registry breakpoints are materialized
     * against the still-frozen stub first, then [startExecutionPolicy] resumes.
     */
    private fun applyConfigurationPhase() {
        when (stopAtEntry) {
            StopAtEntry.MAIN -> {
                LOG.info("GdbDapDebugProcess: stopAtEntry=MAIN, setting breakpoint on main")
                client.request("setFunctionBreakpoints", buildJsonObject {
                    putJsonArray("breakpoints") {
                        addJsonObject { put("name", "main") }
                    }
                })
                client.request("configurationDone")
            }
            StopAtEntry.ENTRY, StopAtEntry.NONE -> {
                LOG.info("GdbDapDebugProcess: stopAtEntry=$stopAtEntry, configurationDone")
                client.request("configurationDone")
            }
        }
    }

    /** Execution phase: initial run policy, issued only after breakpoint materialization. */
    private fun startExecutionPolicy() {
        if (requiresMonitorStart) {
            // Renode gdbstub with autostart=false: start the emulation now that breakpoints
            // exist. qRcmd passthrough; the CPU stays halted under gdb until continue.
            val response = client.request("evaluate", buildJsonObject {
                put("expression", "monitor start")
                put("context", "repl")
            })
            LOG.warn("hexana-bp: monitor start response=$response")
        }
        when (stopAtEntry) {
            StopAtEntry.MAIN, StopAtEntry.NONE -> {
                // QEMU -S delivers a stopped(attach) event immediately on connect.
                // Drain it before continuing so the event loop sees only real stops.
                drainAttachStop()
                client.request("continue", buildJsonObject { put("threadId", currentThreadId) })
            }
            StopAtEntry.ENTRY -> {
                // Stay frozen; stopped(attach) is handled by the event loop as the initial position.
            }
        }
    }

    /**
     * Consumes a `stopped` event with reason `"attach"` or `"entry"` if one is queued.
     * QEMU in `-S` mode delivers this immediately when GDB connects via `configurationDone`.
     * For MAIN/NONE stop policies we want to suppress this initial pause so the event loop
     * only reports the intended breakpoint or run-free stop.
     */
    private fun drainAttachStop() {
        val stop = client.waitForEvent("stopped", timeoutMs = 5_000) ?: return
        val reason = stop["body"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
        if (reason == "attach" || reason == "entry") {
            LOG.debug("GdbDapDebugProcess: consumed initial stopped($reason) before continue")
        } else {
            // Unexpected stop — put it back so the event loop handles it.
            LOG.warn("GdbDapDebugProcess: unexpected stop reason '$reason' before continue — re-queuing")
            client.requeueEvent(stop)
        }
    }

    private fun startEventLoop() {
        LOG.info("GdbDapDebugProcess: event loop started")
        try {
            while (true) {
                val event = client.waitForEvent("stopped", "terminated", "exited", timeoutMs = 60_000)
                    ?: continue
                val eventName = event["event"]?.jsonPrimitive?.contentOrNull ?: continue
                LOG.debug("GdbDapDebugProcess event: $eventName")
                when (eventName) {
                    "stopped" -> handleStop(event)
                    "terminated", "exited" -> {
                        LOG.info("GdbDapDebugProcess: process $eventName")
                        endBinaryBreakpointSession()
                        invokeLater { session.stop() }
                        break
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            LOG.warn("GdbDapDebugProcess event loop error", e)
        }
        LOG.info("GdbDapDebugProcess: event loop finished")
    }

    private fun handleStop(stoppedEvent: JsonObject) {
        isStopped = true
        try {
            // Get the thread ID from the event body, fall back to currentThreadId.
            val body = stoppedEvent["body"]?.jsonObject
            val threadId = body?.get("threadId")?.jsonPrimitive?.intOrNull ?: currentThreadId
            currentThreadId = threadId

            val stackResp = client.request("stackTrace", buildJsonObject {
                put("threadId", threadId)
                put("startFrame", 0)
                put("levels", 1)
            })

            val frames = stackResp["body"]?.jsonObject?.get("stackFrames")?.jsonArray
            val topFrame = frames?.firstOrNull()?.jsonObject

            val functionName = topFrame?.get("name")?.jsonPrimitive?.contentOrNull
            val sourceObj = topFrame?.get("source")?.jsonObject
            val filePath = sourceObj?.get("path")?.jsonPrimitive?.contentOrNull
            val line = topFrame?.get("line")?.jsonPrimitive?.intOrNull ?: 0

            val position: XSourcePosition? = if (!filePath.isNullOrBlank() && line > 0) {
                val vFile = LocalFileSystem.getInstance().findFileByPath(filePath)
                if (vFile != null) XDebuggerUtilImpl.getInstance().createPosition(vFile, line - 1) else null
            } else null

            val frame = GdbStackFrame(position, functionName)
            val context = GdbSuspendContext(frame)
            invokeLater { session.positionReached(context) }

            notifyBinaryBreakpointHit(topFrame)
        } catch (e: Exception) {
            LOG.warn("GdbDapDebugProcess: handleStop failed", e)
        }
    }

    /**
     * Publishes the stopped PC to the binary-view breakpoint coordinator.
     * Tries [instructionPointerReference] (set when supportsInstructionBreakpoints is true),
     * then [address] (non-standard but emitted by some gdb-dap adapters). Skips silently
     * when no address field is present (best-effort, never breaks stop handling).
     */
    private fun notifyBinaryBreakpointHit(topFrame: JsonObject?) {
        val identity = binaryIdentity ?: return
        if (topFrame == null) return
        try {
            val addrStr = topFrame["instructionPointerReference"]?.jsonPrimitive?.contentOrNull
                ?: topFrame["address"]?.jsonPrimitive?.contentOrNull
                ?: run {
                    LOG.warn("hexana-bp: stop frame has no address field, keys=${topFrame.keys}")
                    return
                }
            LOG.warn("hexana-bp: stop at pc=$addrStr")
            val pc = addrStr.removePrefix("0x").toULongOrNull(16) ?: return
            val coordinator = session.project.service<BreakpointMaterializationCoordinator>()
            coordinator.breakpointHit(
                BinaryExecutionPosition(
                    identity,
                    BinaryAnchor.Native(virtualAddress = pc, symbolName = null, offsetInSymbol = 0u),
                ),
            )
        } catch (e: Exception) {
            LOG.warn("GdbDapDebugProcess: breakpoint hit notification failed", e)
        }
    }

    override fun resume(context: XSuspendContext?) {
        isStopped = false
        notifyExecutionResumed()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                client.request("continue", buildJsonObject { put("threadId", currentThreadId) })
            } catch (e: Exception) {
                LOG.warn("GdbDapDebugProcess: resume failed", e)
            }
        }
    }

    override fun startPausing() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                client.request("pause", buildJsonObject { put("threadId", currentThreadId) })
            } catch (e: Exception) {
                LOG.warn("GdbDapDebugProcess: pause failed", e)
            }
        }
    }

    override fun startStepOver(context: XSuspendContext?) {
        isStopped = false
        notifyExecutionResumed()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                client.request("next", buildJsonObject { put("threadId", currentThreadId) })
            } catch (e: Exception) {
                LOG.warn("GdbDapDebugProcess: stepOver failed", e)
            }
        }
    }

    override fun startStepInto(context: XSuspendContext?) {
        isStopped = false
        notifyExecutionResumed()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                client.request("stepIn", buildJsonObject { put("threadId", currentThreadId) })
            } catch (e: Exception) {
                LOG.warn("GdbDapDebugProcess: stepInto failed", e)
            }
        }
    }

    override fun startStepOut(context: XSuspendContext?) {
        isStopped = false
        notifyExecutionResumed()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                client.request("stepOut", buildJsonObject { put("threadId", currentThreadId) })
            } catch (e: Exception) {
                LOG.warn("GdbDapDebugProcess: stepOut failed", e)
            }
        }
    }

    override fun stop() {
        LOG.info("GdbDapDebugProcess.stop()")
        endBinaryBreakpointSession()
        try {
            client.request("disconnect", buildJsonObject { put("terminateDebuggee", false) })
        } catch (e: Exception) {
            LOG.debug("GdbDapDebugProcess: disconnect request failed (expected on abrupt close)", e)
        }
        client.close()
        if (!gdbHandler.isProcessTerminated) gdbHandler.destroyProcess()
        if (!emulatorHandler.isProcessTerminated) emulatorHandler.destroyProcess()
        Disposer.dispose(consoleDisposable)
    }

    private fun notifyExecutionResumed() {
        if (!binarySessionStarted) return
        try {
            session.project.service<BreakpointMaterializationCoordinator>().executionResumed()
        } catch (e: Exception) {
            LOG.warn("GdbDapDebugProcess: executionResumed notification failed", e)
        }
    }

    private fun endBinaryBreakpointSession() {
        if (!binarySessionStarted) return
        try {
            session.project.service<BreakpointMaterializationCoordinator>().sessionEnded(breakpointSessionId)
        } catch (e: Exception) {
            LOG.warn("GdbDapDebugProcess: sessionEnded failed", e)
        }
    }

    override fun createTabLayouter(): XDebugTabLayouter = object : XDebugTabLayouter() {
        override fun registerAdditionalContent(ui: RunnerLayoutUi) {
            val transport = GdbDapConsoleTransport()
            val consoleContent = ui.createContent(
                "HexanaDebuggerConsole",
                buildDebuggerConsolePanel(session.project, transport, consoleDisposable),
                "Debugger Console",
                null,
                null,
            )
            consoleContent.isCloseable = false
            ui.addContent(consoleContent)
        }
    }

    /**
     * [DebuggerConsoleTransport] for the GDB-DAP session.
     *
     * Commands are forwarded via the DAP `evaluate` request with context `"repl"`.
     * GDB's DAP adapter returns the command output in the `result` field of the response body,
     * so the console output relies solely on the evaluate response body (not the event queue,
     * which is owned by the session loop).
     *
     * LANDMINE: evaluating `continue` or any execution-control command through the GDB -i=dap
     * REPL wedges the interpreter. The guardrail classifier intercepts those commands for
     * [ConsoleSessionKind.GDB_DAP] before they reach [execute].
     */
    private inner class GdbDapConsoleTransport : DebuggerConsoleTransport {
        override val sessionKind: ConsoleSessionKind get() = ConsoleSessionKind.GDB_DAP

        override fun execute(command: String): List<String> =
            renderEvaluateResponse(
                client.request("evaluate", buildJsonObject {
                    put("expression", command)
                    put("context", "repl")
                })
            )

        override fun isAcceptingCommands(): Boolean = isStopped

        override fun performIntercept(action: InterceptAction) {
            when (action) {
                InterceptAction.RESUME -> resume(null)
                InterceptAction.PAUSE -> startPausing()
                InterceptAction.STEP_OVER -> startStepOver(null)
                InterceptAction.STEP_INTO -> startStepInto(null)
                InterceptAction.STEP_OUT -> startStepOut(null)
                InterceptAction.STOP -> stop()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    private class GdbSuspendContext(private val top: GdbStackFrame) : XSuspendContext() {
        override fun getActiveExecutionStack(): XExecutionStack = GdbExecutionStack(top)
    }

    private class GdbExecutionStack(private val top: GdbStackFrame) : XExecutionStack("Firmware") {
        override fun getTopFrame(): XStackFrame = top
        override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
            if (firstFrameIndex == 0) container.addStackFrames(listOf(top), true)
            else container.addStackFrames(emptyList(), true)
        }
    }

    private class GdbStackFrame(
        private val position: XSourcePosition?,
        private val functionName: String?,
    ) : XStackFrame() {
        override fun getSourcePosition(): XSourcePosition? = position
        override fun customizePresentation(component: com.intellij.ui.ColoredTextContainer) {
            component.append(
                functionName ?: "<unknown>",
                com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES,
            )
        }
    }

    private object SimpleEditorsProvider : XDebuggerEditorsProvider() {
        override fun getFileType(): FileType = PlainTextFileType.INSTANCE
        override fun createDocument(
            project: com.intellij.openapi.project.Project,
            expression: com.intellij.xdebugger.XExpression,
            sourcePosition: XSourcePosition?,
            mode: EvaluationMode,
        ): Document {
            val factory = com.intellij.psi.PsiFileFactory.getInstance(project)
            val file = factory.createFileFromText(
                "firmware-dap-eval.txt",
                PlainTextFileType.INSTANCE,
                expression.expression,
            )
            return com.intellij.psi.PsiDocumentManager.getInstance(project)
                .getDocument(file)
                ?: com.intellij.openapi.editor.EditorFactory.getInstance()
                    .createDocument(expression.expression)
        }
    }
}

// ---------------------------------------------------------------------------
// DAP evaluate response rendering
// ---------------------------------------------------------------------------

/**
 * Converts a DAP `evaluate` response [body] into output lines for the console.
 *
 * On success (`success=true`), the GDB DAP adapter returns the command output
 * in the `result` string field of the response body. The function splits it on
 * newlines and drops trailing blank lines.
 *
 * On an error response (`success=false`) or a missing/empty `result`, the
 * `message` field from the top-level response or body is used as a single
 * error line.
 */
internal fun renderEvaluateResponse(body: JsonObject): List<String> {
    val success = body["success"]?.jsonPrimitive?.booleanOrNull ?: false
    if (!success) {
        val msg = body["message"]?.jsonPrimitive?.contentOrNull
            ?: body["body"]?.jsonObject?.get("error")?.jsonObject?.get("format")?.jsonPrimitive?.contentOrNull
            ?: "evaluate command failed"
        return listOf("error: $msg")
    }
    val result = body["body"]?.jsonObject?.get("result")?.jsonPrimitive?.contentOrNull ?: ""
    if (result.isEmpty()) return emptyList()
    return result.split('\n').dropLastWhile { it.isBlank() }
}
