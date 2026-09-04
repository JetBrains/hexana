package org.jetbrains.hexana.wasm.analysis

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.opcodes.*
import org.jetbrains.hexana.wasm.opcodes.legacy.eh.LegacyEH
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.opcodes.table.Element
import org.jetbrains.hexana.wasm.opcodes.table.Table
import org.jetbrains.hexana.wasm.sections.*

/**
 * Detects which WebAssembly proposals are used by a parsed [WasmModule].
 *
 * The detector inspects:
 * - **Type records** in the types section (struct/array/rec types → GC, multi-value returns).
 * - **Opcodes** in code bodies (sign-extension, saturating truncation, tail call, typed function
 *   references, GC instructions, exception handling, SIMD, relaxed SIMD, atomics, bulk memory,
 *   reference types).
 * - **Memory limits** (i64-indexed → memory64, shared flag → threads).
 * - **Import records** (mutable globals, tag imports, memory imports with i64/shared limits).
 * - **Section presence** (tag/exception section → exception handling, data count section → bulk memory).
 * - **Multiple memories** (more than one memory definition + memory imports).
 * - **Initializer expressions** (a non-MVP instruction in a global, table, data-offset or
 *   element expression → extended constant expressions).
 * - **Custom sections** (`metadata.code.branch_hint` → branch hinting; a name-section
 *   subsection beyond module/function/locals → extended name section).
 * - **Import module names** (`wasm:js-string` → JS string builtins).
 * - **`exact` heap types** in type records or code locals → custom descriptors.
 *
 * [WasmProposal.ComponentModel] is intentionally not produced here: this detector takes a
 * core [WasmModule], so a component binary never reaches it. Callers that hold a component
 * report it themselves.
 */
object WasmProposalDetector {

    /** Custom section carrying branch hints; see the branch-hinting proposal. */
    private const val BRANCH_HINT_SECTION_NAME = "metadata.code.branch_hint"

    private const val NAME_SECTION_NAME = "name"

    /** Import modules defined by the JS-string-builtins proposal. */
    private val JS_BUILTIN_IMPORT_MODULES = setOf("wasm:js-string", "wasm:text-encoder", "wasm:text-decoder")

    fun detect(module: WasmModule, buffer: CommonByteBuffer): Set<WasmProposal> {
        val result = mutableSetOf<WasmProposal>()
        detectFromTypes(module, buffer, result)
        detectFromMemories(module, buffer, result)
        detectFromImports(module, buffer, result)
        detectFromExports(module, buffer, result)
        detectFromGlobals(module, buffer, result)
        detectFromTables(module, buffer, result)
        detectFromData(module, buffer, result)
        detectFromElements(module, buffer, result)
        detectFromSections(module, result)
        detectFromCustomSections(module, buffer, result)
        detectFromCode(module, buffer, result)
        return result
    }

    private fun detectFromTypes(module: WasmModule, buffer: CommonByteBuffer, result: MutableSet<WasmProposal>) {
        val typesSection = module.typesSection ?: return
        val types = with(typesSection) { buffer.parseTypes() }
        for (type in types) {
            detectFromTypeRecord(type, result)
        }
    }

    private fun detectFromTypeRecord(type: WasmTypeRecord, result: MutableSet<WasmProposal>) {
        when (type) {
            is WasmRecursiveTypeRecord -> {
                result.add(WasmProposal.GarbageCollection)
                type.entries.forEach { detectFromTypeRecord(it, result) }
            }
            is WasmArrayTypeRecord -> {
                result.add(WasmProposal.GarbageCollection)
                detectFromValueType(type.fieldType.storageType, result)
            }
            is WasmStructTypeRecord -> {
                result.add(WasmProposal.GarbageCollection)
                type.fields.forEach { detectFromValueType(it.storageType, result) }
            }
            is WasmSectionFunctionTypeRecord -> {
                if (type.retTypes.size > 1) {
                    result.add(WasmProposal.MultiValue)
                }
                type.params.forEach { detectFromValueType(it, result) }
                type.retTypes.forEach { detectFromValueType(it, result) }
            }
        }
    }

