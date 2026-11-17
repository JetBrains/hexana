package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.sections.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for WASM vector (SIMD) instructions — v128 prefix 0xFD.
 *
 * Each test verifies that the parser produces no UnparsedOpCode for the
 * given SIMD instruction. Tests will fail until SIMD decoding is implemented
 * in consumeMultibyteOpCode.
 */
class VectorInstructionTest {

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

    private fun assertNoUnparsed(ops: List<OpCode>) {
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "No instructions should be unparsed: ${unparsed.firstOrNull()?.e?.message}")
    }

    // ========================================================================
    // v128.const
    // ========================================================================

    @Test
    fun `v128 const — i32x4`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result v128)
                v128.const i32x4 1 2 3 4
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `v128 const — i64x2`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result v128)
                v128.const i64x2 100 200
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `v128 const — f32x4`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result v128)
                v128.const f32x4 1.0 2.0 3.0 4.0
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `v128 const — f64x2`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result v128)
                v128.const f64x2 1.5 2.5
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // v128 memory — load and store
    // ========================================================================

    @Test
    fun `v128 load`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result v128)
                local.get 0
                v128.load
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `v128 store`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f") (param i32 v128)
                local.get 0
                local.get 1
                v128.store
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // splat — broadcast scalar to all lanes
    // ========================================================================

    @Test
    fun `i8x16 splat`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result v128)
                local.get 0
                i8x16.splat
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i16x8 splat`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result v128)
                local.get 0
                i16x8.splat
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i32x4 splat`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result v128)
                local.get 0
                i32x4.splat
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i64x2 splat`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i64) (result v128)
                local.get 0
                i64x2.splat
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `f32x4 splat`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result v128)
                local.get 0
                f32x4.splat
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `f64x2 splat`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result v128)
                local.get 0
                f64x2.splat
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // extract_lane / replace_lane
    // ========================================================================

    @Test
    fun `i32x4 extract_lane`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128) (result i32)
                local.get 0
                i32x4.extract_lane 0
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i32x4 replace_lane`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 i32) (result v128)
                local.get 0
                local.get 1
                i32x4.replace_lane 2
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `f64x2 extract_lane`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128) (result f64)
                local.get 0
                f64x2.extract_lane 1
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i8x16 extract_lane_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128) (result i32)
                local.get 0
                i8x16.extract_lane_s 5
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // i32x4 arithmetic
    // ========================================================================

    @Test
    fun `i32x4 add`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i32x4.add
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i32x4 sub`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i32x4.sub
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i32x4 mul`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i32x4.mul
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // i16x8 arithmetic
    // ========================================================================

    @Test
    fun `i16x8 add`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i16x8.add
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i16x8 mul`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i16x8.mul
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // i64x2 arithmetic
    // ========================================================================

    @Test
    fun `i64x2 add`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i64x2.add
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i64x2 sub`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i64x2.sub
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i64x2 mul`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i64x2.mul
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // f32x4 arithmetic
    // ========================================================================

    @Test
    fun `f32x4 add`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                f32x4.add
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `f32x4 sub`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                f32x4.sub
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `f32x4 mul`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                f32x4.mul
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `f32x4 div`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                f32x4.div
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `f32x4 sqrt`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128) (result v128)
                local.get 0
                f32x4.sqrt
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // f64x2 arithmetic
    // ========================================================================

    @Test
    fun `f64x2 add`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                f64x2.add
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `f64x2 sub`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                f64x2.sub
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `f64x2 mul`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                f64x2.mul
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `f64x2 sqrt`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128) (result v128)
                local.get 0
                f64x2.sqrt
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // v128 bitwise
    // ========================================================================

    @Test
    fun `v128 and`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                v128.and
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `v128 or`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                v128.or
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `v128 xor`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                v128.xor
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `v128 not`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128) (result v128)
                local.get 0
                v128.not
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // i32x4 comparison
    // ========================================================================

    @Test
    fun `i32x4 eq`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i32x4.eq
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i32x4 ne`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i32x4.ne
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // i8x16 shuffle
    // ========================================================================

    @Test
    fun `i8x16 shuffle`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i8x16.shuffle 0 1 2 3 4 5 6 7 16 17 18 19 20 21 22 23
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // i8x16 swizzle
    // ========================================================================

    @Test
    fun `i8x16 swizzle`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i8x16.swizzle
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // conversion — between integer and float lanes
    // ========================================================================

    @Test
    fun `f32x4 convert_i32x4_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128) (result v128)
                local.get 0
                f32x4.convert_i32x4_s
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i32x4 trunc_sat_f32x4_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128) (result v128)
                local.get 0
                i32x4.trunc_sat_f32x4_s
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    // ========================================================================
    // v128 any_true / i32x4 all_true
    // ========================================================================

    @Test
    fun `v128 any_true`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128) (result i32)
                local.get 0
                v128.any_true
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }

    @Test
    fun `i32x4 all_true`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param v128) (result i32)
                local.get 0
                i32x4.all_true
              )
            )
        """.trimIndent())
        assertNoUnparsed(ops)
    }
}
