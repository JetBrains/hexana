package org.jetbrains.hexana.firmware.debug

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.ui.XDebugTabLayouter
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.impl.XDebuggerUtilImpl
import org.jetbrains.hexana.firmware.run.StopAtEntry
import org.jetbrains.hexana.run.ConsoleSessionKind
import org.jetbrains.hexana.run.DebuggerConsoleTransport
import org.jetbrains.hexana.run.InterceptAction
import org.jetbrains.hexana.run.LldbCommunicator
import org.jetbrains.hexana.run.buildDebuggerConsolePanel
import org.jetbrains.hexana.shared.parseLldbFrameLocation
import org.jetbrains.hexana.shared.parseLldbFunctionName

/**
 * Debug process for ELF firmware running under a GDB-stub emulator (QEMU or Renode).
 *
 * Composes [LldbCommunicator] to drive LLDB's `gdb-remote` attach. LLDB reads the ELF
 * and its DWARF debug info directly; source-level stepping and variable display are
 * handled by LLDB's own DWARF engine without any Hexana-specific wasm plumbing.
 *
 * **Line breakpoints in arbitrary source files are NOT wired this iteration.** Wiring them
 * requires a registered XBreakpointType (to intercept editor gutter actions); adding a new
 * type was explicitly out of scope. Breakpoints are applied programmatically via
 * [LldbCommunicator.sendBreakpointOnFunction] at session init per [stopAtEntry]. LLDB
 * native breakpoints (set via the LLDB console in the Threads tab) work normally.
 *
 * @param session The active debug session.
 * @param console Execution console (LLDB stdout) attached to the LLDB process.
 * @param lldbHandler Process handler for the LLDB process.
 * @param emulatorHandler Process handler for the emulator (QEMU or Renode); destroyed on stop.
 * @param lldb LLDB communicator already attached to [lldbHandler].
 * @param stopAtEntry Controls where execution pauses after GDB attach.
 */
