package org.jetbrains.hexana.run

import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext

/**
 * Suspend context produced when LLDB stops execution (breakpoint hit,
 * step completed, signal, etc.).
 *
 * It provides a single execution stack whose top frame points at the
 * source location reported by LLDB.
 */
class WasmSuspendContext(
    private val topFrame: XStackFrame,
) : XSuspendContext() {

    private val executionStack = WasmExecutionStack(topFrame)

    override fun getActiveExecutionStack(): XExecutionStack = executionStack

    override fun getExecutionStacks(): Array<XExecutionStack> = arrayOf(executionStack)
}

private class WasmExecutionStack(
    private val topFrame: XStackFrame,
) : XExecutionStack("Wasm Thread") {

    override fun getTopFrame(): XStackFrame = topFrame

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer?) {
        if (firstFrameIndex == 0) {
            container?.addStackFrames(listOf(topFrame), true)
        } else {
            container?.addStackFrames(emptyList(), true)
        }
    }
}