    /**
     * Flags the custom-descriptors proposal on an `exact x` heap type (`0x62 x:u32`), the
     * one part of that proposal the parser models as a first-class type. The `descriptor`
     * (0x4D) and `describes` (0x4C) subtype clauses are consumed for alignment by
     * [org.jetbrains.hexana.wasm.sections.StandardTypesParser] but not recorded, so a
     * binary that uses only those clauses is not detected.
     */
    private fun detectFromValueType(type: WasmValueType<*>, result: MutableSet<WasmProposal>) {
        val refType = type as? WasmRefType ?: return
        if (refType.code.heapType is WasmExactHeapType) {
            result.add(WasmProposal.CustomDescriptors)
        }
    }

    private fun detectFromMemories(module: WasmModule, buffer: CommonByteBuffer, result: MutableSet<WasmProposal>) {
        var memoryCount = 0

        val memorySection = module.memorySection
        if (memorySection != null) {
            val memories = with(memorySection) { buffer.scan() }
            memoryCount += memories.size
            for (mem in memories) {
                detectFromLimits(mem.limits, result)
            }
        }

        // Count memory imports for multiple-memories detection and check their limits
        val importSection = module.importSection
        if (importSection != null) {
            val imports = with(importSection) { buffer.getImportRecords() }
            for (imp in imports) {
                val memType = imp.type as? WasmMemType ?: continue
                memoryCount++
                detectFromLimits(memType.limits, result)
            }
        }

        if (memoryCount > 1) {
            result.add(WasmProposal.MultipleMemories)
        }
    }

    private fun detectFromLimits(limits: WasmLimits<*>, result: MutableSet<WasmProposal>) {
        if (limits.pageSizeLog2 != null) {
            result.add(WasmProposal.CustomPageSizes)
        }
        when (limits) {
            is WasmOpenI64Limits -> result.add(WasmProposal.Memory64)
            is WasmFixedI64Limits -> result.add(WasmProposal.Memory64)
            is WasmFixedI32Limits -> {
                if (limits.shared) result.add(WasmProposal.Threads)
            }
            is WasmOpenI32Limits -> { /* MVP */ }
        }
    }

    private fun detectFromImports(module: WasmModule, buffer: CommonByteBuffer, result: MutableSet<WasmProposal>) {
        val importSection = module.importSection ?: return
        val imports = with(importSection) { buffer.getImportRecords() }
        for (import in imports) {
            if (import.prefix in JS_BUILTIN_IMPORT_MODULES) {
                result.add(WasmProposal.JsStringBuiltins)
            }
            when (val type = import.type) {
                is WasmGlobalType -> {
                    if (type.mutable) result.add(WasmProposal.MutableGlobals)
                }
                is WasmTagType -> result.add(WasmProposal.ExceptionHandling)
                is WasmMemType -> detectFromLimits(type.limits, result)
                is WasmTableExternalType -> detectFromLimits(type.limits, result)
                is WasmTypeIndexExternalType -> { /* function import — MVP */ }
            }
        }
    }

    private fun detectFromExports(module: WasmModule, buffer: CommonByteBuffer, result: MutableSet<WasmProposal>) {
        val exportSection = module.exportSection ?: return
        val exports = with(exportSection) { buffer.scan() }
        for (export in exports) {
            if (export is WasmTagExportRecordEntry) {
                result.add(WasmProposal.ExceptionHandling)
            }
        }
        // Mutable global exports: we need to check global types.
        // Global exports reference global indices — if we have global section with mutable entries
        // that are exported, that signals the mutable-globals proposal. We detect this simpler:
        // any mutable global in the module that is exported signals the proposal.
        val globalExportIndices = exports.filterIsInstance<WasmGlobalExportRecordEntry>().map { it.index.toInt() }.toSet()
        if (globalExportIndices.isNotEmpty()) {
            detectMutableGlobalExports(module, buffer, result, globalExportIndices)
        }
    }

