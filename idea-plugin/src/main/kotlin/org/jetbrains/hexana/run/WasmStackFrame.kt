package org.jetbrains.hexana.run

import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XStackFrame

/**
 * A stack frame that points to a source position reported by LLDB
 * when execution stops at a breakpoint or after a step.
 */
class WasmStackFrame(
    private val sourcePosition: XSourcePosition?,
    private val functionName: String? = null,
) : XStackFrame() {

    override fun getSourcePosition(): XSourcePosition? = sourcePosition

    override fun customizePresentation(component: ColoredTextContainer) {
        val name = functionName ?: sourcePosition?.file?.name ?: "unknown"
        val line = sourcePosition?.let { ":${it.line + 1}" } ?: ""
        component.append("$name$line", SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }
}
