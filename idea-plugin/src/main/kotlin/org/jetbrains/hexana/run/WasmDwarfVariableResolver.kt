package org.jetbrains.hexana.run

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.hexana.dwarf.DwarfExprOp
import org.jetbrains.hexana.dwarf.DwarfLocationDescription
import org.jetbrains.hexana.dwarf.DwarfLocationListEntry
import org.jetbrains.hexana.dwarf.DwarfSubprogramInfo
import org.jetbrains.hexana.dwarf.DwarfVariableInfo
import org.jetbrains.hexana.dwarf.DwarfVariableInfoExtractor
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.shared.parseLldbExpressionResult
import org.jetbrains.hexana.wasm.dwarf.WasmDwarfAccessor

/**
 * Uses parsed DWARF variable location metadata to resolve variable
 * values via targeted LLDB commands when the standard `frame variable`
 * command reports them as unavailable.
 *
 * The resolver is created once per debug session from the Wasm binary
 * bytes and caches the extracted DWARF subprogram information. When a
 * variable is unavailable, [resolveVariable] constructs LLDB commands
 * based on the DWARF location description:
 *
 * - `DW_OP_addr`: reads from a fixed address in Wasm linear memory
 * - `DW_OP_fbreg`: computes address from frame base + offset, then reads memory
 * - `DW_OP_WASM_location 0x00 (local)`: reads Wasm local via LLDB Python API
 * - `DW_OP_WASM_location 0x01/0x03 (global)`: reads Wasm global via LLDB Python API
 *
 * All LLDB commands are executed through the [LldbCommunicator].
 */
