package org.jetbrains.hexana.run

import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
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

    companion object {
        private val LOG = Logger.getInstance(WasmDebuggerEvaluator::class.java)

        /**
         * LLDB expression results typically look like:
         * ```
         * (int) $0 = 42
         * (const char *) $1 = 0x... "hello"
         * ```
         * This pattern captures the type, the scratch variable name, and
         * the value portion.
         */
        private val EXPRESSION_RESULT_PATTERN =
            """\((.+?)\)\s+\$\d+\s+=\s+(.+)""".toRegex(RegexOption.DOT_MATCHES_ALL)

        internal fun parseLldbExpressionResult(text: String): ExpressionResult {
            val match = EXPRESSION_RESULT_PATTERN.find(text)
            return if (match != null) {
                ExpressionResult(
                    typeName = match.groupValues[1].trim(),
                    displayValue = match.groupValues[2].trim(),
                )
            } else {
                // Fallback: show the raw output when the pattern doesn't match
                ExpressionResult(typeName = null, displayValue = text)
            }
        }
    }

    internal data class ExpressionResult(
        val typeName: String?,
        val displayValue: String,
    )
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
