package org.jetbrains.hexana.wasm.analysis

/**
 * WebAssembly proposals that can be detected in a binary module.
 *
 * Finished proposals correspond to entries at
 * [https://github.com/WebAssembly/proposals/blob/main/finished-proposals.md]; the
 * [specVersion] indicates the spec revision that merged them. Entries for proposals that
 * are not yet standardized carry [specVersion] `"proposal"`.
 *
 * [featureId] is the key this proposal has in WebAssembly's `features.json`
 * ([https://github.com/WebAssembly/website/blob/main/features.json]), which is the source
 * of the per-runtime compatibility table and the documentation URL shown for a proposal.
 * Every entry must have one, and it must exist in that file —
 * `WasmProposalFeatureIdTest` pins both directions.
 *
 * Proposals deliberately *not* represented here, because a core binary carries no signal
 * for them or the parser cannot reach it (kept in sync with the inventory in
 * `FinishedProposalsInstructionTest`):
 * - `compactImportSection`, `typeImports`, `sharedEverythingThreads` — their encodings hit
 *   the `error("unknown ...")` paths in the import and type parsers.
 * - `stackSwitching`, `wideArithmetic`, `halfPrecision`, `stringref` — need opcode families
 *   the parser does not decode yet.
 * - `bigInt`, `jspi`, `esmIntegration`, `customAnnotationSyntaxInTheTextFormat` — JS-API or
 *   text-format only, with no binary encoding at all.
 */
enum class WasmProposal(
    val displayName: String,
    val specVersion: String,
    val featureId: String,
) {
    /** Sign-extension operators (i32.extend8_s, i64.extend32_s, etc.). */
    SignExtensionOps("Sign-extension operators", "2.0", "signExtensions"),

    /** Non-trapping float-to-int conversions (i32.trunc_sat_f32_s, etc.). */
    NonTrappingFloatToInt("Non-trapping float-to-int conversions", "2.0", "saturatedFloatToInt"),

    /** Multi-value block types (func-type index in block/loop/if). */
    MultiValue("Multi-value", "2.0", "multiValue"),

    /** Reference types (ref.null, ref.is_null, ref.func, externref, table ops). */
    ReferenceTypes("Reference types", "2.0", "referenceTypes"),

    /** Bulk memory operations (memory.init, memory.copy, memory.fill, data.drop, table.init, table.copy, elem.drop). */
    BulkMemoryOperations("Bulk memory operations", "2.0", "bulkMemory"),

    /** Fixed-width SIMD (v128 type and 0xFD-prefixed instructions with sub-opcode < 0x100). */
    SIMD("Fixed-width SIMD", "2.0", "simd"),

    /** Import/Export of mutable globals. */
    MutableGlobals("Import/Export of mutable globals", "2.0", "mutableGlobals"),

    /** Tail call (return_call, return_call_indirect). */
    TailCall("Tail call", "3.0", "tailCall"),

    /** Typed function references (call_ref, return_call_ref, ref.as_non_null, br_on_null, br_on_non_null). */
    TypedFunctionReferences("Typed function references", "3.0", "typedFunctionReferences"),

    /** Garbage collection (struct/array types, GC instructions). */
    GarbageCollection("Garbage collection", "3.0", "gc"),

    /** Multiple memories (more than one memory definition or import). */
    MultipleMemories("Multiple memories", "3.0", "multiMemory"),

    /** Relaxed SIMD (0xFD-prefixed instructions with sub-opcode >= 0x100). */
    RelaxedSIMD("Relaxed SIMD", "3.0", "relaxedSimd"),

    /** Exception handling (tag section, try_table, throw, throw_ref). */
    ExceptionHandling("Exception handling", "3.0", "exceptionsFinal"),

    /** Memory64 (i64-indexed memory limits). */
    Memory64("Memory64", "3.0", "memory64"),

    /** Threads and atomics (shared memory, 0xFE-prefixed atomic instructions). */
    Threads("Threads", "3.0", "threads"),

    /**
     * Extended constant expressions: a non-MVP instruction in an initializer expression
     * (global, table, data offset, or element segment).
     */
    ExtendedConst("Extended constant expressions", "3.0", "extendedConst"),

    /** Legacy exception handling (try/catch/delegate — pre-try_table form). */
    LegacyExceptionHandling("Legacy exception handling", "pre-3.0", "exceptions"),

    /**
     * Branch hinting: the `metadata.code.branch_hint` custom section. Detected by presence
     * only; the hint payload itself is not decoded.
     */
    BranchHinting("Branch hinting", "proposal", "branchHinting"),

    /** JS string builtins: an import from the `wasm:js-string` module. */
    JsStringBuiltins("JS string builtins", "proposal", "jsStringBuiltins"),

    /**
     * Extended name section: a name-section subsection beyond the three the core spec
     * defines (module, function, locals), i.e. labels, types, tables, memories, globals,
     * elem segments, data segments, fields, or tags.
     */
    ExtendedNameSection("Extended name section", "proposal", "extendedNameSection"),

    /** Custom descriptors: an `exact` heap type, or a descriptor/describes clause on a type. */
    CustomDescriptors("Custom descriptors", "proposal", "customDescriptors"),

    /** Custom page sizes: a memory whose limits carry an explicit page-size-log2 field. */
    CustomPageSizes("Custom page sizes", "proposal", "customPageSizes"),

    /** Component model: the binary is a component (layer >= 1), not a core module. */
    ComponentModel("Component model", "proposal", "componentModel"),
}
