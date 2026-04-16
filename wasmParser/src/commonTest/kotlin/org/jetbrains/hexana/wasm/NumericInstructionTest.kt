package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.sections.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for WASM numeric instructions — i32, i64, f32, f64.
 *
 * Covers: constants, arithmetic, bitwise, comparison, conversion,
 * sign-extension, reinterpretation, and unary operations.
 *
 * All tests use inline WAT compiled via wat2wasm.
 */
class NumericInstructionTest {

    private suspend fun parseInlineOpcodes(wat: String): List<OpCode> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        return records[0].opCodes
    }

    // ========================================================================
    // i32 — constants
    // ========================================================================

    @Test
    fun `i32 const — zero`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result i32)
                i32.const 0
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<I32.Const>().single()
        assertEquals(0, c.value)
    }

    @Test
    fun `i32 const — positive`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result i32)
                i32.const 2147483647
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<I32.Const>().single()
        assertEquals(Int.MAX_VALUE, c.value)
    }

    @Test
    fun `i32 const — negative`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result i32)
                i32.const -1
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<I32.Const>().single()
        assertEquals(-1, c.value)
    }

    // ========================================================================
    // i32 — arithmetic
    // ========================================================================

    @Test
    fun `i32 add`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.add
              )
            )
        """.trimIndent())
        assertIs<I32.Add>(ops[2])
    }

    @Test
    fun `i32 sub`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.sub
              )
            )
        """.trimIndent())
        assertIs<I32.Sub>(ops[2])
    }

    @Test
    fun `i32 mul`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.mul
              )
            )
        """.trimIndent())
        assertIs<I32.Mul>(ops[2])
    }

    @Test
    fun `i32 div_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.div_s
              )
            )
        """.trimIndent())
        assertIs<I32.DivS>(ops[2])
    }

    @Test
    fun `i32 div_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.div_u
              )
            )
        """.trimIndent())
        assertIs<I32.DivU>(ops[2])
    }

    @Test
    fun `i32 rem_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.rem_s
              )
            )
        """.trimIndent())
        assertIs<I32.RemS>(ops[2])
    }

    @Test
    fun `i32 rem_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.rem_u
              )
            )
        """.trimIndent())
        assertIs<I32.RemU>(ops[2])
    }

    // ========================================================================
    // i32 — bitwise
    // ========================================================================

    @Test
    fun `i32 and`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.and
              )
            )
        """.trimIndent())
        assertIs<I32.And>(ops[2])
    }

    @Test
    fun `i32 or`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.or
              )
            )
        """.trimIndent())
        assertIs<I32.Or>(ops[2])
    }

    @Test
    fun `i32 xor`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.xor
              )
            )
        """.trimIndent())
        assertIs<I32.Xor>(ops[2])
    }

    @Test
    fun `i32 shl`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.shl
              )
            )
        """.trimIndent())
        assertIs<I32.Shl>(ops[2])
    }

    @Test
    fun `i32 shr_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.shr_s
              )
            )
        """.trimIndent())
        assertIs<I32.ShrS>(ops[2])
    }

    @Test
    fun `i32 shr_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.shr_u
              )
            )
        """.trimIndent())
        assertIs<I32.ShrU>(ops[2])
    }

    @Test
    fun `i32 rotl`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.rotl
              )
            )
        """.trimIndent())
        assertIs<I32.Rotl>(ops[2])
    }

    @Test
    fun `i32 rotr`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.rotr
              )
            )
        """.trimIndent())
        assertIs<I32.Rotr>(ops[2])
    }

    // ========================================================================
    // i32 — unary
    // ========================================================================

    @Test
    fun `i32 clz`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.clz
              )
            )
        """.trimIndent())
        assertIs<I32.Clz>(ops[1])
    }

    @Test
    fun `i32 ctz`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.ctz
              )
            )
        """.trimIndent())
        assertIs<I32.Ctz>(ops[1])
    }

    @Test
    fun `i32 popcnt`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.popcnt
              )
            )
        """.trimIndent())
        assertIs<I32.PopCnt>(ops[1])
    }

    // ========================================================================
    // i32 — comparison
    // ========================================================================

    @Test
    fun `i32 eqz`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.eqz
              )
            )
        """.trimIndent())
        assertIs<I32.Eqz>(ops[1])
    }

    @Test
    fun `i32 eq`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.eq
              )
            )
        """.trimIndent())
        assertIs<I32.Eq>(ops[2])
    }

    @Test
    fun `i32 ne`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.ne
              )
            )
        """.trimIndent())
        assertIs<I32.Ne>(ops[2])
    }

    @Test
    fun `i32 lt_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.lt_s
              )
            )
        """.trimIndent())
        assertIs<I32.LTS>(ops[2])
    }

    @Test
    fun `i32 lt_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.lt_u
              )
            )
        """.trimIndent())
        assertIs<I32.LTU>(ops[2])
    }

    @Test
    fun `i32 gt_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.gt_s
              )
            )
        """.trimIndent())
        assertIs<I32.GTS>(ops[2])
    }

    @Test
    fun `i32 gt_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.gt_u
              )
            )
        """.trimIndent())
        assertIs<I32.GTU>(ops[2])
    }

    @Test
    fun `i32 le_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.le_s
              )
            )
        """.trimIndent())
        assertIs<I32.LES>(ops[2])
    }

    @Test
    fun `i32 le_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.le_u
              )
            )
        """.trimIndent())
        assertIs<I32.LEU>(ops[2])
    }

    @Test
    fun `i32 ge_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.ge_s
              )
            )
        """.trimIndent())
        assertIs<I32.GES>(ops[2])
    }

    @Test
    fun `i32 ge_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.ge_u
              )
            )
        """.trimIndent())
        assertIs<I32.GEU>(ops[2])
    }

    // ========================================================================
    // i32 — conversions and sign extension
    // ========================================================================

    @Test
    fun `i32 wrap_i64`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result i32)
                local.get 0
                i32.wrap_i64
              )
            )
        """.trimIndent())
        assertIs<I32.WrapI64>(ops[1])
    }

    @Test
    fun `i32 extend8_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.extend8_s
              )
            )
        """.trimIndent())
        assertIs<I32.Extend8S>(ops[1])
    }

    @Test
    fun `i32 extend16_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i32)
                local.get 0
                i32.extend16_s
              )
            )
        """.trimIndent())
        assertIs<I32.Extend16S>(ops[1])
    }

    @Test
    fun `i32 trunc_f32_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result i32)
                local.get 0
                i32.trunc_f32_s
              )
            )
        """.trimIndent())
        assertIs<I32.TruncF32S>(ops[1])
    }

    @Test
    fun `i32 trunc_f64_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result i32)
                local.get 0
                i32.trunc_f64_s
              )
            )
        """.trimIndent())
        assertIs<I32.TruncF64S>(ops[1])
    }

    @Test
    fun `i32 reinterpret_f32`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result i32)
                local.get 0
                i32.reinterpret_f32
              )
            )
        """.trimIndent())
        assertIs<I32.ReinterpretF32>(ops[1])
    }

    // ========================================================================
    // i64 — constants
    // ========================================================================

    @Test
    fun `i64 const — zero`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result i64)
                i64.const 0
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<I64.Const>().single()
        assertEquals(0L, c.value)
    }

    @Test
    fun `i64 const — small positive`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result i64)
                i64.const 12345
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<I64.Const>().single()
        assertEquals(12345L, c.value)
    }

    @Test
    fun `i64 const — fits in i32 range`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result i64)
                i64.const 100000
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<I64.Const>().single()
        assertEquals(100000L, c.value)
    }

    // ========================================================================
    // i64 — arithmetic
    // ========================================================================

    @Test
    fun `i64 add`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.add
              )
            )
        """.trimIndent())
        assertIs<I64.Add>(ops[2])
    }

    @Test
    fun `i64 sub`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.sub
              )
            )
        """.trimIndent())
        assertIs<I64.Sub>(ops[2])
    }

    @Test
    fun `i64 mul`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.mul
              )
            )
        """.trimIndent())
        assertIs<I64.Mul>(ops[2])
    }

    @Test
    fun `i64 div_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.div_s
              )
            )
        """.trimIndent())
        assertIs<I64.DivS>(ops[2])
    }

    @Test
    fun `i64 div_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.div_u
              )
            )
        """.trimIndent())
        assertIs<I64.DivU>(ops[2])
    }

    @Test
    fun `i64 rem_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.rem_s
              )
            )
        """.trimIndent())
        assertIs<I64.RemS>(ops[2])
    }

    @Test
    fun `i64 rem_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.rem_u
              )
            )
        """.trimIndent())
        assertIs<I64.RemU>(ops[2])
    }

    // ========================================================================
    // i64 — bitwise and unary
    // ========================================================================

    @Test
    fun `i64 and`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.and
              )
            )
        """.trimIndent())
        assertIs<I64.And>(ops[2])
    }

    @Test
    fun `i64 or`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.or
              )
            )
        """.trimIndent())
        assertIs<I64.Or>(ops[2])
    }

    @Test
    fun `i64 xor`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.xor
              )
            )
        """.trimIndent())
        assertIs<I64.Xor>(ops[2])
    }

    @Test
    fun `i64 shl`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.shl
              )
            )
        """.trimIndent())
        assertIs<I64.Shl>(ops[2])
    }

    @Test
    fun `i64 shr_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.shr_s
              )
            )
        """.trimIndent())
        assertIs<I64.ShrS>(ops[2])
    }

    @Test
    fun `i64 shr_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.shr_u
              )
            )
        """.trimIndent())
        assertIs<I64.ShrU>(ops[2])
    }

    @Test
    fun `i64 rotl`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.rotl
              )
            )
        """.trimIndent())
        assertIs<I64.RotL>(ops[2])
    }

    @Test
    fun `i64 rotr`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i64)
                local.get 0
                local.get 1
                i64.rotr
              )
            )
        """.trimIndent())
        assertIs<I64.RotR>(ops[2])
    }

    @Test
    fun `i64 clz`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result i64)
                local.get 0
                i64.clz
              )
            )
        """.trimIndent())
        assertIs<I64.Clz>(ops[1])
    }

    @Test
    fun `i64 ctz`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result i64)
                local.get 0
                i64.ctz
              )
            )
        """.trimIndent())
        assertIs<I64.Ctz>(ops[1])
    }

    @Test
    fun `i64 popcnt`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result i64)
                local.get 0
                i64.popcnt
              )
            )
        """.trimIndent())
        assertIs<I64.PopCnt>(ops[1])
    }

    // ========================================================================
    // i64 — comparison
    // ========================================================================

    @Test
    fun `i64 eqz`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result i32)
                local.get 0
                i64.eqz
              )
            )
        """.trimIndent())
        assertIs<I64.Eqz>(ops[1])
    }

    @Test
    fun `i64 eq`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i32)
                local.get 0
                local.get 1
                i64.eq
              )
            )
        """.trimIndent())
        assertIs<I64.EQ>(ops[2])
    }

    @Test
    fun `i64 ne`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i32)
                local.get 0
                local.get 1
                i64.ne
              )
            )
        """.trimIndent())
        assertIs<I64.NE>(ops[2])
    }

    @Test
    fun `i64 lt_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i32)
                local.get 0
                local.get 1
                i64.lt_s
              )
            )
        """.trimIndent())
        assertIs<I64.LTS>(ops[2])
    }

    @Test
    fun `i64 ge_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64 i64) (result i32)
                local.get 0
                local.get 1
                i64.ge_u
              )
            )
        """.trimIndent())
        assertIs<I64.GEU>(ops[2])
    }

    // ========================================================================
    // i64 — conversions
    // ========================================================================

    @Test
    fun `i64 extend_i32_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.extend_i32_s
              )
            )
        """.trimIndent())
        assertIs<I64.Extend_I32_S>(ops[1])
    }

    @Test
    fun `i64 extend_i32_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.extend_i32_u
              )
            )
        """.trimIndent())
        assertIs<I64.Extend_I32_U>(ops[1])
    }

    @Test
    fun `i64 extend8_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result i64)
                local.get 0
                i64.extend8_s
              )
            )
        """.trimIndent())
        assertIs<I64.Extend8S>(ops[1])
    }

    @Test
    fun `i64 extend16_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result i64)
                local.get 0
                i64.extend16_s
              )
            )
        """.trimIndent())
        assertIs<I64.Extend16S>(ops[1])
    }

    @Test
    fun `i64 extend32_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result i64)
                local.get 0
                i64.extend32_s
              )
            )
        """.trimIndent())
        assertIs<I64.Extend32S>(ops[1])
    }

    @Test
    fun `i64 reinterpret_f64`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result i64)
                local.get 0
                i64.reinterpret_f64
              )
            )
        """.trimIndent())
        assertIs<I64.ReinterpretF64>(ops[1])
    }

    @Test
    fun `i64 trunc_f64_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result i64)
                local.get 0
                i64.trunc_f64_s
              )
            )
        """.trimIndent())
        assertIs<I64.TruncF64S>(ops[1])
    }

    // ========================================================================
    // f32 — constants
    // ========================================================================

    @Test
    fun `f32 const — zero`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result f32)
                f32.const 0.0
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<F32.Const>().single()
        assertEquals(0.0f, c.value)
    }

    @Test
    fun `f32 const — pi approximation`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result f32)
                f32.const 3.14159
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<F32.Const>().single()
        assertEquals(3.14159f, c.value, "should parse pi approximation")
    }

    @Test
    fun `f32 const — negative`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result f32)
                f32.const -1.5
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<F32.Const>().single()
        assertEquals(-1.5f, c.value)
    }

    // ========================================================================
    // f32 — arithmetic
    // ========================================================================

    @Test
    fun `f32 add`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result f32)
                local.get 0
                local.get 1
                f32.add
              )
            )
        """.trimIndent())
        assertIs<F32.Add>(ops[2])
    }

    @Test
    fun `f32 sub`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result f32)
                local.get 0
                local.get 1
                f32.sub
              )
            )
        """.trimIndent())
        assertIs<F32.Sub>(ops[2])
    }

    @Test
    fun `f32 mul`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result f32)
                local.get 0
                local.get 1
                f32.mul
              )
            )
        """.trimIndent())
        assertIs<F32.Mul>(ops[2])
    }

    @Test
    fun `f32 div`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result f32)
                local.get 0
                local.get 1
                f32.div
              )
            )
        """.trimIndent())
        assertIs<F32.Div>(ops[2])
    }

    @Test
    fun `f32 min`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result f32)
                local.get 0
                local.get 1
                f32.min
              )
            )
        """.trimIndent())
        assertIs<F32.Min>(ops[2])
    }

    @Test
    fun `f32 max`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result f32)
                local.get 0
                local.get 1
                f32.max
              )
            )
        """.trimIndent())
        assertIs<F32.Max>(ops[2])
    }

    @Test
    fun `f32 copysign`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result f32)
                local.get 0
                local.get 1
                f32.copysign
              )
            )
        """.trimIndent())
        assertIs<F32.CopySign>(ops[2])
    }

    // ========================================================================
    // f32 — unary
    // ========================================================================

    @Test
    fun `f32 abs`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result f32)
                local.get 0
                f32.abs
              )
            )
        """.trimIndent())
        assertIs<F32.Abs>(ops[1])
    }

    @Test
    fun `f32 neg`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result f32)
                local.get 0
                f32.neg
              )
            )
        """.trimIndent())
        assertIs<F32.Neg>(ops[1])
    }

    @Test
    fun `f32 ceil`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result f32)
                local.get 0
                f32.ceil
              )
            )
        """.trimIndent())
        assertIs<F32.Ceil>(ops[1])
    }

    @Test
    fun `f32 floor`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result f32)
                local.get 0
                f32.floor
              )
            )
        """.trimIndent())
        assertIs<F32.Floor>(ops[1])
    }

    @Test
    fun `f32 trunc`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result f32)
                local.get 0
                f32.trunc
              )
            )
        """.trimIndent())
        assertIs<F32.Trunc>(ops[1])
    }

    @Test
    fun `f32 nearest`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result f32)
                local.get 0
                f32.nearest
              )
            )
        """.trimIndent())
        assertIs<F32.Nearest>(ops[1])
    }

    @Test
    fun `f32 sqrt`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result f32)
                local.get 0
                f32.sqrt
              )
            )
        """.trimIndent())
        assertIs<F32.Sqrt>(ops[1])
    }

    // ========================================================================
    // f32 — comparison
    // ========================================================================

    @Test
    fun `f32 eq`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result i32)
                local.get 0
                local.get 1
                f32.eq
              )
            )
        """.trimIndent())
        assertIs<F32.EQ>(ops[2])
    }

    @Test
    fun `f32 ne`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result i32)
                local.get 0
                local.get 1
                f32.ne
              )
            )
        """.trimIndent())
        assertIs<F32.NE>(ops[2])
    }

    @Test
    fun `f32 lt`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result i32)
                local.get 0
                local.get 1
                f32.lt
              )
            )
        """.trimIndent())
        assertIs<F32.LT>(ops[2])
    }

    @Test
    fun `f32 gt`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result i32)
                local.get 0
                local.get 1
                f32.gt
              )
            )
        """.trimIndent())
        assertIs<F32.GT>(ops[2])
    }

    @Test
    fun `f32 le`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result i32)
                local.get 0
                local.get 1
                f32.le
              )
            )
        """.trimIndent())
        assertIs<F32.LE>(ops[2])
    }

    @Test
    fun `f32 ge`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f32) (result i32)
                local.get 0
                local.get 1
                f32.ge
              )
            )
        """.trimIndent())
        assertIs<F32.GE>(ops[2])
    }

    // ========================================================================
    // f32 — conversions
    // ========================================================================

    @Test
    fun `f32 convert_i32_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result f32)
                local.get 0
                f32.convert_i32_s
              )
            )
        """.trimIndent())
        assertIs<F32.ConvertI32S>(ops[1])
    }

    @Test
    fun `f32 convert_i64_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result f32)
                local.get 0
                f32.convert_i64_u
              )
            )
        """.trimIndent())
        assertIs<F32.ConvertI64U>(ops[1])
    }

    @Test
    fun `f32 demote_f64`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result f32)
                local.get 0
                f32.demote_f64
              )
            )
        """.trimIndent())
        assertIs<F32.DemoteF64>(ops[1])
    }

    @Test
    fun `f32 reinterpret_i32`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result f32)
                local.get 0
                f32.reinterpret_i32
              )
            )
        """.trimIndent())
        assertIs<F32.ReinterpretI32>(ops[1])
    }

    // ========================================================================
    // f64 — constants
    // ========================================================================

    @Test
    fun `f64 const — zero`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result f64)
                f64.const 0.0
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<F64.Const>().single()
        assertEquals(0.0, c.value)
    }

    @Test
    fun `f64 const — pi`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result f64)
                f64.const 3.141592653589793
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<F64.Const>().single()
        assertEquals(3.141592653589793, c.value)
    }

    @Test
    fun `f64 const — negative`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result f64)
                f64.const -99.99
              )
            )
        """.trimIndent())
        val c = ops.filterIsInstance<F64.Const>().single()
        assertEquals(-99.99, c.value)
    }

    // ========================================================================
    // f64 — arithmetic
    // ========================================================================

    @Test
    fun `f64 add`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result f64)
                local.get 0
                local.get 1
                f64.add
              )
            )
        """.trimIndent())
        assertIs<F64.Add>(ops[2])
    }

    @Test
    fun `f64 sub`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result f64)
                local.get 0
                local.get 1
                f64.sub
              )
            )
        """.trimIndent())
        assertIs<F64.Sub>(ops[2])
    }

    @Test
    fun `f64 mul`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result f64)
                local.get 0
                local.get 1
                f64.mul
              )
            )
        """.trimIndent())
        assertIs<F64.Mul>(ops[2])
    }

    @Test
    fun `f64 div`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result f64)
                local.get 0
                local.get 1
                f64.div
              )
            )
        """.trimIndent())
        assertIs<F64.Div>(ops[2])
    }

    @Test
    fun `f64 min`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result f64)
                local.get 0
                local.get 1
                f64.min
              )
            )
        """.trimIndent())
        assertIs<F64.Min>(ops[2])
    }

    @Test
    fun `f64 max`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result f64)
                local.get 0
                local.get 1
                f64.max
              )
            )
        """.trimIndent())
        assertIs<F64.Max>(ops[2])
    }

    @Test
    fun `f64 copysign`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result f64)
                local.get 0
                local.get 1
                f64.copysign
              )
            )
        """.trimIndent())
        assertIs<F64.Copysign>(ops[2])
    }

    // ========================================================================
    // f64 — unary
    // ========================================================================

    @Test
    fun `f64 abs`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result f64)
                local.get 0
                f64.abs
              )
            )
        """.trimIndent())
        assertIs<F64.Abs>(ops[1])
    }

    @Test
    fun `f64 neg`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result f64)
                local.get 0
                f64.neg
              )
            )
        """.trimIndent())
        assertIs<F64.Neg>(ops[1])
    }

    @Test
    fun `f64 ceil`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result f64)
                local.get 0
                f64.ceil
              )
            )
        """.trimIndent())
        assertIs<F64.Ceil>(ops[1])
    }

    @Test
    fun `f64 floor`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result f64)
                local.get 0
                f64.floor
              )
            )
        """.trimIndent())
        assertIs<F64.Floor>(ops[1])
    }

    @Test
    fun `f64 trunc`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result f64)
                local.get 0
                f64.trunc
              )
            )
        """.trimIndent())
        assertIs<F64.Trunc>(ops[1])
    }

    @Test
    fun `f64 nearest`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result f64)
                local.get 0
                f64.nearest
              )
            )
        """.trimIndent())
        assertIs<F64.Nearest>(ops[1])
    }

    @Test
    fun `f64 sqrt`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result f64)
                local.get 0
                f64.sqrt
              )
            )
        """.trimIndent())
        assertIs<F64.Sqrt>(ops[1])
    }

    // ========================================================================
    // f64 — comparison
    // ========================================================================

    @Test
    fun `f64 eq`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result i32)
                local.get 0
                local.get 1
                f64.eq
              )
            )
        """.trimIndent())
        assertIs<F64.EQ>(ops[2])
    }

    @Test
    fun `f64 ne`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result i32)
                local.get 0
                local.get 1
                f64.ne
              )
            )
        """.trimIndent())
        assertIs<F64.NE>(ops[2])
    }

    @Test
    fun `f64 lt`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result i32)
                local.get 0
                local.get 1
                f64.lt
              )
            )
        """.trimIndent())
        assertIs<F64.LT>(ops[2])
    }

    @Test
    fun `f64 gt`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result i32)
                local.get 0
                local.get 1
                f64.gt
              )
            )
        """.trimIndent())
        assertIs<F64.GT>(ops[2])
    }

    @Test
    fun `f64 le`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result i32)
                local.get 0
                local.get 1
                f64.le
              )
            )
        """.trimIndent())
        assertIs<F64.LE>(ops[2])
    }

    @Test
    fun `f64 ge`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64 f64) (result i32)
                local.get 0
                local.get 1
                f64.ge
              )
            )
        """.trimIndent())
        assertIs<F64.GE>(ops[2])
    }

    // ========================================================================
    // f64 — conversions
    // ========================================================================

    @Test
    fun `f64 convert_i32_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result f64)
                local.get 0
                f64.convert_i32_s
              )
            )
        """.trimIndent())
        assertIs<F64.ConvertI32S>(ops[1])
    }

    @Test
    fun `f64 convert_i64_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result f64)
                local.get 0
                f64.convert_i64_u
              )
            )
        """.trimIndent())
        assertIs<F64.ConvertI64U>(ops[1])
    }

    @Test
    fun `f64 promote_f32`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result f64)
                local.get 0
                f64.promote_f32
              )
            )
        """.trimIndent())
        assertIs<F64.PromoteF32>(ops[1])
    }

    @Test
    fun `f64 reinterpret_i64`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result f64)
                local.get 0
                f64.reinterpret_i64
              )
            )
        """.trimIndent())
        assertIs<F64.ReinterpretI64>(ops[1])
    }

    // ========================================================================
    // mixed numeric — cross-type expressions
    // ========================================================================

    @Test
    fun `mixed — i32 arithmetic chain`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.add
                i32.const 2
                i32.mul
                i32.const 1
                i32.sub
              )
            )
        """.trimIndent())
        assertIs<I32.Add>(ops[2])
        assertIs<I32.Const>(ops[3])
        assertIs<I32.Mul>(ops[4])
        assertIs<I32.Const>(ops[5])
        assertIs<I32.Sub>(ops[6])
    }

    @Test
    fun `mixed — f64 to i32 conversion chain`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result f32)
                local.get 0
                f64.convert_i32_s
                f32.demote_f64
              )
            )
        """.trimIndent())
        assertIs<F64.ConvertI32S>(ops[1])
        assertIs<F32.DemoteF64>(ops[2])
    }

    @Test
    fun `mixed — i32 and i64 interop`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i64)
                local.get 0
                i64.extend_i32_s
                i64.const 100
                i64.mul
              )
            )
        """.trimIndent())
        assertIs<I64.Extend_I32_S>(ops[1])
        assertIs<I64.Const>(ops[2])
        assertEquals(100L, (ops[2] as I64.Const).value)
        assertIs<I64.Mul>(ops[3])
    }

    @Test
    fun `mixed — f32 and f64 round-trip`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result f32)
                local.get 0
                f64.promote_f32
                f64.sqrt
                f32.demote_f64
              )
            )
        """.trimIndent())
        assertIs<F64.PromoteF32>(ops[1])
        assertIs<F64.Sqrt>(ops[2])
        assertIs<F32.DemoteF64>(ops[3])
    }
}
