package org.jetbrains.hexana.run

import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList

/**
 * A stack frame that points to a source position reported by LLDB
 * when execution stops at a breakpoint or after a step.
 *
 * When an [LldbCommunicator] is available, the frame provides an
 * [XDebuggerEvaluator] that lets users evaluate expressions in the
 * current LLDB frame context, and populates the **Variables** pane
 * with the local variables and parameters from the current frame via
 * LLDB's `frame variable` command.
 *
 * @param frameIndex the LLDB frame index (e.g. 0 for the top frame).
 *                   Used when selecting a non-top frame before
 *                   querying variables.
 */
class WasmStackFrame(
    private val sourcePosition: XSourcePosition?,
    private val functionName: String? = null,
    private val lldb: LldbCommunicator? = null,
    private val frameIndex: Int = 0,
) : XStackFrame() {

    override fun getSourcePosition(): XSourcePosition? = sourcePosition

    override fun getEvaluator(): XDebuggerEvaluator? =
        lldb?.let { WasmDebuggerEvaluator(it) }

    override fun customizePresentation(component: ColoredTextContainer) {
        val name = functionName ?: sourcePosition?.file?.name ?: "unknown"
        val line = sourcePosition?.let { ":${it.line + 1}" } ?: ""
        component.append("$name$line", SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }

    override fun computeChildren(node: XCompositeNode) {
        val communicator = lldb
        if (communicator == null) {
            node.addChildren(XValueChildrenList.EMPTY, true)
            return
        }

        com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread {
                try {
                    // Select the correct frame before querying variables,
                    // so non-top stack frames report the right locals.
                    if (frameIndex != 0) {
                        communicator.sendCommandAndCollectOutput("frame select $frameIndex")
                    }

                    val lines = communicator.sendCommandAndCollectOutput("frame variable")
                    val variables = WasmLocalVariable.parseFrameVariableOutput(lines)

                    val list = XValueChildrenList()
                    for (variable in variables) {
                        list.add(variable.name, variable)
                    }
                    node.addChildren(list, true)
                } catch (e: Exception) {
                    node.setErrorMessage("Failed to retrieve variables: ${e.message}")
                }
            }
    }
}
