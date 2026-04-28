package org.jetbrains.hexana.wasm.analysis

import org.jetbrains.hexana.wasm.opcodes.HeapType
import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i64
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.opcodes.table.Element
import org.jetbrains.hexana.wasm.opcodes.table.Table
import org.jetbrains.hexana.wasm.opcodes.vector.V128
import org.jetbrains.hexana.wasm.opcodes.vector.f32x4
import org.jetbrains.hexana.wasm.opcodes.vector.f64x2
import org.jetbrains.hexana.wasm.opcodes.vector.i16x8
import org.jetbrains.hexana.wasm.opcodes.vector.i32x4
import org.jetbrains.hexana.wasm.opcodes.vector.i64x2
import org.jetbrains.hexana.wasm.opcodes.vector.i8x16
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage check: every Finished WebAssembly proposal that contributes new
 * opcodes is exercised through [classifyOpcode] and pinned to its expected
 * [OpcodeCategory]. The size profiler walks parsed opcodes, buckets them by
 * category, and reports per-category byte attribution; gaps here translate
 * directly into mis-attributed bytes in the size view.
 *
 * Categories we treat as **load-bearing** for proposal coverage:
 *  - [OpcodeCategory.Call]        — call/return-call/indirect/ref variants
 *  - [OpcodeCategory.Control]     — block, branch, parametric, exception
 *  - [OpcodeCategory.Memory]      — load/store/bulk-mem, memory.size/grow
 *  - [OpcodeCategory.Arithmetic]  — numeric (i32/i64/f32/f64) including
 *                                   sign-ext, trunc-sat, extended const ops
 *  - [OpcodeCategory.GC]          — every `0xFB`-prefixed instruction
 *  - [OpcodeCategory.SIMD]        — every `0xFD`-prefixed instruction
 *                                   (fixed-width SIMD + relaxed SIMD)
 *
 * Categories we accept as [OpcodeCategory.Other] by design (matched here so
 * the assumption is checked, not silently relied on):
 *  - Local/Global access
 *  - Table operations and element.drop
 *  - Reference-type predicates and constructors that aren't typed casts
 *
 * Sibling proposals with **no opcode-level surface** — JS BigInt, JS String
 * Builtins, Mutable Globals, Custom Annotation Syntax, Branch Hinting,
 * Memory64 (section-level limits), Multiple memories (memarg flag bit, see
 * limitation note in `FinishedProposalsInstructionTest`) — are intentionally
 * absent.
 */
class SizeProfilerProposalCoverageTest {

    // ========================================================================
    // Sign-extension operators (spec 2.0)
    // ========================================================================