    private fun detectMutableGlobalExports(
        module: WasmModule,
        buffer: CommonByteBuffer,
        result: MutableSet<WasmProposal>,
        exportedIndices: Set<Int>
    ) {
        // Global index space: imported globals first, then defined globals
        val importSection = module.importSection
        val importedGlobals = if (importSection != null) {
            with(importSection) { buffer.getImportRecords() }
                .filter { it.type is WasmGlobalType }
        } else {
            emptyList()
        }

        for ((i, imp) in importedGlobals.withIndex()) {
            if (i in exportedIndices && (imp.type as WasmGlobalType).mutable) {
                result.add(WasmProposal.MutableGlobals)
                return
            }
        }

        val globalSection = module.globalSection ?: return
        val globals = with(globalSection) { buffer.scan() }
        val importedCount = importedGlobals.size
        for ((i, global) in globals.withIndex()) {
            if ((importedCount + i) in exportedIndices && global.type.mutable) {
                result.add(WasmProposal.MutableGlobals)
                return
            }
        }
    }

    private fun detectFromGlobals(module: WasmModule, buffer: CommonByteBuffer, result: MutableSet<WasmProposal>) {
        val globalSection = module.globalSection ?: return
        val globals = with(globalSection) { buffer.scan() }
        for (global in globals) {
            detectFromConstExpression(global.expression, result)
        }
    }

    private fun detectFromTables(module: WasmModule, buffer: CommonByteBuffer, result: MutableSet<WasmProposal>) {
        val tableSection = module.tableSection ?: return
        val tables = with(tableSection) { buffer.scanTable() }
        for (table in tables) {
            detectFromLimits(table.tableType.limits, result)
            table.expression?.let { detectFromConstExpression(it, result) }
        }
    }

    private fun detectFromData(module: WasmModule, buffer: CommonByteBuffer, result: MutableSet<WasmProposal>) {
        val dataSection = module.dataSection ?: return
        val records = with(dataSection) { buffer.scan() }
        for (record in records) {
            when (record) {
                is WasmDataRecordActive -> detectFromConstExpression(record.expression, result)
                is WasmDataRecordActiveWithMemoryIndex ->
                    detectFromConstExpression(record.expression, result)
                else -> { /* passive segment: no offset expression */ }
            }
        }
    }

    private fun detectFromElements(module: WasmModule, buffer: CommonByteBuffer, result: MutableSet<WasmProposal>) {
        val elementSection = module.elementSection ?: return
        val segments = with(elementSection) { buffer.scanElements() }
        for (segment in segments) {
            when (segment) {
                is FuncIdxWasmElementSegment.ActiveDefault ->
                    detectFromConstExpression(segment.offsetExpr, result)
                is FuncIdxWasmElementSegment.ActiveExplicit ->
                    detectFromConstExpression(segment.offsetExpr, result)
                is ExprWasmElementSegment.ActiveDefault -> {
                    detectFromConstExpression(segment.offsetExpr, result)
                    segment.exprs.forEach { detectFromConstExpression(it, result) }
                }
                is ExprWasmElementSegment.ActiveExplicit -> {
                    detectFromConstExpression(segment.offsetExpr, result)
                    segment.exprs.forEach { detectFromConstExpression(it, result) }
                }
                is ExprWasmElementSegment -> segment.exprs.forEach { detectFromConstExpression(it, result) }
                is FuncIdxWasmElementSegment -> { /* passive/declare: func indices only */ }
            }
        }
    }

    private fun detectFromSections(module: WasmModule, result: MutableSet<WasmProposal>) {
        if (module.exceptionSection != null) {
            result.add(WasmProposal.ExceptionHandling)
        }
        if (module.dataCountSection != null) {
            result.add(WasmProposal.BulkMemoryOperations)
        }
    }

