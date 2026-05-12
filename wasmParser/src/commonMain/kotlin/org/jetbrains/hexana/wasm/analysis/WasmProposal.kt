package org.jetbrains.hexana.wasm.analysis

/**
 * Finished WebAssembly proposals that can be detected in a binary module.
 *
 * Each entry corresponds to a proposal listed at
 * [https://github.com/WebAssembly/proposals/blob/main/finished-proposals.md].
 * The [specVersion] indicates the spec revision that merged the proposal.
 */
enum class WasmProposal(val displayName: String, val specVersion: String) {
    /** Sign-extension operators (i32.extend8_s, i64.extend32_s, etc.). */
    SignExtensionOps("Sign-extension operators", "2.0"),

    /** Non-trapping float-to-int conversions (i32.trunc_sat_f32_s, etc.). */
    NonTrappingFloatToInt("Non-trapping float-to-int conversions", "2.0"),

    /** Multi-value block types (func-type index in block/loop/if). */
    MultiValue("Multi-value", "2.0"),

    /** Reference types (ref.null, ref.is_null, ref.func, externref, table ops). */
    ReferenceTypes("Reference types", "2.0"),

    /** Bulk memory operations (memory.init, memory.copy, memory.fill, data.drop, table.init, table.copy, elem.drop). */
    BulkMemoryOperations("Bulk memory operations", "2.0"),

    /** Fixed-width SIMD (v128 type and 0xFD-prefixed instructions with sub-opcode < 0x100). */
    SIMD("Fixed-width SIMD", "2.0"),

    /** Import/Export of mutable globals. */
    MutableGlobals("Import/Export of mutable globals", "2.0"),

    /** Tail call (return_call, return_call_indirect). */
    TailCall("Tail call", "3.0"),

    /** Typed function references (call_ref, return_call_ref, ref.as_non_null, br_on_null, br_on_non_null). */
    TypedFunctionReferences("Typed function references", "3.0"),

    /** Garbage collection (struct/array types, GC instructions). */
    GarbageCollection("Garbage collection", "3.0"),

    /** Multiple memories (more than one memory definition or import). */
    MultipleMemories("Multiple memories", "3.0"),

    /** Relaxed SIMD (0xFD-prefixed instructions with sub-opcode ≥ 0x100). */
    RelaxedSIMD("Relaxed SIMD", "3.0"),

    /** Exception handling (tag section, try_table, throw, throw_ref). */
    ExceptionHandling("Exception handling", "3.0"),

    /** Extended constant expressions (i32/i64 add/sub/mul in const-expr context). */
    // Note: cannot be reliably detected from opcodes alone — const-expr validation
    // is not performed by the parser. Detected only via the tag section or
    // exception-related opcodes when applicable.

    /** Memory64 (i64-indexed memory limits). */
    Memory64("Memory64", "3.0"),

    /** Threads and atomics (shared memory, 0xFE-prefixed atomic instructions). */
    Threads("Threads", "3.0"),

    /** Legacy exception handling (try/catch/delegate — pre-try_table form). */
    LegacyExceptionHandling("Legacy exception handling", "pre-3.0"),
}
