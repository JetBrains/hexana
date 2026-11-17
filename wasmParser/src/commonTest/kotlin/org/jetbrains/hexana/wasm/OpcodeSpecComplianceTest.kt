package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.sections.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the seven opcode-parser issues identified in the spec audit.
 *
 * Each test is labeled with a `bug N` prefix matching the audit. Tests
 * for fixed bugs pass and act as regression protection; tests for unfixed
 * bugs assert the spec-correct behavior and will fail until the parser is
 * fixed — they document the expected contract.
 *
 * Audit reference: see commit history for the spec-audit findings.
 */
class OpcodeSpecComplianceTest {

    private suspend fun parseInlineOpcodes(wat: String): List<OpCode> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        return records[0].opCodes
    }

    private suspend fun parseAllFunctions(wat: String): List<List<OpCode>> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        return records.map { it.opCodes }
    }

    private fun flattenOpcodes(ops: List<OpCode>): List<OpCode> {
        val result = mutableListOf<OpCode>()
        for (op in ops) {
            result.add(op)
            when (op) {
                is OpCodes.Flow.Block -> result.addAll(flattenOpcodes(op.value))
                is OpCodes.Flow.TryTable -> result.addAll(flattenOpcodes(op.code))
                else -> {}
            }
        }
        return result
    }

    // ========================================================================
    // Bug 1 (FIXED): 0xAB i32.trunc_f64_u was dispatched as TruncF64S.
    //
    // Fixed in commit ed237652. Test pins the correct dispatch.
    // ========================================================================

    @Test
    fun `bug 1 — 0xAB dispatches to i32 trunc_f64_u (not _s)`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result i32)
                local.get 0
                i32.trunc_f64_u
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        // The `_u` variant is what we expect; if we accidentally dispatch to `_s`,
        // the test would find a TruncF64S instance instead.
        assertTrue(ops.any { it is I32.TruncF64U }, "i32.trunc_f64_u must produce TruncF64U")
        assertTrue(ops.none { it is I32.TruncF64S }, "must not produce TruncF64S")
    }

    // ========================================================================
    // Bug 2 (FIXED): 0x11 call_indirect dropped the tableidx immediate.
    //
    // Fixed in commit c6f5c29f — CallIndirect is now (typeIdx, tableIdx).
    // Tests pin both immediates and verify subsequent instructions parse
    // at the correct offset (catches byte-shift if tableidx read regresses).
    // ========================================================================

    @Test
    fun `bug 2 — call_indirect carries typeidx and tableidx (single table)`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}f (func (param i32) (result i32)))
              (table 1 funcref)
              (func (export "g") (param i32) (result i32)
                i32.const 0
                local.get 0
                i32.const 0
                call_indirect (type ${'$'}f)
                drop
                i32.const 99
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val ci = ops.filterIsInstance<OpCodes.Flow.CallIndirect>().single()
        assertEquals(0, ci.value0, "typeidx = 0 (the only declared type)")
        assertEquals(0, ci.value1, "tableidx = 0 (default table)")
        // i32.const 99 lives right after call_indirect — if tableidx read had
        // regressed, the trailing 0x00 would be parsed as `unreachable` and the
        // 99 constant would shift / disappear.
        assertTrue(
            ops.filterIsInstance<I32.Const>().any { it.value == 99 },
            "i32.const 99 (after call_indirect) must parse correctly",
        )
    }

    @Test
    fun `bug 2 — call_indirect on non-zero table index`() = runTest {
        // Multi-table requires the reference-types proposal, but standard wat2wasm
        // builds support it. Tests that tableidx is genuinely consumed.
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}f (func (param i32) (result i32)))
              (table ${'$'}t0 1 funcref)
              (table ${'$'}t1 1 funcref)
              (func (export "g") (param i32) (result i32)
                i32.const 0
                local.get 0
                i32.const 0
                call_indirect ${'$'}t1 (type ${'$'}f)
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val ci = ops.filterIsInstance<OpCodes.Flow.CallIndirect>().single()
        assertEquals(0, ci.value0, "typeidx = 0")
        assertEquals(1, ci.value1, "tableidx = 1 (\$t1)")
    }

    // ========================================================================
    // Bug 3 (FIXED locally): 0xFB 0x18/0x19 br_on_cast / br_on_cast_fail
    // were missing the leading `castflags:u8` byte.
    //
    // Already covered by `inline — br_on_cast …` tests in
    // MultibyteInstructionTest. One redundant assertion here pins the
    // smoke check at this file's level for visibility alongside bug 3b.
    // ========================================================================

    @Test
    fun `bug 3 — br_on_cast reads castflags byte before label`() = runTest {
        val ops = flattenOpcodes(parseInlineOpcodes("""
            (module
              (type ${'$'}base (struct (field i32)))
              (type ${'$'}derived (sub ${'$'}base (struct (field i32) (field i32))))
              (func (export "f") (param (ref null ${'$'}base)) (result i32)
                (block ${'$'}L (result (ref ${'$'}derived))
                  local.get 0
                  br_on_cast ${'$'}L (ref null ${'$'}base) (ref ${'$'}derived)
                  drop
                  i32.const 0
                  return
                )
                drop
                i32.const 1
              )
            )
        """.trimIndent()))
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val br = ops.filterIsInstance<OpCodes.GC.BrOnCast>().single()
        // src nullable, dst non-null → flags == 0x01
        assertEquals(0x01.toByte(), br.flags, "src nullable, dst non-null")
    }

    // ========================================================================
    // Bug 3b (FIXED): br_on_cast_fail (0xFB 0x19) was reading label as SLEB128
    // and heap types as ULEB128 — both signednesses flipped vs spec.
    //
    // Manifests for label >= 64 or for abstract heap types whose single-byte
    // encoding has bit 6 set (`anyref` = 0x6E, etc.).
    //
    // Fix realigned BrOnCastFail with BrOnCast: label: UInt, srcType/dstType:
    // HeapType (Int-backed), parser uses consumeInt + consumeSignedInt.
    // Test pins the SLEB-decoded value of `anyref`'s 0x6E byte (= -18).
    // ========================================================================

    @Test
    fun `single-byte SLEB128 negative values round-trip`() = runTest {
        // Probe: this exercises getSLEB128 in encdec via i32.const, which uses
        // SLEB128. Expected to fail with -1 for every negative when SLEB128's
        // sign-extension uses `(-1L) shl shift` instead of `(-1L) shl (shift+7)`.
        for (v in listOf(-1, -2, -18, -63, -64)) {
            val ops = parseInlineOpcodes("""
                (module
                  (func (export "f") (result i32)
                    i32.const $v
                  )
                )
            """.trimIndent())
            val c = ops.filterIsInstance<I32.Const>().single()
            assertEquals(v, c.value, "i32.const $v must round-trip; got ${c.value}")
        }
    }

    @Test
    fun `bug 3b — br_on_cast_fail decodes abstract heap type with SLEB128`() = runTest {
        val ops = flattenOpcodes(parseInlineOpcodes("""
            (module
              (type ${'$'}T (struct (field i32)))
              (func (export "f") (param anyref) (result i32)
                (block ${'$'}L (result anyref)
                  local.get 0
                  br_on_cast_fail ${'$'}L anyref (ref ${'$'}T)
                  drop
                  i32.const 1
                  return
                )
                drop
                i32.const 0
              )
            )
        """.trimIndent()))
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val br = ops.filterIsInstance<OpCodes.GC.BrOnCastFail>().single()
        // `anyref` heap type encodes as single byte 0x6E. As SLEB128 → -18.
        // As ULEB128 (pre-fix buggy parser) → 110.
        assertEquals(
            -18, br.srcType.type,
            "br_on_cast_fail must read src heaptype as SLEB128 (anyref = -18, not ULEB 110)",
        )
        // Sanity on the destination type: type index 0 (\$T), encoded as 0x00 in
        // both SLEB and ULEB — same value either way; this just confirms the
        // class shape is the new HeapType-backed one.
        assertEquals(0, br.dstType.type, "dst heaptype = type index 0 (\$T)")
    }

    // ========================================================================
    // Bug 4 (FIXED — was a false-positive in the audit): 0xFB 0x0A
    // array.new_elem IS dispatched (consumeGCOpCode.kt:17 → ArrayNewElement,
    // declared at OpCodes.GC.ArrayNewElement). Audit said it was missing; it
    // wasn't. Test pins the correct dispatch.
    // ========================================================================

    @Test
    fun `bug 4 — array_new_elem (0xFB 0x0A) parses to ArrayNewElement`() = runTest {
        val all = parseAllFunctions("""
            (module
              (type ${'$'}arr (array funcref))
              (func ${'$'}helper)
              (elem ${'$'}e func ${'$'}helper)
              (func (export "make")
                i32.const 0
                i32.const 1
                array.new_elem ${'$'}arr ${'$'}e
                drop
              )
            )
        """.trimIndent())
        val opsAll = all[1]
        val unparsed = opsAll.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "array.new_elem should parse: ${unparsed.firstOrNull()?.e?.message}")
        assertTrue(
            opsAll.any { it is OpCodes.GC.ArrayNewElement },
            "expected ArrayNewElement opcode in function body",
        )
    }

    // ========================================================================
    // Bug 5 (UNFIXED): 0xFC sub-opcodes 0x0C–0x11 (table.init, elem.drop,
    // table.copy, table.grow, table.size, table.fill) are not dispatched.
    //
    // consumeFcOpCode.kt only handles trunc_sat (0x00–0x07) and memory ops
    // (0x08–0x0B). All six table ops fall through to UnsupportedOpsException.
    //
    // One test per missing op. Each FAILS until the dispatcher learns the op.
    // ========================================================================

    @Test
    fun `bug 5 — table_init (0xFC 0x0C) parses`() = runTest {
        val all = parseAllFunctions("""
            (module
              (table ${'$'}t 4 funcref)
              (func ${'$'}h)
              (elem ${'$'}e func ${'$'}h)
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 1
                table.init ${'$'}t ${'$'}e
              )
            )
        """.trimIndent())
        val unparsed = all[1].filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "table.init should parse: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `bug 5 — elem_drop (0xFC 0x0D) parses`() = runTest {
        val all = parseAllFunctions("""
            (module
              (func ${'$'}h)
              (elem ${'$'}e func ${'$'}h)
              (func (export "f")
                elem.drop ${'$'}e
              )
            )
        """.trimIndent())
        val unparsed = all[1].filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "elem.drop should parse: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `bug 5 — table_copy (0xFC 0x0E) parses`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table ${'$'}t 4 funcref)
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 1
                table.copy ${'$'}t ${'$'}t
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "table.copy should parse: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `bug 5 — table_grow (0xFC 0x0F) parses`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table ${'$'}t 4 funcref)
              (func (export "f") (result i32)
                ref.null func
                i32.const 1
                table.grow ${'$'}t
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "table.grow should parse: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `bug 5 — table_size (0xFC 0x10) parses`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table ${'$'}t 4 funcref)
              (func (export "f") (result i32)
                table.size ${'$'}t
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "table.size should parse: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `bug 5 — table_fill (0xFC 0x11) parses`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table ${'$'}t 4 funcref)
              (func (export "f")
                i32.const 0
                ref.null func
                i32.const 1
                table.fill ${'$'}t
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "table.fill should parse: ${unparsed.firstOrNull()?.e?.message}")
    }

    // ========================================================================
    // Bug 6 (UNFIXED): SIMD i32x4.trunc_sat_f64x2_u_zero (0xFD 0xFD).
    //
    // The class TruncSatUZeroF64x2 isn't declared in i32x4.kt and the FD
    // dispatcher has no case for sub-opcode 253. Modules using this
    // instruction throw → UnparsedOpCode. The signed sibling
    // (TruncSatSZeroF64x2 / 0xFD 0xFC) is supported.
    //
    // FAILS until the class is added and consumeFDBasedOpCode.kt dispatches it.
    // ========================================================================

    @Test
    fun `bug 6 — i32x4_trunc_sat_f64x2_u_zero (0xFD 0xFD) parses`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128) (result v128)
                local.get 0
                i32x4.trunc_sat_f64x2_u_zero
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(
            unparsed.isEmpty(),
            "i32x4.trunc_sat_f64x2_u_zero should parse: ${unparsed.firstOrNull()?.e?.message}",
        )
    }

    // ========================================================================
    // Bug 7 (UNFIXED, low-priority spec drift): legacy-EH `catch` (0x07)
    // reads tagidx as SLEB128 instead of ULEB128. Class field is `Int`
    // instead of `UInt`.
    //
    // Manifests only for tagidx >= 64 (single-byte encoding where SLEB and
    // ULEB diverge — bit 6 set). Inline WAT cannot trigger this:
    //   1. wat2wasm doesn't expose the legacy-EH proposal opcodes (try / catch /
    //      throw — distinct from the modern try_table family that we test).
    //   2. Constructing a module with 64+ tags is impractical inline.
    //
    // This bug needs a hand-crafted byte-level test (build a tiny Wasm module
    // with a code section containing the legacy try/catch sequence and
    // tagidx = 100). Out of scope for the inline-WAT pattern used here.
    //
    // Test deliberately omitted; this comment is the placeholder.
    // ========================================================================
}
