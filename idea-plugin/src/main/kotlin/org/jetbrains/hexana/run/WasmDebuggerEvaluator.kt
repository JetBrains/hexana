package org.jetbrains.hexana.run

import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import org.jetbrains.hexana.shared.parseLldbExpressionResult
import javax.swing.Icon

/**
 * Evaluates user expressions during a Wasm debug session by
 * forwarding them to LLDB's `expression` command.
 *
 * LLDB evaluates the expression in the current frame context,
 * which gives access to local variables and parameters when
 * DWARF debug info is present.
 */
class WasmDebuggerEvaluator(
    private val lldb: LldbCommunicator,
    private val frameIndex: Int = 0,
    private val guestOnly: Boolean = false,
) : XDebuggerEvaluator() {

    override fun evaluate(
        expression: String,
        callback: XEvaluationCallback,
        expressionPosition: XSourcePosition?,
    ) {
        // Run on a pooled thread so we don't block the UI while
        // waiting for LLDB to respond.
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .executeOnPooledThread {
                try {
                    doEvaluate(expression, callback)
                } catch (e: Exception) {
                    LOG.warn("Expression evaluation failed: $expression", e)
                    callback.errorOccurred("Evaluation failed: ${e.message}")
                }
            }
    }

    private fun doEvaluate(expression: String, callback: XEvaluationCallback) {
        // Select the correct frame so the expression is evaluated in
        // the context of the stack frame this evaluator belongs to.
        lldb.sendCommandAndCollectOutput("frame select $frameIndex")

        // In gdbstub guest sessions any `expression` command aborts the
        // LLDB process: clang cannot generate code for the wasm object
        // format (fatal "Objective-C support is unimplemented" LLVM
        // error, present in Homebrew and wasi-sdk LLDB 22 alike). Only
        // `frame variable` paths are safe there.
        if (guestOnly) {
            evaluateViaFrameVariable(expression.trim(), callback)
            return
        }

        // Initialize the Wasmtime VM context so that Wasm pointers and
        // variables can be resolved. Errors are ignored because the
        // call may legitimately fail in non-Wasm frames.
        try {
            lldb.sendCommandAndCollectOutput("expression -- __vmctx->set()", timeoutMs = 2_000)
        } catch (_: Exception) {
            // expected in non-Wasm frames
        }

        val lines = lldb.sendCommandAndCollectOutput("expression -- $expression")
        if (lines.isEmpty()) {
            callback.errorOccurred("No response from LLDB")
            return
        }

        val joined = lines.joinToString("\n").trim()

        // LLDB error output typically starts with "error:"
        if (joined.startsWith("error:")) {
            callback.errorOccurred(joined.removePrefix("error:").trim())
            return
        }

        val result = parseLldbExpressionResult(joined)
        callback.evaluated(WasmExpressionValue(result.typeName, result.displayValue))
    }

    /**
     * Evaluates a plain variable path with `frame variable`, which reads
     * values without invoking the expression compiler. Anything that is
     * not a variable path is rejected with an explanatory error.
     */
    private fun evaluateViaFrameVariable(expression: String, callback: XEvaluationCallback) {
        if (!isFrameVariablePath(expression)) {
            callback.errorOccurred(
                "Only variable paths (x, s.field, p->x, arr[0]) can be evaluated when " +
                    "debugging the wasm guest: LLDB cannot compile expressions for wasm targets.",
            )
            return
        }

        val lines = lldb.sendCommandAndCollectOutput("frame variable $expression")
        if (lines.isEmpty()) {
            callback.errorOccurred("No response from LLDB")
            return
        }

        val joined = lines.joinToString("\n").trim()
        if (joined.startsWith("error:")) {
            callback.errorOccurred(joined.removePrefix("error:").trim())
            return
        }

        val result = parseLldbExpressionResult(joined)
        callback.evaluated(WasmExpressionValue(result.typeName, result.displayValue))
    }

    companion object {
        private val LOG = Logger.getInstance(WasmDebuggerEvaluator::class.java)

        /**
         * A `frame variable` expression path: an identifier followed by
         * any number of `.member`, `->member`, or `[index]` segments.
         */
        private val FRAME_VARIABLE_PATH =
            Regex("""[A-Za-z_$][A-Za-z0-9_$]*(?:(?:\.|->)[A-Za-z_$][A-Za-z0-9_$]*|\[\d+])*""")

        /** True when [expression] is a plain variable path safe for `frame variable`. */
        internal fun isFrameVariablePath(expression: String): Boolean =
            FRAME_VARIABLE_PATH.matches(expression)
    }
}

/**
 * Represents a single evaluated expression value displayed in the
 * debugger's "Evaluate Expression" result panel or inline watches.
 */
private class WasmExpressionValue(
    private val typeName: String?,
    private val displayValue: String,
) : XValue() {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(
            null as Icon?,
            typeName,
            displayValue,
            false,
        )
    }
}