    /**
     * Branch hinting is flagged on the presence of its custom section alone; the hint
     * payload (function index, byte offset, likely/unlikely) is not decoded.
     *
     * The extended name section is flagged when the name section carries any subsection
     * beyond the three the core spec defines (module, function, locals) — ids 3..11, which
     * the parser already decodes.
     */
    private fun detectFromCustomSections(
        module: WasmModule,
        buffer: CommonByteBuffer,
        result: MutableSet<WasmProposal>,
    ) {
        if (module.customSections.any { it.rawName == BRANCH_HINT_SECTION_NAME }) {
            result.add(WasmProposal.BranchHinting)
        }

        val nameSection = module.getCustomSectionByName(NAME_SECTION_NAME) ?: return
        // A truncated or non-conforming name section must not lose the other detections.
        val names = try {
            with(nameSection) { buffer.parseAsNameSection() }
        } catch (_: Exception) {
            return
        }
        val hasExtendedSubsection = names.labelsSection != null ||
            names.typesSection != null ||
            names.tablesNameSection != null ||
            names.memoriesNameSection != null ||
            names.globalsNameSection != null ||
            names.elemsNameSection != null ||
            names.dataSegmentsNameSection != null ||
            names.fieldsNameSection != null ||
            names.tagsNameSection != null
        if (hasExtendedSubsection) {
            result.add(WasmProposal.ExtendedNameSection)
        }
    }

    private fun detectFromCode(module: WasmModule, buffer: CommonByteBuffer, result: MutableSet<WasmProposal>) {
        val codeSection = module.codeSection ?: return
        val codeRecords = with(codeSection) { buffer.scan() }
        for (record in codeRecords) {
            record.locals.forEach { detectFromValueType(it.type, result) }
            detectFromOpcodes(record.opCodes, result)
        }
    }

    /**
     * Walks an initializer expression: everything [detectFromOpcodes] finds, plus the
     * extended-constant-expressions proposal when the expression contains an instruction
     * outside the MVP const-expression set.
     */
    private fun detectFromConstExpression(opcodes: List<OpCode>, result: MutableSet<WasmProposal>) {
        detectFromOpcodes(opcodes, result)
        if (opcodes.any { !isMvpConstExpressionOpcode(it) }) {
            result.add(WasmProposal.ExtendedConst)
        }
    }

    /**
     * The instructions an MVP (and reference-types) constant expression may contain.
     * Anything else in an initializer means the extended-const proposal.
     */
    private fun isMvpConstExpressionOpcode(opcode: OpCode): Boolean {
        return when (opcode) {
            is I32.Const,
            is I64.Const,
            is F32.Const,
            is F64.Const,
            is OpCodes.Global.Get,
            is OpCodes.Ref.Null,
            is OpCodes.Ref.Func,
            OpCodes.Flow.Terminal -> true
            else -> false
        }
    }

    private fun detectFromOpcodes(opcodes: List<OpCode>, result: MutableSet<WasmProposal>) {
        for (opcode in opcodes) {
            detectFromOpcode(opcode, result)
        }
    }

