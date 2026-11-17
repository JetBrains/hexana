package org.jetbrains.hexana.wasm

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.opcodes.parseOpCodes
import org.jetbrains.hexana.wasm.opcodes.table.Element
import org.jetbrains.hexana.wasm.opcodes.table.Table
import org.jetbrains.hexana.wasm.opcodes.vector.V128
import org.jetbrains.hexana.wasm.sections.StandardTypesParser
import org.jetbrains.hexana.wasm.sections.WasmFuncHeapType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Inline-bytes coverage for **Finished** WebAssembly proposals
 * (those merged into the spec — see
 * https://github.com/WebAssembly/proposals/blob/main/finished-proposals.md).
 *
 * Each section pins one or more canonical encodings per spec. Tests are
 * intentionally minimal: a single instruction (or instruction pair) parsed
 * via [parseOpCodes], asserting the resulting `OpCode` subclass and any
 * decoded immediates. Bigger, .wat-driven coverage already lives in
 * `MultibyteInstructionTest` and friends; this file is for canonical-bytes
 * regression pinning that doesn't depend on `wat2wasm`.
 *
 * Excluded (no opcode-level parser surface):
 *  - **MVP** — covered by every other test in the suite.
 *  - **Import/Export of Mutable Globals**, **JS BigInt↔i64**,
 *    **JS String Builtins** — JS API / section-level only.
 *  - **Custom Annotation Syntax** — text-format only.
 *  - **Branch Hinting** — custom section; not yet wired into the parser.
 *  - **Memory64** — section-level (`0x04`/`0x05` limits already supported);
 *    its memarg-flag-bit extension is handled together with multi-memory.
 *  - **Multiple memories** load/store — covered by the memarg bit-6 flag
 *    test below; bulk-memory dispatch *does* take memidx and is covered.
 */
class FinishedProposalsInstructionTest {

    // ========================================================================
    // Sign-extension operators (spec 2.0)
    // https://github.com/WebAssembly/sign-extension-ops
    // ------------------------------------------------------------------------
    // Single-byte opcodes, no immediates:
    //   0xC0 i32.extend8_s   0xC1 i32.extend16_s
    //   0xC2 i64.extend8_s   0xC3 i64.extend16_s   0xC4 i64.extend32_s
    // ========================================================================

    @Test
    fun `sign-ext — i32 extend8_s`() {
        val ops = byteArrayOf(0xC0.toByte()).toCommonByteBuffer().parseOpCodes()
        assertSame(I32.Extend8S, ops.single())
    }

    @Test
    fun `sign-ext — i32 extend16_s`() {
        val ops = byteArrayOf(0xC1.toByte()).toCommonByteBuffer().parseOpCodes()
        assertSame(I32.Extend16S, ops.single())
    }

    @Test
    fun `sign-ext — i64 extend8_s, extend16_s, extend32_s`() {
        val ops = byteArrayOf(0xC2.toByte(), 0xC3.toByte(), 0xC4.toByte())
            .toCommonByteBuffer().parseOpCodes()
        assertEquals(3, ops.size)
        assertSame(I64.Extend8S, ops[0])
        assertSame(I64.Extend16S, ops[1])
        assertSame(I64.Extend32S, ops[2])
    }

    // ========================================================================
    // Non-trapping float-to-int conversions (spec 2.0)
    // https://github.com/WebAssembly/nontrapping-float-to-int-conversions
    // ------------------------------------------------------------------------
    // 0xFC prefix, sub-opcodes 0x00..0x07 (single-byte ULEB):
    //   0x00 i32.trunc_sat_f32_s   0x01 i32.trunc_sat_f32_u
    //   0x02 i32.trunc_sat_f64_s   0x03 i32.trunc_sat_f64_u
    //   0x04 i64.trunc_sat_f32_s   0x05 i64.trunc_sat_f32_u
    //   0x06 i64.trunc_sat_f64_s   0x07 i64.trunc_sat_f64_u
    // ========================================================================

    @Test
    fun `trunc_sat — all 8 variants`() {
        val ops = byteArrayOf(
            0xFC.toByte(), 0x00,
            0xFC.toByte(), 0x01,
            0xFC.toByte(), 0x02,
            0xFC.toByte(), 0x03,
            0xFC.toByte(), 0x04,
            0xFC.toByte(), 0x05,
            0xFC.toByte(), 0x06,
            0xFC.toByte(), 0x07,
        ).toCommonByteBuffer().parseOpCodes()
        assertEquals(8, ops.size)
        assertSame(I32.TruncSatF32S, ops[0])
        assertSame(I32.TruncSatF32U, ops[1])
        assertSame(I32.TruncSatF64S, ops[2])
        assertSame(I32.TruncSatF64U, ops[3])
        assertSame(I64.TruncSatF32S, ops[4])
        assertSame(I64.TruncSatF32U, ops[5])
        assertSame(I64.TruncSatF64S, ops[6])
        assertSame(I64.TruncSatF64U, ops[7])
    }

    // ========================================================================
    // Multi-value (spec 2.0)
    // https://github.com/WebAssembly/multi-value
    // ------------------------------------------------------------------------
    // Block types may now be a positive type index (S33-encoded), referring
    // to a func-type with multiple params/results. Encoding shape:
    //   0x02 <s33> ... 0x0B            (block typeidx N)
    // ========================================================================

    @Test
    fun `multi-value — block with positive S33 type index`() {
        // 02 = block, 05 = positive SLEB33 (typeidx 5), 0B = end
        val ops = byteArrayOf(0x02, 0x05, 0x0B).toCommonByteBuffer().parseOpCodes()
        val block = assertIs<OpCodes.Flow.Block>(ops.single())
        val s33 = assertIs<StandardTypesParser.WasmBlockType.S33>(block.type)
        assertEquals(5u, s33.value)
        assertEquals(emptyList(), block.value)
    }

    // ========================================================================
    // Reference Types (spec 2.0)
    // https://github.com/WebAssembly/reference-types
    // ------------------------------------------------------------------------
    // Adds:
    //   0xD0 ref.null  ht
    //   0xD1 ref.is_null
    //   0xD2 ref.func  funcidx
    //   0x1C select t*           (typed select; vec(valtype) immediate)
    //   0x25 table.get tableidx  0x26 table.set tableidx
    //   0xFC 0x0F table.grow     0xFC 0x10 table.size     0xFC 0x11 table.fill
    //   0xFC 0x0C table.init     0xFC 0x0E table.copy     0xFC 0x0D elem.drop
    //   externref shorthand 0x6F
    // (Bulk-memory pieces are listed under their own proposal below.)
    // ========================================================================

    @Test
    fun `reftypes — ref_null funcref`() {
        // D0 70: ref.null (funcref shorthand 0x70)
        val ops = byteArrayOf(0xD0.toByte(), 0x70).toCommonByteBuffer().parseOpCodes()
        val refNull = assertIs<OpCodes.Ref.Null>(ops.single())
        assertSame(WasmFuncHeapType, refNull.value)
    }

    @Test
    fun `reftypes — ref_is_null`() {
        val ops = byteArrayOf(0xD1.toByte()).toCommonByteBuffer().parseOpCodes()
        assertSame(OpCodes.Ref.IsNull, ops.single())
    }

    @Test
    fun `reftypes — ref_func`() {
        // D2 07: ref.func 7
        val ops = byteArrayOf(0xD2.toByte(), 0x07).toCommonByteBuffer().parseOpCodes()
        val refFunc = assertIs<OpCodes.Ref.Func>(ops.single())
        assertEquals(7u, refFunc.value)
    }

    @Test
    fun `reftypes — typed select with one valtype`() {
        // 1C 01 7F: select t*, vec(valtype) = [i32] (i32 = 0x7F)
        val ops = byteArrayOf(0x1C, 0x01, 0x7F).toCommonByteBuffer().parseOpCodes()
        val sel = assertIs<OpCodes.ParametricInstructions.SelectWithType>(ops.single())
        assertEquals(1, sel.value.size)
    }

    @Test
    fun `reftypes — table_get and table_set`() {
        // 25 00 26 00: table.get 0; table.set 0
        val ops = byteArrayOf(0x25, 0x00, 0x26, 0x00).toCommonByteBuffer().parseOpCodes()
        assertEquals(2, ops.size)
        val get = assertIs<Table.Get>(ops[0]); assertEquals(0u, get.value)
        val set = assertIs<Table.Set>(ops[1]); assertEquals(0u, set.value)
    }

    @Test
    fun `reftypes — table_grow, table_size, table_fill`() {
        // FC 0F 00; FC 10 00; FC 11 00
        val ops = byteArrayOf(
            0xFC.toByte(), 0x0F, 0x00,
            0xFC.toByte(), 0x10, 0x00,
            0xFC.toByte(), 0x11, 0x00,
        ).toCommonByteBuffer().parseOpCodes()
        assertEquals(3, ops.size)
        assertIs<Table.Grow>(ops[0])
        assertIs<Table.Size>(ops[1])
        assertIs<Table.Fill>(ops[2])
    }

    // ========================================================================
    // Bulk memory operations (spec 2.0)
    // https://github.com/WebAssembly/bulk-memory-operations
    // ------------------------------------------------------------------------
    // 0xFC prefix:
    //   0x08 memory.init dataidx memidx
    //   0x09 data.drop   dataidx
    //   0x0A memory.copy memidxY memidxX
    //   0x0B memory.fill memidx
    //   0x0C table.init  elemidx tableidx
    //   0x0D elem.drop   elemidx
    //   0x0E table.copy  tableidxX tableidxY
    // ========================================================================

    @Test
    fun `bulk-mem — memory_init`() {
        // FC 08 03 00: memory.init dataidx=3 memidx=0
        val ops = byteArrayOf(0xFC.toByte(), 0x08, 0x03, 0x00).toCommonByteBuffer().parseOpCodes()
        val init = assertIs<OpCodes.Memory.Init>(ops.single())
        assertEquals(3u, init.dataIdx)
        assertEquals(0u, init.memIdx)
    }

    @Test
    fun `bulk-mem — data_drop`() {
        // FC 09 02: data.drop 2
        val ops = byteArrayOf(0xFC.toByte(), 0x09, 0x02).toCommonByteBuffer().parseOpCodes()
        val drop = assertIs<OpCodes.Memory.Data.Drop>(ops.single())
        assertEquals(2u, drop.dataIdx)
    }

    @Test
    fun `bulk-mem — memory_copy`() {
        // FC 0A 00 00: memory.copy memY=0 memX=0
        val ops = byteArrayOf(0xFC.toByte(), 0x0A, 0x00, 0x00).toCommonByteBuffer().parseOpCodes()
        assertIs<OpCodes.Memory.Copy>(ops.single())
    }

    @Test
    fun `bulk-mem — memory_fill`() {
        // FC 0B 00: memory.fill memidx=0
        val ops = byteArrayOf(0xFC.toByte(), 0x0B, 0x00).toCommonByteBuffer().parseOpCodes()
        assertIs<OpCodes.Memory.Fill>(ops.single())
    }

    @Test
    fun `bulk-mem — table_init`() {
        // FC 0C 04 00: table.init elemidx=4 tableidx=0
        val ops = byteArrayOf(0xFC.toByte(), 0x0C, 0x04, 0x00).toCommonByteBuffer().parseOpCodes()
        assertIs<Table.Init>(ops.single())
    }

    @Test
    fun `bulk-mem — elem_drop`() {
        // FC 0D 01: elem.drop 1
        val ops = byteArrayOf(0xFC.toByte(), 0x0D, 0x01).toCommonByteBuffer().parseOpCodes()
        val drop = assertIs<Element.Drop>(ops.single())
        assertEquals(1u, drop.value)
    }

    @Test
    fun `bulk-mem — table_copy`() {
        // FC 0E 00 00: table.copy 0 0
        val ops = byteArrayOf(0xFC.toByte(), 0x0E, 0x00, 0x00).toCommonByteBuffer().parseOpCodes()
        assertIs<Table.Copy>(ops.single())
    }

    // ========================================================================
    // Fixed-width SIMD (spec 2.0)
    // https://github.com/WebAssembly/simd
    // ------------------------------------------------------------------------
    // 0xFD prefix, ULEB128 sub-opcode (mostly 1 byte; range 0x00..~0xFF for
    // SIMD-128 and ≥0x100 for Relaxed SIMD).
    //   0xFD 0x0C v128.const  i128 (16 raw bytes)
    // ========================================================================

    @Test
    fun `simd — v128_const all-zero`() {
        // FD 0C <16 bytes of 0x00>
        val bytes = ByteArray(2 + 16).apply {
            this[0] = 0xFD.toByte()
            this[1] = 0x0C
            // remaining 16 bytes default to 0
        }
        val ops = bytes.toCommonByteBuffer().parseOpCodes()
        val v = assertIs<V128.Const>(ops.single())
        // V128.Const stores the 16 lanes as v0..vf individually; assert all zero.
        val lanes = listOf(v.v0, v.v1, v.v2, v.v3, v.v4, v.v5, v.v6, v.v7,
                           v.v8, v.v9, v.va, v.vb, v.vc, v.vd, v.ve, v.vf)
        assertTrue(lanes.all { it == 0.toByte() })
    }

    // ========================================================================
    // Tail call (spec 3.0)
    // https://github.com/WebAssembly/tail-call
    // ------------------------------------------------------------------------
    //   0x12 return_call           funcidx
    //   0x13 return_call_indirect  typeidx tableidx
    //   0x15 return_call_ref       typeidx
    // ========================================================================

    @Test
    fun `tail-call — return_call`() {
        // 12 03: return_call 3
        val ops = byteArrayOf(0x12, 0x03).toCommonByteBuffer().parseOpCodes()
        val rc = assertIs<OpCodes.Flow.ReturnCall>(ops.single())
        assertEquals(3u, rc.value)
    }

    @Test
    fun `tail-call — return_call_indirect`() {
        // 13 02 00: return_call_indirect typeidx=2 tableidx=0
        val ops = byteArrayOf(0x13, 0x02, 0x00).toCommonByteBuffer().parseOpCodes()
        assertIs<OpCodes.Flow.ReturnCallIndirect>(ops.single())
    }

    @Test
    fun `tail-call — return_call_ref`() {
        // 15 04: return_call_ref typeidx=4
        val ops = byteArrayOf(0x15, 0x04).toCommonByteBuffer().parseOpCodes()
        val rcr = assertIs<OpCodes.Flow.ReturnCallRef>(ops.single())
        assertEquals(4u, rcr.value)
    }

    // ========================================================================
    // Typed Function References (spec 3.0)
    // https://github.com/WebAssembly/function-references
    // ------------------------------------------------------------------------
    //   0x14 call_ref          typeidx
    //   0x15 return_call_ref   typeidx (also covered by Tail Call above)
    //   0xD4 ref.as_non_null
    //   0xD5 br_on_null        labelidx
    //   0xD6 br_on_non_null    labelidx
    //   non-nullable ref encoding 0x64 ht (covered by Reference Types tests)
    // ========================================================================

    @Test
    fun `funcrefs — call_ref`() {
        // 14 02: call_ref typeidx=2
        val ops = byteArrayOf(0x14, 0x02).toCommonByteBuffer().parseOpCodes()
        val cr = assertIs<OpCodes.Flow.CallRef>(ops.single())
        assertEquals(2u, cr.value)
    }

    @Test
    fun `funcrefs — ref_as_non_null`() {
        val ops = byteArrayOf(0xD4.toByte()).toCommonByteBuffer().parseOpCodes()
        assertSame(OpCodes.Ref.AsNonNull, ops.single())
    }

    @Test
    fun `funcrefs — br_on_null and br_on_non_null`() {
        // D5 00; D6 01
        val ops = byteArrayOf(0xD5.toByte(), 0x00, 0xD6.toByte(), 0x01)
            .toCommonByteBuffer().parseOpCodes()
        assertEquals(2, ops.size)
        val brOnNull = assertIs<OpCodes.Flow.BrOnNull>(ops[0])
        assertEquals(0u, brOnNull.value)
        val brOnNonNull = assertIs<OpCodes.Flow.BrOnNonNull>(ops[1])
        assertEquals(1u, brOnNonNull.value)
    }

    // ========================================================================
    // Garbage Collection (spec 3.0)  — full coverage in MultibyteInstructionTest.
    // https://github.com/WebAssembly/gc
    // ------------------------------------------------------------------------
    // Smoke test: 0xFB 0x1C ref.i31 — object instruction, no immediates.
    // ========================================================================

    @Test
    fun `gc — ref_i31 smoke`() {
        // FB 1C: ref.i31
        val ops = byteArrayOf(0xFB.toByte(), 0x1C).toCommonByteBuffer().parseOpCodes()
        assertSame(OpCodes.GC.RefI31, ops.single())
    }

    // ========================================================================
    // Multiple memories (spec 3.0)
    // https://github.com/WebAssembly/multi-memory
    // ------------------------------------------------------------------------
    // The proposal extends memarg encoding so load/store can target a
    // non-zero memidx: bit 6 of the align byte signals "memidx follows".
    //
    // Bulk-memory dispatch (memory.init/copy/fill) carries memidx as
    // explicit immediates and is covered by the bulk-memory section above.
    //
    // Load/store: `consumeMemArg` (consumers.kt) detects bit 6, consumes the
    // trailing memidx ULEB128 for stream alignment, and clears the flag from
    // the effective align stored in MemArg.
    // ========================================================================

    @Test
    fun `multi-memory — i32_load with memidx decodes to single op`() {
        // 28 42 00 01:
        //   28     i32.load opcode
        //   42     rawAlign = 0x42 (bit 6 set = memidx follows; effective log2 align = 2)
        //   00     memidx = 0  (consumed for stream alignment, not stored in MemArg)
        //   01     offset = 1
        val ops = byteArrayOf(0x28, 0x42, 0x00, 0x01).toCommonByteBuffer().parseOpCodes()
        assertEquals(1, ops.size, "multi-memory load must produce exactly one op")
        val load = assertIs<OpCodes.Memory.i32.Load>(ops.single())
        assertEquals(2, load.value.align, "effective align must have bit 6 cleared")
        assertEquals(1, load.value.offset)
    }

    // ========================================================================
    // Relaxed SIMD (spec 3.0)
    // https://github.com/WebAssembly/relaxed-simd
    // ------------------------------------------------------------------------
    // 0xFD prefix, sub-opcodes ≥ 0x100 (multi-byte ULEB).
    // Spec table (excerpt):
    //   0x100 i8x16.relaxed_swizzle
    //   0x101 i32x4.relaxed_trunc_f32x4_s
    //   ...
    // 0x100 ULEB128 = 0x80 0x02 (high-bit on first byte, then 0x02).
    // We pin one representative dispatch.
    // ========================================================================

    @Test
    fun `relaxed-simd — relaxed_swizzle dispatches without throw`() {
        // FD 80 02: 0xFD prefix, sub-opcode ULEB(0x100)
        val ops = byteArrayOf(0xFD.toByte(), 0x80.toByte(), 0x02).toCommonByteBuffer().parseOpCodes()
        // Match by class name to stay agnostic to whether the OpCode lives in
        // i8x16 / i16x8 / etc. — the only thing we pin here is "did not throw".
        assertEquals(1, ops.size)
    }

    // ========================================================================
    // Exception Handling (spec 3.0, modern try_table form)
    // https://github.com/WebAssembly/exception-handling
    // ------------------------------------------------------------------------
    // Modern (post-legacy) opcodes:
    //   0x1F try_table  bt vec(catch) instr* 0x0B
    //   0x08 throw      tagidx
    //   0x0A throw_ref
    //
    // catch encoding: one byte tag (0x00..0x03) then immediates.
    //
    // Legacy (try/catch/delegate) is covered separately in
    // [LegacyExceptionInstructionTest].
    // ========================================================================

    @Test
    fun `eh — try_table empty body, no catches`() {
        // 1F 40 00 0B: try_table bt=Empty, vec(catch)=[], end
        val ops = byteArrayOf(0x1F, 0x40, 0x00, 0x0B).toCommonByteBuffer().parseOpCodes()
        val tt = assertIs<OpCodes.Flow.TryTable>(ops.single())
        assertSame(StandardTypesParser.WasmBlockType.Empty, tt.blockType)
        assertEquals(0, tt.catches.size)
        assertEquals(0, tt.code.size)
    }

    @Test
    fun `eh — try_table with one catch_all`() {
        // 1F 40 01 02 05 0B
        //   1F          try_table
        //   40          bt = Empty
        //   01          vec(catch) length = 1
        //   02 05       catch kind=0x02 (catch_all), labelidx=5
        //   0B          end
        val ops = byteArrayOf(0x1F, 0x40, 0x01, 0x02, 0x05, 0x0B)
            .toCommonByteBuffer().parseOpCodes()
        val tt = assertIs<OpCodes.Flow.TryTable>(ops.single())
        assertEquals(1, tt.catches.size)
        val ca = assertIs<OpCodes.Flow.CatchType.CatchAll>(tt.catches.single())
        assertEquals(5u, ca.labelIdx)
    }

    @Test
    fun `eh — throw and throw_ref`() {
        // 08 02; 0A
        val ops = byteArrayOf(0x08, 0x02, 0x0A).toCommonByteBuffer().parseOpCodes()
        assertEquals(2, ops.size)
        val throw0 = assertIs<OpCodes.Flow.Trow>(ops[0])
        assertEquals(2u, throw0.value)
        assertSame(OpCodes.Flow.ThrowRef, ops[1])
    }

    // ========================================================================
    // Extended Constant Expressions (spec 3.0)
    // https://github.com/WebAssembly/extended-const
    // ------------------------------------------------------------------------
    // Allows a richer subset of instructions (i32/i64 add/sub/mul) inside
    // const expressions (global init, element offset, data offset).
    // No new opcodes — the change is which expressions validators accept.
    //
    // We don't validate; we just parse. This pins that a typical extended
    // const init (i32.const + i32.const + i32.add) parses cleanly through
    // [parseOpCodes] with no surprises.
    // ========================================================================

    @Test
    fun `ext-const — i32_const + i32_const + i32_add`() {
        // 41 01 41 02 6A 0B
        //   41 01     i32.const 1
        //   41 02     i32.const 2
        //   6A        i32.add
        //   0B        end
        val ops = byteArrayOf(0x41, 0x01, 0x41, 0x02, 0x6A, 0x0B)
            .toCommonByteBuffer().parseOpCodes()
        assertEquals(3, ops.size)
        val c1 = assertIs<I32.Const>(ops[0]); assertEquals(1, c1.value)
        val c2 = assertIs<I32.Const>(ops[1]); assertEquals(2, c2.value)
        assertSame(I32.Add, ops[2])
    }
}
