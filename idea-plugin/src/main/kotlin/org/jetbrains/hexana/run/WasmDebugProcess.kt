package org.jetbrains.hexana.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext

/**
 * A debug process that wraps a wasmtime execution launched with
 * `-D debug-info`.  The process runs under the Debug tool window
 * so users see console output and can stop/restart the process.
 *
 * Full native-debugger stepping/breakpoints require the CIDR native debug
 * plugin (available in CLion / IDEA Ultimate with the Native Debugging
 * Support plugin).  This process provides the baseline: console output
 * in the Debug tab and the ability to stop / restart the process.
 */
class WasmDebugProcess(
    session: XDebugSession,
    private val console: ExecutionConsole,
    private val processHandler: ProcessHandler,
) : XDebugProcess(session) {

    override fun getEditorsProvider(): XDebuggerEditorsProvider = WasmDebuggerEditorsProvider()

    override fun doGetProcessHandler(): ProcessHandler = processHandler

    override fun createConsole(): ExecutionConsole = console

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = emptyArray()

    override fun stop() {
        processHandler.destroyProcess()
    }

    override fun resume(context: XSuspendContext?) {
        // No-op: native stepping not available without CIDR
    }

    override fun startPausing() {
        // No-op: native stepping not available without CIDR
    }

    override fun startStepOver(context: XSuspendContext?) {
        // No-op: native stepping not available without CIDR
    }

    override fun startStepInto(context: XSuspendContext?) {
        // No-op: native stepping not available without CIDR
    }

    override fun startStepOut(context: XSuspendContext?) {
        // No-op: native stepping not available without CIDR
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