internal class WasmDwarfVariableResolver private constructor(
    private val subprograms: List<DwarfSubprogramInfo>,
    private val globalVariables: List<DwarfVariableInfo>,
) {

    /**
     * Attempts to resolve the value of an unavailable variable by
     * using DWARF location metadata to construct targeted LLDB
     * commands.
     *
     * @param communicator the LLDB communicator for sending commands
     * @param variable the unavailable variable to resolve
     * @param functionName the name of the current function (used to
     *        find the matching DWARF subprogram)
     * @return a new [WasmLocalVariable] with the resolved value, or
     *         the original variable if resolution fails
     */
    fun resolveVariable(
        communicator: LldbCommunicator,
        variable: WasmLocalVariable,
        functionName: String?,
    ): WasmLocalVariable {
        // First, try to find the variable in the matching subprogram
        val dwarfVar = findDwarfVariable(variable.name, functionName)
        if (dwarfVar != null) {
            val resolved = tryResolveFromDwarfVar(communicator, variable, dwarfVar, functionName)
            if (resolved != null) return resolved
        }

        // Fall back to global variables
        val globalVar = globalVariables.find { it.name == variable.name }
        if (globalVar != null) {
            val resolved = tryResolveFromDwarfVar(communicator, variable, globalVar, functionName)
            if (resolved != null) return resolved
        }

        // Last resort: try direct LLDB Python FindVariable lookup.
        // For Wasmtime JIT code, Cranelift transforms original DWARF
        // locations into native register-based locations that LLDB's
        // FindVariable can resolve even when our DWARF matching above
        // fails (e.g. due to function name mismatch or unsupported
        // location ops).
        val directResult = tryDirectVariableLookup(communicator, variable)
        if (directResult != null) return directResult

        return variable
    }

    /**
     * Tries to resolve a variable using its DWARF metadata. If the
     * variable has a location list, tries all entries (not just the
     * first) in case the single-location path fails. Location lists
     * are common in Go/TinyGo WASM binaries where variable storage
     * changes across the function's address range.
     */
    private fun tryResolveFromDwarfVar(
        communicator: LldbCommunicator,
        variable: WasmLocalVariable,
        dwarfVar: DwarfVariableInfo,
        functionName: String?,
    ): WasmLocalVariable? {
        // Try the primary location first (works for inline expressions
        // and is the first entry for location lists)
        val resolved = tryResolveFromLocation(communicator, variable, dwarfVar, functionName)
        if (resolved != null) return resolved

        // For location lists, try remaining entries — the variable
        // may be stored differently at different PC addresses
        if (dwarfVar.locationList.size > 1) {
            for (entry in dwarfVar.locationList.drop(1)) {
                val entryResolved = tryResolveFromLocationDesc(
                    communicator, variable, entry.location, functionName,
                )
                if (entryResolved != null) return entryResolved
            }
        }
        return null
    }

    /**
     * Returns a human-readable description of the DWARF location for
     * the given variable, or `null` if no DWARF metadata is found.
     * This is used to enrich the display message for unavailable
     * variables.
     */
    fun describeVariableLocation(
        variableName: String,
        functionName: String?,
    ): String? {
        val dwarfVar = findDwarfVariable(variableName, functionName)
            ?: globalVariables.find { it.name == variableName }
            ?: return null
        // For location lists, describe all entries for a comprehensive view
        if (dwarfVar.locationList.isNotEmpty()) {
            return dwarfVar.locationList.joinToString("; ") { entry ->
                "[0x${entry.startAddress.toString(16)}..0x${entry.endAddress.toString(16)}): ${entry.location.describe()}"
            }
        }
        return dwarfVar.location?.describe()
    }

    /**
     * Returns `true` when we have any DWARF location metadata for the
     * given variable (either an inline expression or a location list).
     */
    fun hasLocationMetadata(
        variableName: String,
        functionName: String?,
    ): Boolean {
        val dwarfVar = findDwarfVariable(variableName, functionName)
            ?: globalVariables.find { it.name == variableName }
            ?: return false
        return dwarfVar.location != null || dwarfVar.locationList.isNotEmpty()
    }

    /**
     * Builds a mapping from Wasm local indices to DWARF variable names
     * for the given function. This is used by the CDP debugger to
     * resolve internal names like `$var0` to their source-level names.
     *
     * @param functionName the name of the current function
     * @return a map from wasm local index to the DWARF variable name
     */
    /**
     * Returns `true` when the given function name matches a DWARF
     * subprogram entry (by name only, no offset fallback).
     */
    fun hasDwarfSubprogram(functionName: String?): Boolean {
        if (functionName == null) return false
        val normalizedName = functionName.removePrefix("$")
        // Use only exact name/linkageName matching — no prefix fallback.
        // Prefix matching would incorrectly match wasm-bindgen wrappers
        // whose mangled names contain the real function's name as a
        // substring (e.g. `__wasm_bindgen_generated_add` starts with
        // a subprogram's linkageName).
        return subprograms.any { sp ->
            sp.name == functionName || sp.name == normalizedName ||
                sp.linkageName == functionName || sp.linkageName == normalizedName
        }
    }

    /**
     * Builds a mapping from Wasm local indices to DWARF variable names
     * using only the Wasm byte offset (no function name). Used during
     * breakpoint registration when the function name is not yet known
     * but the DWARF offset is available.
     *
     * Falls back to empty map when no subprogram covers the offset.
     */
    fun buildWasmLocalToNameMapByOffset(wasmByteOffset: Int): Map<Long, String> {
        val matchingSubprograms = findMatchingSubprograms(null, wasmByteOffset)
        if (matchingSubprograms.isEmpty()) return emptyMap()
        // Delegate to buildWasmLocalToNameMap with an explicit offset so
        // that it uses the same variable-assignment logic (WasmLocal ops,
        // then positional fallback).  Pass null for functionName so that
        // the name-only path is skipped and offset matching is used.
        // Rather than duplicating the logic, find the function name from
        // the matched subprogram and delegate.
        val sp = matchingSubprograms.first()
        val nameForLookup = sp.name ?: sp.linkageName
        return buildWasmLocalToNameMap(nameForLookup, wasmByteOffset)
    }

    fun buildWasmLocalToNameMap(functionName: String?, wasmByteOffset: Int? = null): Map<Long, String> {
        // Use name-only matching first. The offset-based fallback in
        // findMatchingSubprograms can match a *different* function whose
        // address range covers the current offset (e.g. the real `add`
        // function matched when we're paused in a wasm-bindgen wrapper).
        // Local indices are function-specific, so using another function's
        // DWARF locals produces garbage values. Only fall back to offset
        // matching when name matching succeeds — i.e. we trust the offset
        // fallback only for findDwarfVariable (LLDB path), not here.
        val matchingSubprograms = findMatchingSubprogramsByName(functionName)
        if (matchingSubprograms.isEmpty()) {
            LOG.debug("buildWasmLocalToNameMap: no matching subprograms for function='$functionName' offset=$wasmByteOffset")
            return emptyMap()
        }
        val result = mutableMapOf<Long, String>()
        for (sp in matchingSubprograms) {
            // First pass: collect variables that have explicit WasmLocal ops
            for (v in sp.variables) {
                val name = v.name ?: continue
                val loc = effectiveLocation(v) ?: continue
                val primaryOp = loc.operations.firstOrNull() ?: continue
                if (primaryOp is DwarfExprOp.WasmLocal) {
                    result.putIfAbsent(primaryOp.index, name)
                }
            }
            // Second pass: if no WasmLocal ops were found (e.g. Rust DWARF
            // uses DW_OP_fbreg for parameters), assign indices positionally.
            // In wasm, parameters occupy locals 0..N-1 in declaration order,
            // followed by local variables.
            if (result.isEmpty()) {
                var index = 0L
                // Parameters first, in declaration order
                for (v in sp.variables.filter { it.isParameter }) {
                    val name = v.name ?: continue
                    result.putIfAbsent(index, name)
                    index++
                }
                // Then non-parameter locals
                for (v in sp.variables.filter { !it.isParameter }) {
                    val name = v.name ?: continue
                    result.putIfAbsent(index, name)
                    index++
                }
            }
        }
        LOG.debug("buildWasmLocalToNameMap: function='$functionName' offset=$wasmByteOffset -> $result")
        return result
    }

    /**
     * Finds subprograms matching by name only. Used by
     * [buildWasmLocalToNameMap] where local indices are
     * function-specific and offset-based fallback would match
     * the wrong function.
     */
    private fun findMatchingSubprogramsByName(
        functionName: String?,
    ): List<DwarfSubprogramInfo> {
        if (functionName == null) return emptyList()
        val normalizedName = functionName.removePrefix("$")
        // Prefer exact name matches (DW_AT_name) over linkage name
        // matches. V8 reports wasm functions by their export name,
        // which matches the wrapper's linkageName but the real
        // function's name — we want the real function first.
        val byName = subprograms.filter { sp ->
            sp.name == functionName || sp.name == normalizedName
        }
        if (byName.isNotEmpty()) return byName
        // Then try linkage name exact matches
        val byLinkage = subprograms.filter { sp ->
            sp.linkageName == functionName || sp.linkageName == normalizedName
        }
        if (byLinkage.isNotEmpty()) return byLinkage
        // Fall back to prefix matching (for mangled/demangled names)
        return subprograms.filter { sp ->
            sp.name?.let { functionName.startsWith(it) } == true ||
                sp.name?.let { normalizedName.startsWith(it) } == true ||
                sp.linkageName?.let { normalizedName.startsWith(it) } == true ||
                sp.linkageName?.let { functionName.startsWith(it) } == true
        }
    }

    /**
     * Finds subprograms matching by name first, falling back to
     * address range matching using the wasm byte offset when name
     * matching fails (common with mangled wasm-bindgen names).
     */
    private fun findMatchingSubprograms(
        functionName: String?,
        wasmByteOffset: Int? = null,
    ): List<DwarfSubprogramInfo> {
        val byName = findMatchingSubprogramsByName(functionName)
        if (byName.isNotEmpty()) return byName
        // Fall back to address range matching
        if (wasmByteOffset != null) {
            val byOffset = subprograms.filter { sp ->
                val low = sp.lowPc ?: return@filter false
                val high = sp.highPc ?: return@filter false
                wasmByteOffset in low until high
            }
            if (byOffset.isNotEmpty()) return byOffset
        }
        return emptyList()
    }

    /**
     * Returns the effective location for a variable, checking both
     * the inline location and the first location list entry.
     */
    private fun effectiveLocation(v: DwarfVariableInfo): DwarfLocationDescription? {
        v.location?.let { return it }
        return v.locationList.firstOrNull()?.location
    }

    /**
     * Returns `true` when the variable is present in DWARF metadata
     * (as a local/parameter of the given function, or as a global),
     * regardless of whether it has location information. This
     * distinguishes "variable exists but toolchain omitted location"
     * from "variable not found in DWARF at all".
     */
    fun isKnownInDwarf(
        variableName: String,
        functionName: String?,
    ): Boolean {
        val dwarfVar = findDwarfVariable(variableName, functionName)
            ?: globalVariables.find { it.name == variableName }
        return dwarfVar != null
    }

    private fun findDwarfVariable(
        variableName: String,
        functionName: String?,
    ): DwarfVariableInfo? {
        if (functionName == null) return null
        // V8 prepends '$' to wasm function names; strip it for matching
        val normalizedName = functionName.removePrefix("$")
        // Match by function name — try both name and linkage name
        val matchingSubprograms = subprograms.filter { sp ->
            sp.name == functionName ||
                sp.linkageName == functionName ||
                sp.name == normalizedName ||
                sp.linkageName == normalizedName ||
                // LLDB may report demangled names with arguments stripped
                sp.name?.let { functionName.startsWith(it) } == true ||
                sp.name?.let { normalizedName.startsWith(it) } == true
        }
        for (sp in matchingSubprograms) {
            val v = sp.variables.find { it.name == variableName }
            if (v != null) return v
        }
        return null
    }

    private fun tryResolveFromLocation(
        communicator: LldbCommunicator,
        variable: WasmLocalVariable,
        dwarfVar: DwarfVariableInfo,
        functionName: String?,
    ): WasmLocalVariable? {
        val location = dwarfVar.location ?: return null
        return tryResolveFromLocationDesc(communicator, variable, location, functionName)
    }

    private fun tryResolveFromLocationDesc(
        communicator: LldbCommunicator,
        variable: WasmLocalVariable,
        location: DwarfLocationDescription,
        functionName: String?,
    ): WasmLocalVariable? {
        if (location.isEmpty) return null

        return try {
            when (val primaryOp = location.operations.first()) {
                is DwarfExprOp.Address ->
                    resolveViaAddress(communicator, variable, primaryOp)

                is DwarfExprOp.FrameBaseOffset ->
                    resolveViaFrameBaseOffset(communicator, variable, primaryOp, functionName)

                is DwarfExprOp.WasmLocal ->
                    resolveViaWasmLocal(communicator, variable, primaryOp, location)

                is DwarfExprOp.WasmGlobal ->
                    resolveViaWasmGlobal(communicator, variable, primaryOp, location)

                is DwarfExprOp.WasmGlobalU32 ->
                    resolveViaWasmGlobal(communicator, variable, primaryOp, location)

                else -> {
                    LOG.debug("Unsupported DWARF location op for '${variable.name}': $primaryOp")
                    null
                }
            }
        } catch (e: Exception) {
            LOG.debug("DWARF-based resolution failed for '${variable.name}': ${e.message}")
            null
        }
    }

    /**
     * Resolves a variable stored at a fixed address in Wasm linear
     * memory (`DW_OP_addr`). Uses LLDB's `memory read` command to
     * fetch the value at the given address offset from the Wasm
     * memory base.
     */
    private fun resolveViaAddress(
        communicator: LldbCommunicator,
        variable: WasmLocalVariable,
        addressOp: DwarfExprOp.Address,
    ): WasmLocalVariable? {
        val addr = addressOp.address
        // Try reading via the expression evaluator with the
        // specific address. LLDB in Wasmtime JIT mode may be able to
        // resolve the address through __vmctx.
        val exprLines = communicator.sendCommandAndCollectOutput(
            "expression -- *(${variable.typeName ?: "int"} *)($addr)",
            timeoutMs = 3_000,
        )
        val exprJoined = exprLines.joinToString("\n").trim()
        if (exprJoined.isNotEmpty() && !exprJoined.startsWith("error:") &&
            !exprJoined.contains("unavailable")
        ) {
            val result = parseLldbExpressionResult(exprJoined)
            if (!result.displayValue.contains("unavailable") &&
                !result.displayValue.contains("variable not available")
            ) {
                return WasmLocalVariable.withResolvedValue(
                    variable,
                    result.typeName ?: variable.typeName,
                    result.displayValue,
                )
            }
        }

        return null
    }

    /**
     * Resolves a variable located at an offset from the frame base
     * (`DW_OP_fbreg`). The frame base itself is retrieved from the
     * subprogram's `DW_AT_frame_base` attribute, and the variable's
     * address is computed as frame_base + offset.
     *
     * In Wasmtime JIT mode, Cranelift transforms the original DWARF
     * `DW_AT_frame_base` (e.g. `DW_OP_WASM_location 0x0 local#N`)
     * into a native register location. The transformed frame base is
     * accessible through LLDB's standard frame machinery. We try
     * multiple strategies:
     *
     * 1. Direct variable lookup via LLDB Python (`FindVariable`) —
     *    this uses the JIT-transformed DWARF and is the most reliable
     *    path when Cranelift's transformation succeeded.
     * 2. For `WasmLocal` frame bases: use `__stack_pointer` as the
     *    frame base value (the function prologue synchronizes the
     *    local and the global), then compute
     *    `memory_base + frame_base_value + offset` to read the
     *    variable from Wasm linear memory.
     * 3. For `WasmGlobal` frame bases: read `__stack_pointer` via
     *    the expression evaluator, then compute the memory address.
     */
    private fun resolveViaFrameBaseOffset(
        communicator: LldbCommunicator,
        variable: WasmLocalVariable,
        fbreg: DwarfExprOp.FrameBaseOffset,
        functionName: String?,
    ): WasmLocalVariable? {
        if (functionName == null) return null

        // Find the subprogram to get the frame base expression
        val subprogram = subprograms.find { sp ->
            sp.name == functionName ||
                sp.linkageName == functionName ||
                sp.name?.let { functionName.startsWith(it) } == true
        } ?: return null

        val frameBase = subprogram.frameBase ?: return null
        val frameBaseOp = frameBase.operations.firstOrNull() ?: return null

        // Strategy 1: Try direct variable lookup first — this uses
        // Cranelift's JIT-transformed DWARF which maps wasm locations
        // to native registers. When the transformation succeeds, LLDB
        // can resolve the variable directly by name.
        val directResult = tryDirectVariableLookup(communicator, variable)
        if (directResult != null) return directResult

        // Strategy 2: Try to manually compute the address in Wasm
        // linear memory using the DWARF frame base + fbreg offset.
        // This requires reading the frame base value and the linear
        // memory base address from __vmctx->memory.
        val offset = fbreg.offset
        val typeName = variable.typeName ?: "int"

        // For WasmLocal frame bases, GetFP() returns the *native*
        // frame pointer — NOT the wasm local value. Reading at
        // native_fp + offset accesses random native stack data and
        // returns garbage that looks valid (e.g. value=1 instead of
        // the real parameter value). We must not attempt that path.
        //
        // For WasmGlobal frame bases (e.g. __stack_pointer), the
        // expression evaluator returns the actual wasm stack pointer
        // value which is a linear-memory offset — safe to use with
        // __vmctx->memory.
        when (frameBaseOp) {
            is DwarfExprOp.WasmLocal -> {
                // Wasm local frame base: DW_OP_WASM_location local#N
                // with DW_OP_stack_value. The frame base equals the
                // value of wasm local #N, which is the stack pointer
                // local. In LLVM-compiled wasm, the function prologue
                // loads __stack_pointer global, subtracts the frame
                // size, stores the result back into both the local and
                // the global. After the prologue, __stack_pointer ==
                // local#N == frame_base. We can therefore use the
                // __stack_pointer global (readable via LLDB expression
                // evaluator) as the frame base value.
            }
            is DwarfExprOp.WasmGlobal, is DwarfExprOp.WasmGlobalU32 -> {}
            else -> return null
        }

        val frameBaseExpr = "lldb.frame.EvaluateExpression('__stack_pointer').unsigned"

        val script = buildString {
            append("script ")
            append("import sys; ")
            append("fb = $frameBaseExpr; ")
            append("addr = fb + ($offset); ")
            append("e = lldb.frame.EvaluateExpression('*($typeName *)' + str(addr)); ")
            append("r = 'type=' + str(e.type) + ' value=' + str(e.value) ")
            append("if e.IsValid() and e.value and not e.GetError().Fail() else None; ")
            // If direct address failed, try via __vmctx->memory base
            append("mb = lldb.frame.EvaluateExpression('(unsigned long long)__vmctx->memory').unsigned if r is None else 0; ")
            append("e2 = lldb.frame.EvaluateExpression('*($typeName *)' + str(mb + fb + ($offset))) if r is None and mb else None; ")
            append("r2 = 'type=' + str(e2.type) + ' value=' + str(e2.value) ")
            append("if e2 is not None and e2.IsValid() and e2.value and not e2.GetError().Fail() else None; ")
            append("print(r or r2 or 'unavailable')")
        }

        val lines = communicator.sendCommandAndCollectOutput(script, timeoutMs = 5_000)
        val joined = lines.joinToString(" ").trim()

        if (joined.contains("unavailable") || joined.isEmpty()) {
            // All strategies failed. Return a DWARF location hint so
            // the user knows where the variable is stored.
            return WasmLocalVariable.withDwarfLocationHint(
                variable,
                "fbreg+$offset (frame_base=${frameBase.describe()})",
                "DW_OP_fbreg $offset — ${frameBase.describe()}",
            )
        }

        val typeMatch = TYPE_VALUE_PATTERN.find(joined)
        if (typeMatch != null) {
            val resolvedType = typeMatch.groupValues[1]
            val resolvedValue = typeMatch.groupValues[2]
            return WasmLocalVariable.withResolvedValue(
                variable,
                resolvedType,
                resolvedValue,
            )
        }
        return null
    }

    /**
     * Attempts direct variable lookup via LLDB's Python `FindVariable`
     * API, which uses the JIT-transformed DWARF produced by Cranelift.
     * Returns a resolved variable or `null` if lookup fails.
     */
    private fun tryDirectVariableLookup(
        communicator: LldbCommunicator,
        variable: WasmLocalVariable,
    ): WasmLocalVariable? {
        val script = buildString {
            append("script ")
            append("v = lldb.frame.FindVariable('${variable.name}'); ")
            append("print('type=' + str(v.type) + ' value=' + str(v.value) ")
            append("if v.IsValid() and v.value and 'unavailable' not in str(v.value) else 'unavailable')")
        }

        val lines = communicator.sendCommandAndCollectOutput(script, timeoutMs = 3_000)
        val joined = lines.joinToString(" ").trim()

        if (joined.contains("unavailable") || joined.isEmpty()) return null

        val typeMatch = TYPE_VALUE_PATTERN.find(joined)
        if (typeMatch != null) {
            return WasmLocalVariable.withResolvedValue(
                variable,
                typeMatch.groupValues[1],
                typeMatch.groupValues[2],
            )
        }
        return null
    }

    /**
     * Resolves a variable stored in a Wasm local
     * (`DW_OP_WASM_location 0x00`). In Wasmtime JIT, LLDB
     * transforms these into native register locations — this method
     * tries to read the value via LLDB's Python scripting API.
     */
    private fun resolveViaWasmLocal(
        communicator: LldbCommunicator,
        variable: WasmLocalVariable,
        localOp: DwarfExprOp.WasmLocal,
        location: DwarfLocationDescription,
    ): WasmLocalVariable? {
        val localIdx = localOp.index

        // In Wasmtime JIT, Wasm locals are mapped to native registers
        // by Cranelift. LLDB should be able to access them through the
        // standard variable lookup if the JIT DWARF transformation
        // succeeded. Try the Python SBFrame API for a more direct path.
        val script = buildString {
            append("script ")
            append("v = lldb.frame.FindVariable('${variable.name}'); ")
            append("print('type=' + str(v.type) + ' value=' + str(v.value) ")
            append("if v.IsValid() and v.value else 'unavailable')")
        }

        val lines = communicator.sendCommandAndCollectOutput(script, timeoutMs = 3_000)
        val joined = lines.joinToString(" ").trim()

        if (joined.contains("unavailable") || joined.isEmpty()) {
            // Direct variable lookup failed. Annotate with DWARF location
            // info so the user knows where the variable should be.
            val locationDesc = location.describe()
            return WasmLocalVariable.withDwarfLocationHint(
                variable,
                "wasm local[$localIdx]",
                locationDesc,
            )
        }

        val typeMatch = TYPE_VALUE_PATTERN.find(joined)
        if (typeMatch != null) {
            return WasmLocalVariable.withResolvedValue(
                variable,
                typeMatch.groupValues[1],
                typeMatch.groupValues[2],
            )
        }
        return null
    }

    /**
     * Resolves a variable stored in a Wasm global
     * (`DW_OP_WASM_location 0x01` or `0x03`). Similar to local
     * resolution, this tries the LLDB Python API first.
     */
    private fun resolveViaWasmGlobal(
        communicator: LldbCommunicator,
        variable: WasmLocalVariable,
        globalOp: DwarfExprOp,
        location: DwarfLocationDescription,
    ): WasmLocalVariable? {
        val globalIdx = when (globalOp) {
            is DwarfExprOp.WasmGlobal -> globalOp.index
            is DwarfExprOp.WasmGlobalU32 -> globalOp.index
            else -> return null
        }

        // Try reading via LLDB Python API first
        val script = buildString {
            append("script ")
            append("v = lldb.frame.FindVariable('${variable.name}'); ")
            append("print('type=' + str(v.type) + ' value=' + str(v.value) ")
            append("if v.IsValid() and v.value else 'unavailable')")
        }

        val lines = communicator.sendCommandAndCollectOutput(script, timeoutMs = 3_000)
        val joined = lines.joinToString(" ").trim()

        if (joined.contains("unavailable") || joined.isEmpty()) {
            val locationDesc = location.describe()
            return WasmLocalVariable.withDwarfLocationHint(
                variable,
                "wasm global[$globalIdx]",
                locationDesc,
            )
        }

        val typeMatch = TYPE_VALUE_PATTERN.find(joined)
        if (typeMatch != null) {
            return WasmLocalVariable.withResolvedValue(
                variable,
                typeMatch.groupValues[1],
                typeMatch.groupValues[2],
            )
        }
        return null
    }

    companion object {
        private val LOG = Logger.getInstance(WasmDwarfVariableResolver::class.java)

        /** Pattern to extract type and value from LLDB Python output: `type=<type> value=<value>` */
        internal val TYPE_VALUE_PATTERN = """type=(\S+)\s+value=(.+)""".toRegex()

        /**
         * Creates a resolver from raw Wasm binary bytes by parsing
         * the DWARF debug info sections.
         *
         * A component-model binary carries no DWARF of its own: the debug info sits
         * in the core modules it embeds, so those are tried in `module` section order
         * and the first one carrying DWARF wins. That is the module the app code is
         * compiled from (adapters ship stripped, and [WasmDwarfInjector] injects into
         * the first embedded module for the same reason); variable resolution for a
         * stop in a *different* embedded module is not covered.
         *
         * @return a [WasmDwarfVariableResolver], or `null` if the
         *         binary has no DWARF info or parsing fails
         */
        fun create(wasmBytes: ByteArray): WasmDwarfVariableResolver? =
            createFromCoreModule(wasmBytes)
                ?: WasmDwarfUtil.embeddedCoreModules(wasmBytes).firstNotNullOfOrNull { createFromCoreModule(it) }

        /** [create] for bytes that must already be a core module. */
        private fun createFromCoreModule(wasmBytes: ByteArray): WasmDwarfVariableResolver? {
            return try {
                val buffer = wasmBytes.toCommonByteBuffer()
                val parseResult = WasmParser().parse(buffer) as? WasmParseSuccess
                    ?: return null
                val module = parseResult.wasmModule as? BaseModule ?: return null
                val accessor = WasmDwarfAccessor(module)

                // Check that DWARF info is present before extracting
                if (accessor.debugInfoSection() == null) return null

                val extractor = DwarfVariableInfoExtractor(accessor)
                val subprograms = with(extractor) { buffer.extractSubprograms() }
                val globalVariables = with(extractor) { buffer.extractGlobalVariables() }

                if (subprograms.isEmpty() && globalVariables.isEmpty()) return null

                WasmDwarfVariableResolver(subprograms, globalVariables)
            } catch (e: Exception) {
                LOG.warn("Failed to parse DWARF variable info from Wasm binary", e)
                null
            }
        }
    }
}