    @Test
    fun `sign-ext i32 ops are Arithmetic`() {
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.Extend8S))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.Extend16S))
    }

    @Test
    fun `sign-ext i64 ops are Arithmetic`() {
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.Extend8S))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.Extend16S))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.Extend32S))
    }

    // ========================================================================
    // Non-trapping float-to-int conversions (spec 2.0)
    // ========================================================================

    @Test
    fun `trunc_sat i32 variants are Arithmetic`() {
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.TruncSatF32S))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.TruncSatF32U))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.TruncSatF64S))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.TruncSatF64U))
    }

    @Test
    fun `trunc_sat i64 variants are Arithmetic`() {
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.TruncSatF32S))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.TruncSatF32U))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.TruncSatF64S))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.TruncSatF64U))
    }

    // ========================================================================
    // Multi-value (spec 2.0)
    //
    // Block with positive S33 typeidx — same Flow.Block opcode class as
    // single-result blocks, so the classifier shape is unchanged.
    // ========================================================================

    @Test
    fun `multi-value block stays Control`() {
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.Flow.Block(null, emptyList())))
    }

    // ========================================================================
    // Reference Types (spec 2.0)
    //
    // Reference-typed constructs (ref.null/is_null/func/eq) are classified as
    // Other by design — they're neither control flow nor memory and the size
    // profiler's "Other" bucket is the right home for them. Table ops and
    // typed select likewise go to Other / Control respectively.
    // ========================================================================

    @Test
    fun `reftypes ref ops are Other`() {
        assertEquals(OpcodeCategory.Other, classifyOpcode(OpCodes.Ref.IsNull))
        assertEquals(OpcodeCategory.Other, classifyOpcode(OpCodes.Ref.Func(0u)))
        assertEquals(OpcodeCategory.Other, classifyOpcode(OpCodes.Ref.Eq))
    }

    @Test
    fun `reftypes typed-select is Control`() {
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.ParametricInstructions.SelectWithType(emptyList())))
    }

    @Test
    fun `reftypes table ops are Other`() {
        assertEquals(OpcodeCategory.Other, classifyOpcode(Table.Get(0u)))
        assertEquals(OpcodeCategory.Other, classifyOpcode(Table.Set(0u)))
    }

    // ========================================================================
    // Bulk memory operations (spec 2.0)
    //
    // memory.* bulk ops are Memory. Table-side bulk ops (table.init/copy,
    // elem.drop) currently fall into Other along with the rest of the
    // Table family — pinned here so the asymmetry is intentional.
    // ========================================================================

    @Test
    fun `bulk-mem memory ops are Memory`() {
        assertEquals(OpcodeCategory.Memory, classifyOpcode(OpCodes.Memory.Init(0u, 0u)))
        assertEquals(OpcodeCategory.Memory, classifyOpcode(OpCodes.Memory.Copy(0u, 0u)))
        assertEquals(OpcodeCategory.Memory, classifyOpcode(OpCodes.Memory.Fill(0u)))
        assertEquals(OpcodeCategory.Memory, classifyOpcode(OpCodes.Memory.Data.Drop(0u)))
    }

    @Test
    fun `bulk-mem table ops are Other (asymmetric with memory ops)`() {
        assertEquals(OpcodeCategory.Other, classifyOpcode(Table.Init(0u, 0u, 0)))
        assertEquals(OpcodeCategory.Other, classifyOpcode(Table.Copy(0u, 0u, 0)))
        assertEquals(OpcodeCategory.Other, classifyOpcode(Element.Drop(0u, 0)))
    }

    // ========================================================================
    // Fixed-width SIMD (spec 2.0)
    //
    // Every 0xFD-prefixed instruction must classify as SIMD. Pre-fix this was
    // dead code — no opcode routed to OpcodeCategory.SIMD; all V128/i8x16/…
    // ops fell to Other and appeared as unclassified bytes in the size view.
    // ========================================================================

    @Test
    fun `simd v128 const is SIMD`() {
        assertEquals(OpcodeCategory.SIMD, classifyOpcode(V128.Const(
            0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0
        )))
    }

    @Test
    fun `simd v128 load and store are SIMD`() {
        assertEquals(OpcodeCategory.SIMD, classifyOpcode(V128.Load(MemArg(0, 0))))
        assertEquals(OpcodeCategory.SIMD, classifyOpcode(V128.Store(MemArg(0, 0))))
    }

    @Test
    fun `simd lane-typed arithmetic is SIMD`() {
        assertEquals(OpcodeCategory.SIMD, classifyOpcode(i8x16.Add))
        assertEquals(OpcodeCategory.SIMD, classifyOpcode(i16x8.Add))
        assertEquals(OpcodeCategory.SIMD, classifyOpcode(i32x4.Add))
        assertEquals(OpcodeCategory.SIMD, classifyOpcode(i64x2.Add))
        assertEquals(OpcodeCategory.SIMD, classifyOpcode(f32x4.Add))
        assertEquals(OpcodeCategory.SIMD, classifyOpcode(f64x2.Add))
    }

    @Test
    fun `simd swizzle is SIMD`() {
        assertEquals(OpcodeCategory.SIMD, classifyOpcode(i8x16.Swizzle))
    }

    // ========================================================================
    // Tail call (spec 3.0)
    // ========================================================================

    @Test
    fun `tail-call return_call is Call`() {
        assertEquals(OpcodeCategory.Call, classifyOpcode(OpCodes.Flow.ReturnCall(0u)))
    }

    @Test
    fun `tail-call return_call_indirect is Call`() {
        assertEquals(OpcodeCategory.Call, classifyOpcode(OpCodes.Flow.ReturnCallIndirect(0u, 0u)))
    }

    @Test
    fun `tail-call return_call_ref is Call`() {
        assertEquals(OpcodeCategory.Call, classifyOpcode(OpCodes.Flow.ReturnCallRef(0u)))
    }

    // ========================================================================
    // Typed Function References (spec 3.0)
    // ========================================================================

    @Test
    fun `funcrefs call_ref is Call`() {
        assertEquals(OpcodeCategory.Call, classifyOpcode(OpCodes.Flow.CallRef(0u)))
    }

    @Test
    fun `funcrefs br_on_null and br_on_non_null are Control`() {
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.Flow.BrOnNull(0u)))
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.Flow.BrOnNonNull(0u)))
    }

    @Test
    fun `funcrefs ref_as_non_null is Other`() {
        assertEquals(OpcodeCategory.Other, classifyOpcode(OpCodes.Ref.AsNonNull))
    }

    // ========================================================================
    // Garbage Collection (spec 3.0)
    //
    // All 0xFB sub-opcodes must classify as GC. Pre-fix, several common ops
    // (StructNewDefault, ArrayNew, ArrayFill, ArrayInit*, RefTestNullable,
    // BrOnCast, RefI31, I31Get*) had no explicit case and fell to Other —
    // skewing the size view toward "Other" on Kotlin/Wasm and Java→Wasm
    // binaries that emit them heavily.
    // ========================================================================

    @Test
    fun `gc struct ops are GC`() {
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.StructNew(0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.StructNewDefault(0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.StructGet(0u, 0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.StructGetSignExtended(0u, 0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.StructGetZeroExtended(0u, 0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.StructSet(0u, 0u)))
    }

    @Test
    fun `gc array ops are GC`() {
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayNew(0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayNewDefault(0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayNewFixed(0u, 0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayNewData(0u, 0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayNewElement(0u, 0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayGet(0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayGetSigned(0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayGetUnsigned(0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArraySet(0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayLength))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayFill(0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayCopy(0u, 0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayInitData(0u, 0u)))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayInitElem(0u, 0u)))
    }

    @Test
    fun `gc ref test and cast ops are GC`() {
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.RefTest(HeapType(-16))))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.RefTestNullable(HeapType(-16))))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.RefCast(HeapType(-16))))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.RefCastNullable(HeapType(-16))))
    }

    @Test
    fun `gc br_on_cast variants are GC`() {
        assertEquals(OpcodeCategory.GC, classifyOpcode(
            OpCodes.GC.BrOnCast(0, 0u, HeapType(-16), HeapType(-16))
        ))
        assertEquals(OpcodeCategory.GC, classifyOpcode(
            OpCodes.GC.BrOnCastFail(0, 0u, HeapType(-16), HeapType(-16))
        ))
    }

    @Test
    fun `gc i31 and convert ops are GC`() {
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.RefI31))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.I31GetS))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.I31GetU))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.AnyConvertExtern))
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ExternConvertAny))
    }

    // ========================================================================
    // Multiple memories (spec 3.0)
    //
    // Bulk memory ops carry memidx; loads/stores currently don't decode the
    // memarg flag bit (see FinishedProposalsInstructionTest). Either way,
    // the classification stays Memory.
    // ========================================================================

    @Test
    fun `multi-memory bulk ops are Memory`() {
        assertEquals(OpcodeCategory.Memory, classifyOpcode(OpCodes.Memory.Init(2u, 1u)))
        assertEquals(OpcodeCategory.Memory, classifyOpcode(OpCodes.Memory.Copy(1u, 0u)))
        assertEquals(OpcodeCategory.Memory, classifyOpcode(OpCodes.Memory.Fill(1u)))
    }

    @Test
    fun `multi-memory load and store are Memory regardless of memidx`() {
        assertEquals(OpcodeCategory.Memory, classifyOpcode(i32.Load(MemArg(0, 0))))
        assertEquals(OpcodeCategory.Memory, classifyOpcode(i64.Store(MemArg(0, 0))))
        assertEquals(OpcodeCategory.Memory, classifyOpcode(f32.Load(MemArg(0, 0))))
        assertEquals(OpcodeCategory.Memory, classifyOpcode(f64.Store(MemArg(0, 0))))
    }

    // ========================================================================
    // Relaxed SIMD (spec 3.0)
    //
    // Sub-opcodes ≥ 0x100 under the 0xFD prefix. Same SIMD bucket as the
    // fixed-width 2.0 SIMD set.
    // ========================================================================

    @Test
    fun `relaxed-simd ops are SIMD`() {
        assertEquals(OpcodeCategory.SIMD, classifyOpcode(i16x8.RelaxedSwizzle))
    }

    // ========================================================================
    // Exception Handling — modern (spec 3.0)
    // Legacy try/catch/delegate is covered separately; here we pin the
    // post-exnref shape (try_table, throw, throw_ref) to Control.
    // ========================================================================

    @Test
    fun `eh try_table is Control`() {
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.Flow.TryTable(
            org.jetbrains.hexana.wasm.sections.StandardTypesParser.WasmBlockType.Empty,
            emptyList(), emptyList()
        )))
    }

    @Test
    fun `eh throw and throw_ref are Control`() {
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.Flow.Trow(0u)))
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.Flow.ThrowRef))
    }

    // ========================================================================
    // Extended Constant Expressions (spec 3.0)
    //
    // No new opcodes — pin that the const-expr-eligible set (i32/i64
    // const + add/sub/mul) classifies as Arithmetic. If size analysis
    // ever wants to call out "const exprs vs body", it'll need a richer
    // path; today these go to Arithmetic regardless of context.
    // ========================================================================

    @Test
    fun `extended-const i32 ops are Arithmetic`() {
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.Const(0)))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.Add))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.Sub))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.Mul))
    }

    @Test
    fun `extended-const i64 ops are Arithmetic`() {
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.Const(0)))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.Add))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.Sub))
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.Mul))
    }
}
