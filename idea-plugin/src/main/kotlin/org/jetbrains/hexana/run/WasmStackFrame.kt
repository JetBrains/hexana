package org.jetbrains.hexana.run

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValueChildrenList
import org.jetbrains.hexana.shared.parseLldbExpressionResult

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
internal class WasmStackFrame(
    private val sourcePosition: XSourcePosition?,
    private val functionName: String? = null,
    private val lldb: LldbCommunicator? = null,
    private val frameIndex: Int = 0,
    private val dwarfVariableResolver: WasmDwarfVariableResolver? = null,
    private val engine: WasmEngine = WasmEngine.WASMTIME,
    private val guestOnly: Boolean = false,
) : XStackFrame() {

    override fun getSourcePosition(): XSourcePosition? = sourcePosition

    override fun getEvaluator(): XDebuggerEvaluator? =
        lldb?.let { WasmDebuggerEvaluator(it, frameIndex, guestOnly) }

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
                    // This is not needed for WAMR which uses a GDB remote stub,
                    // and must be SKIPPED in gdbstub guest sessions: any
                    // `expression` command aborts LLDB for wasm targets.
                    if (engine == WasmEngine.WASMTIME && !guestOnly) {
                        initializeVmContext(communicator)
                    }

                    val lines = communicator.sendCommandAndCollectOutput("frame variable")
                    val variables = WasmLocalVariable.parseFrameVariableOutput(lines)

                    // For variables that LLDB's `frame variable` reports as
                    // unavailable, try the `expression` command as a fallback.
                    // In Wasmtime JIT-compiled code the expression evaluator
                    // can sometimes resolve values that `frame variable` cannot
                    // (it uses a different code path for location lookup).
                    val resolved = variables.map { variable ->
                        if (!variable.isUnavailable) return@map variable
                        // The `expression` fallback would abort LLDB in
                        // gdbstub guest sessions; skip straight to DWARF.
                        val attempted = if (guestOnly) variable else tryResolveViaExpression(communicator, variable)
                        if (!attempted.isUnavailable) return@map attempted

                        // Try DWARF-based resolution using parsed location
                        // metadata from the Wasm binary. This can resolve
                        // variables whose locations are described by
                        // DW_OP_addr, DW_OP_fbreg, or DW_OP_WASM_location
                        // even when LLDB's standard paths fail.
                        val resolver = dwarfVariableResolver
                        if (resolver != null) {
                            val dwarfResolved = resolver.resolveVariable(
                                communicator, attempted, functionName,
                            )
                            if (!dwarfResolved.isUnavailable) return@map dwarfResolved
                        }

                        // All retrieval strategies failed. Determine
                        // what message to show the user.
                        //
                        // First check our own parsed DWARF metadata —
                        // it may have location data (e.g. location
                        // lists with DW_OP_WASM_location) that LLDB's
                        // Wasm plugin cannot evaluate. In that case
                        // show a storage-location hint rather than
                        // the misleading "DWARF metadata missing".
                        val ownLocationDesc = resolver?.describeVariableLocation(
                            variable.name, functionName,
                        )
                        if (ownLocationDesc != null) {
                            WasmLocalVariable.withDwarfLocationHint(
                                attempted,
                                ownLocationDesc.substringBefore(","),
                                ownLocationDesc,
                            )
                        } else if (resolver != null &&
                            resolver.isKnownInDwarf(variable.name, functionName) &&
                            !resolver.hasLocationMetadata(variable.name, functionName)
                        ) {
                            // The variable exists in DWARF (e.g. as a
                            // formal parameter) but the compiler did
                            // not emit DW_AT_location for it. This is
                            // a toolchain limitation (common with
                            // TinyGo), not a missing DWARF section.
                            WasmLocalVariable.withNoLocationMessage(attempted)
                        } else if (isDwarfLocationMissing(communicator, variable)) {
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
     *
     * Note: for Wasmtime JIT-compiled code, Cranelift transforms
     * original DWARF `DW_OP_WASM_location` expressions into native
     * register-based locations (e.g. `rdi+0x10`, `[rsp+0x20]`).
     * These are valid locations and must NOT be treated as missing.
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
            if (joined.contains("loc=")) {
                // Extract the location value after "loc="
                val locValue = joined.substringAfter("loc=").trim()
                // Empty or whitespace-only location means missing
                if (locValue.isEmpty() || locValue.isBlank()) return true
                // Any non-empty location string (DW_OP expressions,
                // native register names, stack slots, etc.) means the
                // DWARF metadata IS present — the variable just
                // couldn't be read for another reason.
                return false
            }
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
            val result = parseLldbExpressionResult(joined)
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