    private fun detectFromOpcode(opcode: OpCode, result: MutableSet<WasmProposal>) {
        when (opcode) {
            // --- Sign-extension operators ---
            is I32.Extend8S, is I32.Extend16S,
            is I64.Extend8S, is I64.Extend16S, is I64.Extend32S ->
                result.add(WasmProposal.SignExtensionOps)

            // --- Non-trapping float-to-int conversions ---
            is I32.TruncSatF32S, is I32.TruncSatF32U, is I32.TruncSatF64S, is I32.TruncSatF64U,
            is I64.TruncSatF32S, is I64.TruncSatF32U, is I64.TruncSatF64S, is I64.TruncSatF64U ->
                result.add(WasmProposal.NonTrappingFloatToInt)

            // --- Tail call ---
            is OpCodes.Flow.ReturnCall, is OpCodes.Flow.ReturnCallIndirect ->
                result.add(WasmProposal.TailCall)

            // --- Typed function references ---
            is OpCodes.Flow.CallRef -> result.add(WasmProposal.TypedFunctionReferences)
            is OpCodes.Flow.ReturnCallRef -> {
                result.add(WasmProposal.TypedFunctionReferences)
                result.add(WasmProposal.TailCall)
            }
            is OpCodes.Flow.BrOnNull, is OpCodes.Flow.BrOnNonNull ->
                result.add(WasmProposal.TypedFunctionReferences)
            is OpCodes.Ref.AsNonNull -> result.add(WasmProposal.TypedFunctionReferences)

            // --- Exception handling (modern try_table form) ---
            is OpCodes.Flow.TryTable -> {
                result.add(WasmProposal.ExceptionHandling)
                detectFromOpcodes(opcode.code, result)
            }
            is OpCodes.Flow.Trow -> result.add(WasmProposal.ExceptionHandling)
            is OpCodes.Flow.ThrowRef -> result.add(WasmProposal.ExceptionHandling)

            // --- Legacy exception handling (pre-try_table) ---
            is LegacyEH.Try -> {
                result.add(WasmProposal.LegacyExceptionHandling)
                detectFromOpcodes(opcode.value, result)
            }
            is LegacyEH.Catch, is LegacyEH.CatchAll, is LegacyEH.Delegate, is LegacyEH.Rethrow ->
                result.add(WasmProposal.LegacyExceptionHandling)

            // --- GC instructions ---
            is OpCodes.GC.StructNew, is OpCodes.GC.StructNewDefault,
            is OpCodes.GC.StructGet, is OpCodes.GC.StructGetSignExtended, is OpCodes.GC.StructGetZeroExtended,
            is OpCodes.GC.StructSet,
            is OpCodes.GC.ArrayNew, is OpCodes.GC.ArrayNewDefault, is OpCodes.GC.ArrayNewFixed,
            is OpCodes.GC.ArrayNewData, is OpCodes.GC.ArrayNewElement,
            is OpCodes.GC.ArrayGet, is OpCodes.GC.ArrayGetSigned, is OpCodes.GC.ArrayGetUnsigned,
            is OpCodes.GC.ArraySet, is OpCodes.GC.ArrayLength,
            is OpCodes.GC.ArrayFill, is OpCodes.GC.ArrayCopy,
            is OpCodes.GC.ArrayInitData, is OpCodes.GC.ArrayInitElem,
            is OpCodes.GC.RefTest, is OpCodes.GC.RefTestNullable,
            is OpCodes.GC.RefCast, is OpCodes.GC.RefCastNullable,
            is OpCodes.GC.BrOnCast, is OpCodes.GC.BrOnCastFail,
            is OpCodes.GC.AnyConvertExtern, is OpCodes.GC.ExternConvertAny,
            is OpCodes.GC.RefI31, is OpCodes.GC.I31GetS, is OpCodes.GC.I31GetU ->
                result.add(WasmProposal.GarbageCollection)

            // --- Reference types ---
            is OpCodes.Ref.Null, is OpCodes.Ref.IsNull, is OpCodes.Ref.Func, is OpCodes.Ref.Eq ->
                result.add(WasmProposal.ReferenceTypes)
            is Table.Get, is Table.Set ->
                result.add(WasmProposal.ReferenceTypes)
            is OpCodes.ParametricInstructions.SelectWithType ->
                result.add(WasmProposal.ReferenceTypes)

            // --- Table operations (from reference types and bulk memory) ---
            is Table.Grow, is Table.Size, is Table.Fill ->
                result.add(WasmProposal.ReferenceTypes)
            is Table.Init, is Table.Copy ->
                result.add(WasmProposal.BulkMemoryOperations)
            is Element.Drop ->
                result.add(WasmProposal.BulkMemoryOperations)

            // --- Bulk memory operations ---
            is OpCodes.Memory.Init, is OpCodes.Memory.Copy, is OpCodes.Memory.Fill ->
                result.add(WasmProposal.BulkMemoryOperations)
            is OpCodes.Memory.Data.Drop ->
                result.add(WasmProposal.BulkMemoryOperations)

            // --- SIMD / Relaxed SIMD ---
            is VectorOpCode -> {
                // Relaxed SIMD opcodes are a subset of VectorOpCode.
                // We check known relaxed SIMD types explicitly.
                if (isRelaxedSIMDOpCode(opcode)) {
                    result.add(WasmProposal.RelaxedSIMD)
                }
                result.add(WasmProposal.SIMD)
            }

            // --- Threads / Atomics ---
            is AtomicOpCode -> result.add(WasmProposal.Threads)

            // --- Block/Loop/If: recurse into nested code ---
            is OpCodes.Flow.Block -> detectFromOpcodes(opcode.value, result)
            is OpCodes.Flow.Loop -> detectFromOpcodes(opcode.value, result)
            is OpCodes.Flow.If -> detectFromOpcodes(opcode.value, result)
            is OpCodes.Flow.Else -> detectFromOpcodes(opcode.value, result)

            else -> { /* MVP or unrecognized — skip */ }
        }
    }

