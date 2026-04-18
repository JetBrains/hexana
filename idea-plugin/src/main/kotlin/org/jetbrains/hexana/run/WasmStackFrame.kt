package org.jetbrains.hexana.run

import com.intellij.openapi.diagnostic.Logger
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
        lldb?.let { WasmDebuggerEvaluator(it, frameIndex) }

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
                    // Always select the correct frame before querying
                    // variables. This is needed even for frame #0 because
                    // the user may have navigated to a different frame
                    // previously, leaving LLDB's selected frame out of sync.
                    communicator.sendCommandAndCollectOutput("frame select $frameIndex")

                    // Wasmtime requires __vmctx->set() to be called before
                    // inspecting variables — it sets the current Wasm instance
                    // context that LLDB needs for memory and pointer access.
                    // See https://docs.wasmtime.dev/examples-debugging-native-debugger.html
                    initializeVmContext(communicator)

                    val lines = communicator.sendCommandAndCollectOutput("frame variable")
                    val variables = WasmLocalVariable.parseFrameVariableOutput(lines)

                    // For variables that LLDB's `frame variable` reports as
                    // unavailable, try the `expression` command as a fallback.
                    // In Wasmtime JIT-compiled code the expression evaluator
                    // can sometimes resolve values that `frame variable` cannot
                    // (it uses a different code path for location lookup).
                    val resolved = variables.map { variable ->
                        if (!variable.isUnavailable) return@map variable
                        val attempted = tryResolveViaExpression(communicator, variable)
                        if (!attempted.isUnavailable) return@map attempted
                        // Both retrieval strategies failed. Check whether
                        // DWARF location metadata is missing so we can show
                        // a more informative message to the user.
                        if (isDwarfLocationMissing(communicator, variable)) {
                            WasmLocalVariable.withDwarfMissingMessage(attempted)
                        } else {
                            attempted
                        }
                    }

                    val list = XValueChildrenList()
                    for (variable in resolved) {
                        list.add(variable.name, variable)
                    }
                    node.addChildren(list, true)
                } catch (e: Exception) {
                    node.setErrorMessage("Failed to retrieve variables: ${e.message}")
                }
            }
    }

    /**
     * Calls `__vmctx->set()` in LLDB to initialize the Wasmtime VM
     * context. This is required before inspecting Wasm variables or
     * dereferencing Wasm pointers. The call is a no-op if `__vmctx`
     * is not available (e.g. in non-Wasmtime frames).
     */
    private fun initializeVmContext(communicator: LldbCommunicator) {
        try {
            communicator.sendCommandAndCollectOutput(
                "expression -- __vmctx->set()",
                timeoutMs = 2_000,
            )
        } catch (e: Exception) {
            LOG.debug("__vmctx->set() failed (expected in non-Wasm frames): ${e.message}")
        }
    }

    /**
     * Checks whether the DWARF debug info for the given variable is
     * missing the location attribute (`DW_AT_location`). When LLDB
     * reports a variable as unavailable, this distinguishes between
     * "the DWARF entry exists but has no location" and "the value
     * is temporarily unavailable for other reasons".
     *
     * Uses LLDB's Python scripting API to query the variable's
     * [SBValue.GetLocation()](https://lldb.llvm.org/python_api/lldb.SBValue.html)
     * — if it is empty or absent the location metadata is missing.
     */
    private fun isDwarfLocationMissing(
        communicator: LldbCommunicator,
        variable: WasmLocalVariable,
    ): Boolean {
        return try {
            val lines = communicator.sendCommandAndCollectOutput(
                "script v = lldb.frame.FindVariable('${variable.name}'); " +
                    "print('valid=' + str(v.IsValid()) + ' loc=' + str(v.location))",
                timeoutMs = 2_000,
            )
            val joined = lines.joinToString(" ").trim()
            // When the variable has no DWARF location, LLDB returns
            // an invalid SBValue or an empty / None location string.
            if (joined.contains("valid=False")) return true
            if (joined.contains("loc=None")) return true
            if (joined.contains("loc=") && !joined.contains("loc=DW_OP")) return true
            false
        } catch (e: Exception) {
            LOG.debug("DWARF location check failed for '${variable.name}': ${e.message}")
            false
        }
    }

    /**
     * Attempts to retrieve the value of an unavailable variable by
     * evaluating it as an expression in LLDB. Returns a new
     * [WasmLocalVariable] with the resolved value on success, or the
     * original variable if the expression also fails.
     */
    private fun tryResolveViaExpression(
        communicator: LldbCommunicator,
        variable: WasmLocalVariable,
    ): WasmLocalVariable {
        return try {
            val exprLines = communicator.sendCommandAndCollectOutput(
                "expression -- ${variable.name}",
                timeoutMs = 3_000,
            )
            val joined = exprLines.joinToString("\n").trim()
            if (joined.isEmpty() || joined.startsWith("error:")) {
                return variable
            }
            val result = WasmDebuggerEvaluator.parseLldbExpressionResult(joined)
            // Only replace if we actually got a meaningful value
            if (result.displayValue.contains("variable not available") ||
                result.displayValue.contains("<unavailable>")
            ) {
                return variable
            }
            WasmLocalVariable.withResolvedValue(variable, result.typeName, result.displayValue)
        } catch (e: Exception) {
            LOG.debug("Expression fallback failed for '${variable.name}': ${e.message}")
            variable
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WasmStackFrame::class.java)
    }
}