class FirmwareDebugProcess(
    session: XDebugSession,
    private val console: ExecutionConsole,
    private val lldbHandler: ProcessHandler,
    private val emulatorHandler: ProcessHandler,
    private val lldb: LldbCommunicator,
    private val stopAtEntry: StopAtEntry,
) : XDebugProcess(session) {

    private val LOG = Logger.getInstance(FirmwareDebugProcess::class.java)

    /** Parent [com.intellij.openapi.Disposable] for the debugger console tab's [com.intellij.execution.ui.ConsoleView]. */
    private val consoleDisposable = Disposer.newDisposable("HexanaDebuggerConsole")

    override fun getEditorsProvider(): XDebuggerEditorsProvider = SimpleEditorsProvider

    override fun doGetProcessHandler(): ProcessHandler = lldbHandler

    override fun createConsole(): ExecutionConsole = console

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = emptyArray()

    override fun createTabLayouter(): XDebugTabLayouter = object : XDebugTabLayouter() {
        override fun registerAdditionalContent(ui: RunnerLayoutUi) {
            val transport = LldbFirmwareConsoleTransport()
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
     * Called by the platform after the debug session UI is ready. This is where we
     * apply the stop-at-entry policy and start the event loop.
     *
     * The emulator is already frozen (QEMU -S / Renode StartGdbServer …true) and LLDB
     * has already attached via `gdb-remote` before this method is called.
     */
    override fun sessionInitialized() {
        ApplicationManager.getApplication().executeOnPooledThread {
            applyStopAtEntryPolicy()
            startEventLoop()
        }
    }

    /** Applies the [stopAtEntry] policy: sets a breakpoint or continues immediately. */
    private fun applyStopAtEntryPolicy() {
        when (stopAtEntry) {
            StopAtEntry.ENTRY -> {
                // QEMU -S leaves the CPU at the reset vector. LLDB is already stopped;
                // present the stop to the IDE via positionReached.
                LOG.info("stopAtEntry=ENTRY: presenting initial stop at reset vector")
                presentCurrentStop()
            }
            StopAtEntry.MAIN -> {
                lldb.sendBreakpointOnFunction("main")
                LOG.info("stopAtEntry=MAIN: breakpoint on main, continuing")
                lldb.resume()
            }
            StopAtEntry.NONE -> {
                LOG.info("stopAtEntry=NONE: continuing immediately")
                lldb.resume()
            }
        }
    }

    /**
     * Presents the current LLDB stop position to the IDE (used for stop-at-entry).
     * Queries `frame info` to extract the source location and function name.
     */
    private fun presentCurrentStop() {
        val frameLines = lldb.sendCommandAndCollectOutput("frame info", timeoutMs = 10_000)
        val frameLocation = parseLldbFrameLocation(frameLines)
        val functionName = parseLldbFunctionName(frameLines)

        val position = if (frameLocation != null && frameLocation.line > 0) {
            val vFile = LocalFileSystem.getInstance().findFileByPath(frameLocation.filePath)
            if (vFile != null) XDebuggerUtilImpl.getInstance().createPosition(vFile, frameLocation.line - 1) else null
        } else null

        val frame = FirmwareStackFrame(position, functionName, lldb)
        val context = FirmwareSuspendContext(frame)
        ApplicationManager.getApplication().invokeLater {
            session.positionReached(context)
        }
    }

    /**
     * Background event loop. Processes stop events from [LldbCommunicator] until the
     * process exits or the session ends.
     */
    private fun startEventLoop() {
        ApplicationManager.getApplication().executeOnPooledThread {
            LOG.info("FirmwareDebugProcess event loop started")
            try {
                while (true) {
                    val event = lldb.waitForStopEvent(timeoutMs = 60_000) ?: continue

                    LOG.debug("FirmwareDebugProcess event: $event")
                    when (event) {
                        is LldbCommunicator.StopEvent.BreakpointHit,
                        is LldbCommunicator.StopEvent.Stepped,
                        is LldbCommunicator.StopEvent.StoppedAtEntry -> {
                            handleStop()
                        }
                        is LldbCommunicator.StopEvent.ProcessExited -> {
                            LOG.info("FirmwareDebugProcess: process exited (code=${event.exitCode})")
                            ApplicationManager.getApplication().invokeLater {
                                session.stop()
                            }
                            break
                        }
                    }
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("FirmwareDebugProcess event loop error", e)
            }
            LOG.info("FirmwareDebugProcess event loop finished")
        }
    }

    /** Queries frame info and presents the stop to the IDE. */
    private fun handleStop() {
        val frameLines = lldb.sendCommandAndCollectOutput("frame info", timeoutMs = 10_000)
        val frameLocation = parseLldbFrameLocation(frameLines)
        val functionName = parseLldbFunctionName(frameLines)

        val position = if (frameLocation != null && frameLocation.line > 0) {
            val vFile = LocalFileSystem.getInstance().findFileByPath(frameLocation.filePath)
            if (vFile != null) XDebuggerUtilImpl.getInstance().createPosition(vFile, frameLocation.line - 1) else null
        } else null

        val frame = FirmwareStackFrame(position, functionName, lldb)
        val context = FirmwareSuspendContext(frame)
        ApplicationManager.getApplication().invokeLater {
            session.positionReached(context)
        }
    }

    override fun resume(context: XSuspendContext?) {
        ApplicationManager.getApplication().executeOnPooledThread { lldb.resume() }
    }

    override fun startPausing() {
        ApplicationManager.getApplication().executeOnPooledThread { lldb.pause() }
    }

    override fun startStepOver(context: XSuspendContext?) {
        ApplicationManager.getApplication().executeOnPooledThread { lldb.stepOver() }
    }

    override fun startStepInto(context: XSuspendContext?) {
        ApplicationManager.getApplication().executeOnPooledThread { lldb.stepInto() }
    }

    override fun startStepOut(context: XSuspendContext?) {
        ApplicationManager.getApplication().executeOnPooledThread { lldb.stepOut() }
    }

    override fun stop() {
        LOG.info("FirmwareDebugProcess.stop()")
        lldb.destroy()
        if (!lldbHandler.isProcessTerminated) {
            lldbHandler.destroyProcess()
        }
        if (!emulatorHandler.isProcessTerminated) {
            emulatorHandler.destroyProcess()
        }
        Disposer.dispose(consoleDisposable)
    }

    // -------------------------------------------------------------------------
    // Console transport
    // -------------------------------------------------------------------------

    /**
     * [DebuggerConsoleTransport] for the LLDB firmware session.
     *
     * Commands are forwarded via [LldbCommunicator.sendCommandAndCollectOutput].
     * Execution-control commands (continue, next, step, finish, quit, etc.) are
     * intercepted by the guardrail classifier for [ConsoleSessionKind.LLDB_FIRMWARE]
     * and routed to the matching IDE debugger action, keeping IDE state in sync.
     */
    private inner class LldbFirmwareConsoleTransport : DebuggerConsoleTransport {
        override val sessionKind: ConsoleSessionKind get() = ConsoleSessionKind.LLDB_FIRMWARE

        override fun execute(command: String): List<String> =
            lldb.sendCommandAndCollectOutput(command, 10_000)

        override fun isAcceptingCommands(): Boolean = !lldb.isRunning && !lldb.debuggeeExited

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

    /**
     * Minimal suspend context for a firmware stop. Wraps a single [FirmwareStackFrame].
     *
     * Variables and full backtraces (via `bt`) are surfaced through the LLDB console;
     * the IDE variables panel is intentionally minimal this iteration.
     */
    private class FirmwareSuspendContext(private val top: FirmwareStackFrame) : XSuspendContext() {
        override fun getActiveExecutionStack(): XExecutionStack = FirmwareExecutionStack(top)
    }

    private class FirmwareExecutionStack(private val top: FirmwareStackFrame) :
        XExecutionStack("Firmware") {
        override fun getTopFrame(): XStackFrame = top
        override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
            if (firstFrameIndex == 0) container.addStackFrames(listOf(top), true)
            else container.addStackFrames(emptyList(), true)
        }
    }

    /**
     * A single firmware stack frame. Source position comes from LLDB's `frame info`
     * output parsed by [parseLldbFrameLocation]. Variables are not resolved this
     * iteration (no XDebuggerEvaluator attached — users access locals via the LLDB console).
     */
    private class FirmwareStackFrame(
        private val position: com.intellij.xdebugger.XSourcePosition?,
        private val functionName: String?,
        @Suppress("UNUSED_PARAMETER") lldb: LldbCommunicator,
    ) : XStackFrame() {
        override fun getSourcePosition(): com.intellij.xdebugger.XSourcePosition? = position
        override fun customizePresentation(
            component: com.intellij.ui.ColoredTextContainer,
        ) {
            component.append(functionName ?: "<unknown>", com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
    }

    /**
     * Minimal editors provider. Returns [PlainTextFileType] so the evaluate-expression
     * dialog accepts arbitrary text; evaluation is handled via the LLDB console.
     */
    private object SimpleEditorsProvider : XDebuggerEditorsProvider() {
        override fun getFileType(): FileType = PlainTextFileType.INSTANCE
        override fun createDocument(
            project: com.intellij.openapi.project.Project,
            expression: com.intellij.xdebugger.XExpression,
            sourcePosition: com.intellij.xdebugger.XSourcePosition?,
            mode: com.intellij.xdebugger.evaluation.EvaluationMode,
        ): Document {
            val factory = com.intellij.psi.PsiFileFactory.getInstance(project)
            val file = factory.createFileFromText(
                "firmware-eval.txt",
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