    /**
     * Returns `true` if the given [VectorOpCode] belongs to the Relaxed SIMD proposal.
     *
     * Relaxed SIMD opcodes are all 0xFD-prefixed with sub-opcodes ≥ 0x100.
     * We identify them by checking known relaxed types from each lane family.
     */
    private fun isRelaxedSIMDOpCode(opcode: OpCode): Boolean {
        return when (opcode) {
            is org.jetbrains.hexana.wasm.opcodes.vector.i8x16.RelaxedLaneselect,
            is org.jetbrains.hexana.wasm.opcodes.vector.i16x8.RelaxedSwizzle,
            is org.jetbrains.hexana.wasm.opcodes.vector.i16x8.RelaxedLaneselect,
            is org.jetbrains.hexana.wasm.opcodes.vector.i16x8.RelaxedQ15MulrSatS,
            is org.jetbrains.hexana.wasm.opcodes.vector.i16x8.RelaxedDotSi8x16,
            is org.jetbrains.hexana.wasm.opcodes.vector.i32x4.RelaxedLaneselect,
            is org.jetbrains.hexana.wasm.opcodes.vector.i32x4.RelaxedDotAddSi16x8,
            is org.jetbrains.hexana.wasm.opcodes.vector.i32x4.RelaxedTruncSF32x4,
            is org.jetbrains.hexana.wasm.opcodes.vector.i32x4.RelaxedTruncUF32x4,
            is org.jetbrains.hexana.wasm.opcodes.vector.i32x4.RelaxedTruncSZeroF32x4,
            is org.jetbrains.hexana.wasm.opcodes.vector.i32x4.RelaxedTruncUZeroF32x4,
            is org.jetbrains.hexana.wasm.opcodes.vector.i32x4.RelaxedTruncF32x4S,
            is org.jetbrains.hexana.wasm.opcodes.vector.i32x4.RelaxedTruncF32x4U,
            is org.jetbrains.hexana.wasm.opcodes.vector.i32x4.RelaxedTruncF32x4SZero,
            is org.jetbrains.hexana.wasm.opcodes.vector.i32x4.RelaxedTruncF32x4UZero,
            is org.jetbrains.hexana.wasm.opcodes.vector.i64x2.RelaxedLaneselect,
            is org.jetbrains.hexana.wasm.opcodes.vector.f32x4.RelaxedMin,
            is org.jetbrains.hexana.wasm.opcodes.vector.f32x4.RelaxedMax,
            is org.jetbrains.hexana.wasm.opcodes.vector.f32x4.RelaxedMAdd,
            is org.jetbrains.hexana.wasm.opcodes.vector.f32x4.RelaxedNMAdd,
            is org.jetbrains.hexana.wasm.opcodes.vector.f64x2.RelaxedMin,
            is org.jetbrains.hexana.wasm.opcodes.vector.f64x2.RelaxedMax,
            is org.jetbrains.hexana.wasm.opcodes.vector.f64x2.RelaxedMAdd,
            is org.jetbrains.hexana.wasm.opcodes.vector.f64x2.RelaxedNMAdd -> true
            else -> false
        }
    }
}
