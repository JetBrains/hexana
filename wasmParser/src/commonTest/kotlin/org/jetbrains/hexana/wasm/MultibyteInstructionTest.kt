package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.parseWatResource
import org.jetbrains.hexana.tests.watResourceToBuffer
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.sections.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for multibyte (prefixed) WASM instructions.
 *
 * Covers:
 * - 0xFC prefix: saturating truncation instructions (i32/i64.trunc_sat_f32/f64_s/u)
 * - 0xFB prefix: GC instructions (struct, array, ref, i31, extern/any conversions)
 */
class MultibyteInstructionTest {

    private suspend fun parseOpcodes(resourcePath: String): List<OpCode> {
        val module = parseWatResource(resourcePath)
        val buffer = watResourceToBuffer(resourcePath)
        val records = with(module.codeSection!!) { buffer.scan() }
        return records.flatMap { it.opCodes }
    }

    private suspend fun parseFunctionOpcodes(resourcePath: String, funcIndex: Int): List<OpCode> {
        val module = parseWatResource(resourcePath)
        val buffer = watResourceToBuffer(resourcePath)
        val records = with(module.codeSection!!) { buffer.scan() }
        return records[funcIndex].opCodes
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

    private suspend fun parseInlineOpcodes(wat: String): List<OpCode> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        return records[0].opCodes
    }

    // ========================================================================
    // FC prefix — saturating truncation instructions
    // ========================================================================

    // --- i32.trunc_sat_f32 ---

    @Test
    fun `i32 trunc_sat_f32_s — resource`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/trunc_sat_i32_f32.wat", 0)
        val truncSat = ops.filterIsInstance<I32.TruncSatF32S>()
        assertTrue(truncSat.isNotEmpty(), "Should parse i32.trunc_sat_f32_s")
    }

    @Test
    fun `i32 trunc_sat_f32_u — resource`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/trunc_sat_i32_f32.wat", 1)
        val truncSat = ops.filterIsInstance<I32.TruncSatF32U>()
        assertTrue(truncSat.isNotEmpty(), "Should parse i32.trunc_sat_f32_u")
    }

    // --- i32.trunc_sat_f64 ---

    @Test
    fun `i32 trunc_sat_f64_s — resource`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/trunc_sat_i32_f64.wat", 0)
        val truncSat = ops.filterIsInstance<I32.TruncSatF64S>()
        assertTrue(truncSat.isNotEmpty(), "Should parse i32.trunc_sat_f64_s")
    }

    @Test
    fun `i32 trunc_sat_f64_u — resource`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/trunc_sat_i32_f64.wat", 1)
        val truncSat = ops.filterIsInstance<I32.TruncSatF64U>()
        assertTrue(truncSat.isNotEmpty(), "Should parse i32.trunc_sat_f64_u")
    }

    // --- i64.trunc_sat_f32 ---

    @Test
    fun `i64 trunc_sat_f32_s — resource`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/trunc_sat_i64_f32.wat", 0)
        val truncSat = ops.filterIsInstance<I64.TruncSatF32S>()
        assertTrue(truncSat.isNotEmpty(), "Should parse i64.trunc_sat_f32_s")
    }

    @Test
    fun `i64 trunc_sat_f32_u — resource`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/trunc_sat_i64_f32.wat", 1)
        val truncSat = ops.filterIsInstance<I64.TruncSatF32U>()
        assertTrue(truncSat.isNotEmpty(), "Should parse i64.trunc_sat_f32_u")
    }

    // --- i64.trunc_sat_f64 ---

    @Test
    fun `i64 trunc_sat_f64_s — resource`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/trunc_sat_i64_f64.wat", 0)
        val truncSat = ops.filterIsInstance<I64.TruncSatF64S>()
        assertTrue(truncSat.isNotEmpty(), "Should parse i64.trunc_sat_f64_s")
    }

    @Test
    fun `i64 trunc_sat_f64_u — resource`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/trunc_sat_i64_f64.wat", 1)
        val truncSat = ops.filterIsInstance<I64.TruncSatF64U>()
        assertTrue(truncSat.isNotEmpty(), "Should parse i64.trunc_sat_f64_u")
    }

    // --- All 8 trunc_sat in single function ---

    @Test
    fun `all 8 trunc_sat variants in one function`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/trunc_sat_all.wat", 0)
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "No instructions should fail to parse: ${unparsed.firstOrNull()?.e?.message}")

        assertTrue(ops.any { it is I32.TruncSatF32S }, "Missing i32.trunc_sat_f32_s")
        assertTrue(ops.any { it is I32.TruncSatF32U }, "Missing i32.trunc_sat_f32_u")
        assertTrue(ops.any { it is I32.TruncSatF64S }, "Missing i32.trunc_sat_f64_s")
        assertTrue(ops.any { it is I32.TruncSatF64U }, "Missing i32.trunc_sat_f64_u")
        assertTrue(ops.any { it is I64.TruncSatF32S }, "Missing i64.trunc_sat_f32_s")
        assertTrue(ops.any { it is I64.TruncSatF32U }, "Missing i64.trunc_sat_f32_u")
        assertTrue(ops.any { it is I64.TruncSatF64S }, "Missing i64.trunc_sat_f64_s")
        assertTrue(ops.any { it is I64.TruncSatF64U }, "Missing i64.trunc_sat_f64_u")
    }

    // --- Inline WAT trunc_sat ---

    @Test
    fun `inline — i32 trunc_sat_f32_s`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32) (result i32)
                local.get 0
                i32.trunc_sat_f32_s
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "FC-prefix i32.trunc_sat_f32_s produced UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<I32.TruncSatF32S>(ops[1])
    }

    @Test
    fun `inline — i64 trunc_sat_f64_u`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f64) (result i64)
                local.get 0
                i64.trunc_sat_f64_u
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "FC-prefix i64.trunc_sat_f64_u produced UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<I64.TruncSatF64U>(ops[1])
    }

    @Test
    fun `inline — trunc_sat mixed in arithmetic`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param f32 f64) (result i32)
                local.get 0
                i32.trunc_sat_f32_s
                local.get 1
                i32.trunc_sat_f64_u
                i32.add
              )
            )
        """.trimIndent())
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "FC-prefix trunc_sat mix produced UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
        assertIs<I32.TruncSatF32S>(ops[1])
        assertIs<I32.TruncSatF64U>(ops[3])
        assertIs<I32.Add>(ops[4])
    }

    // ========================================================================
    // FB prefix — GC struct instructions
    // ========================================================================

    @Test
    fun `gc struct_new — creates struct instance`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_struct_ops.wat", 0)
        val structNew = ops.filterIsInstance<OpCodes.GC.StructNew>()
        assertTrue(structNew.isNotEmpty(), "Should parse struct.new")
    }

    @Test
    fun `gc struct_new_default — default init`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_struct_ops.wat", 1)
        val structNewDefault = ops.filterIsInstance<OpCodes.GC.StructNewDefault>()
        assertTrue(structNewDefault.isNotEmpty(), "Should parse struct.new_default")
    }

    @Test
    fun `gc struct_get — read field`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_struct_ops.wat", 2)
        val structGet = ops.filterIsInstance<OpCodes.GC.StructGet>()
        assertTrue(structGet.isNotEmpty(), "Should parse struct.get")
        assertEquals(0u, structGet[0].typeIndex, "Type index should be 0")
        assertEquals(0u, structGet[0].fieldIndex, "Field index should be 0 (${'$'}x)")
    }

    @Test
    fun `gc struct_set — write field`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_struct_ops.wat", 3)
        val structSet = ops.filterIsInstance<OpCodes.GC.StructSet>()
        assertTrue(structSet.isNotEmpty(), "Should parse struct.set")
        assertEquals(0u, structSet[0].typeIndex, "Type index should be 0")
        assertEquals(0u, structSet[0].fieldIndex, "Field index should be 0 (${'$'}x)")
    }

    // --- struct packed field access ---

    @Test
    fun `gc struct_get_s — sign-extending packed read`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_struct_packed.wat", 0)
        val structGetS = ops.filterIsInstance<OpCodes.GC.StructGetSignExtended>()
        assertTrue(structGetS.isNotEmpty(), "Should parse struct.get_s")
    }

    @Test
    fun `gc struct_get_u — zero-extending packed read`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_struct_packed.wat", 1)
        val structGetU = ops.filterIsInstance<OpCodes.GC.StructGetZeroExtended>()
        assertTrue(structGetU.isNotEmpty(), "Should parse struct.get_u")
    }

    // ========================================================================
    // FB prefix — GC array instructions
    // ========================================================================

    @Test
    fun `gc array_new — create array`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_ops.wat", 0)
        val arrayNew = ops.filterIsInstance<OpCodes.GC.ArrayNew>()
        assertTrue(arrayNew.isNotEmpty(), "Should parse array.new")
    }

    @Test
    fun `gc array_new_default — default init`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_ops.wat", 1)
        val arrayNewDefault = ops.filterIsInstance<OpCodes.GC.ArrayNewDefault>()
        assertTrue(arrayNewDefault.isNotEmpty(), "Should parse array.new_default")
    }

    @Test
    fun `gc array_new_fixed — fixed-size array`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_ops.wat", 2)
        val arrayNewFixed = ops.filterIsInstance<OpCodes.GC.ArrayNewFixed>()
        assertTrue(arrayNewFixed.isNotEmpty(), "Should parse array.new_fixed")
        assertEquals(3u, arrayNewFixed[0].value1, "Size should be 3")
    }

    @Test
    fun `gc array_get — read element`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_ops.wat", 3)
        val arrayGet = ops.filterIsInstance<OpCodes.GC.ArrayGet>()
        assertTrue(arrayGet.isNotEmpty(), "Should parse array.get")
    }

    @Test
    fun `gc array_set — write element`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_ops.wat", 4)
        val arraySet = ops.filterIsInstance<OpCodes.GC.ArraySet>()
        assertTrue(arraySet.isNotEmpty(), "Should parse array.set")
    }

    @Test
    fun `gc array_len — get length`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_ops.wat", 5)
        assertIs<OpCodes.GC.ArrayLength>(ops.last())
    }

    // --- array packed access ---

    @Test
    fun `gc array_get_s — sign-extending packed read`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_packed.wat", 0)
        val arrayGetS = ops.filterIsInstance<OpCodes.GC.ArrayGetSigned>()
        assertTrue(arrayGetS.isNotEmpty(), "Should parse array.get_s")
    }

    @Test
    fun `gc array_get_u — zero-extending packed read`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_packed.wat", 1)
        val arrayGetU = ops.filterIsInstance<OpCodes.GC.ArrayGetUnsigned>()
        assertTrue(arrayGetU.isNotEmpty(), "Should parse array.get_u")
    }

    // --- array bulk operations ---

    @Test
    fun `gc array_new_data — init from data segment`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_init.wat", 1)
        val arrayNewData = ops.filterIsInstance<OpCodes.GC.ArrayNewData>()
        assertTrue(arrayNewData.isNotEmpty(), "Should parse array.new_data")
    }

    @Test
    fun `gc array_fill`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_init.wat", 2)
        val arrayFill = ops.filterIsInstance<OpCodes.GC.ArrayFill>()
        assertTrue(arrayFill.isNotEmpty(), "Should parse array.fill")
    }

    @Test
    fun `gc array_copy`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_init.wat", 3)
        val arrayCopy = ops.filterIsInstance<OpCodes.GC.ArrayCopy>()
        assertTrue(arrayCopy.isNotEmpty(), "Should parse array.copy")
    }

    @Test
    fun `gc array_init_data`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_array_init.wat", 4)
        val arrayInitData = ops.filterIsInstance<OpCodes.GC.ArrayInitData>()
        assertTrue(arrayInitData.isNotEmpty(), "Should parse array.init_data")
    }

    // ========================================================================
    // FB prefix — GC i31 instructions
    // ========================================================================

    @Test
    fun `gc ref_i31 — box i32 to i31ref`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_i31_ops.wat", 0)
        assertIs<OpCodes.GC.RefI31>(ops.last())
    }

    @Test
    fun `gc i31_get_s — unbox signed`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_i31_ops.wat", 1)
        assertIs<OpCodes.GC.I31GetS>(ops.last())
    }

    @Test
    fun `gc i31_get_u — unbox unsigned`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_i31_ops.wat", 2)
        assertIs<OpCodes.GC.I31GetU>(ops.last())
    }

    // ========================================================================
    // FB prefix — GC ref.test and ref.cast
    // ========================================================================

    @Test
    fun `gc ref_test — non-nullable`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_ref_test_cast.wat", 0)
        val refTest = ops.filterIsInstance<OpCodes.GC.RefTest>()
        assertTrue(refTest.isNotEmpty(), "Should parse ref.test")
    }

    @Test
    fun `gc ref_test_nullable`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_ref_test_cast.wat", 1)
        val refTestNull = ops.filterIsInstance<OpCodes.GC.RefTestNullable>()
        assertTrue(refTestNull.isNotEmpty(), "Should parse ref.test (nullable)")
    }

    @Test
    fun `gc ref_cast — non-nullable`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_ref_test_cast.wat", 2)
        val refCast = ops.filterIsInstance<OpCodes.GC.RefCast>()
        assertTrue(refCast.isNotEmpty(), "Should parse ref.cast")
    }

    @Test
    fun `gc ref_cast_nullable`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_ref_test_cast.wat", 3)
        val refCastNull = ops.filterIsInstance<OpCodes.GC.RefCastNullable>()
        assertTrue(refCastNull.isNotEmpty(), "Should parse ref.cast (nullable)")
    }

    // ========================================================================
    // FB prefix — GC extern/any conversions
    // ========================================================================

    @Test
    fun `gc any_convert_extern`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_extern_convert.wat", 0)
        assertIs<OpCodes.GC.AnyConvertExtern>(ops.last())
    }

    @Test
    fun `gc extern_convert_any`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_extern_convert.wat", 1)
        assertIs<OpCodes.GC.ExternConvertAny>(ops.last())
    }

    // ========================================================================
    // FB prefix — GC br_on_cast
    // ========================================================================

    @Test
    fun `gc br_on_cast`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_br_on_cast.wat", 0)
        val allOps = flattenOpcodes(ops)
        val unparsed = allOps.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "br_on_cast produced UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
        val brOnCast = allOps.filterIsInstance<OpCodes.GC.BrOnCast>()
        assertTrue(brOnCast.isNotEmpty(), "Should parse br_on_cast (searched ${allOps.size} ops recursively)")
    }

    @Test
    fun `gc br_on_cast_fail`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/gc_br_on_cast.wat", 1)
        val allOps = flattenOpcodes(ops)
        val unparsed = allOps.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "br_on_cast_fail produced UnparsedOpCode: ${unparsed.firstOrNull()?.e?.message}")
        val brOnCastFail = allOps.filterIsInstance<OpCodes.GC.BrOnCastFail>()
        assertTrue(brOnCastFail.isNotEmpty(), "Should parse br_on_cast_fail (searched ${allOps.size} ops recursively)")
    }

    // ========================================================================
    // Inline WAT — GC instructions
    // ========================================================================

    @Test
    fun `inline — struct_new and struct_get`() = runTest {
        val buffer = watStringToBuffer("""
            (module
              (type ${'$'}point (struct (field ${'$'}x (mut i32)) (field ${'$'}y (mut i32))))
              (func (export "f") (result i32)
                (struct.get ${'$'}point ${'$'}x
                  (struct.new ${'$'}point (i32.const 10) (i32.const 20))
                )
              )
            )
        """.trimIndent())
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        val ops = records[0].opCodes
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "No unparsed opcodes: ${unparsed.firstOrNull()?.e?.message}")
        assertTrue(ops.any { it is OpCodes.GC.StructNew }, "Should have struct.new")
        assertTrue(ops.any { it is OpCodes.GC.StructGet }, "Should have struct.get")
    }

    @Test
    fun `inline — array_new_fixed and array_len`() = runTest {
        val buffer = watStringToBuffer("""
            (module
              (type ${'$'}arr (array (mut i32)))
              (func (export "f") (result i32)
                (array.len
                  (array.new_fixed ${'$'}arr 2 (i32.const 1) (i32.const 2))
                )
              )
            )
        """.trimIndent())
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        val ops = records[0].opCodes
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "No unparsed opcodes: ${unparsed.firstOrNull()?.e?.message}")
        assertTrue(ops.any { it is OpCodes.GC.ArrayNewFixed }, "Should have array.new_fixed")
        assertTrue(ops.any { it is OpCodes.GC.ArrayLength }, "Should have array.len")
    }

    @Test
    fun `inline — i31 roundtrip`() = runTest {
        val buffer = watStringToBuffer("""
            (module
              (func (export "f") (param i32) (result i32)
                local.get 0
                ref.i31
                i31.get_s
              )
            )
        """.trimIndent())
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        val ops = records[0].opCodes
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.GC.RefI31>(ops[1])
        assertIs<OpCodes.GC.I31GetS>(ops[2])
    }

    @Test
    fun `inline — extern and any conversions roundtrip`() = runTest {
        val buffer = watStringToBuffer("""
            (module
              (func (export "f") (param externref) (result externref)
                local.get 0
                any.convert_extern
                extern.convert_any
              )
            )
        """.trimIndent())
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        val ops = records[0].opCodes
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.GC.AnyConvertExtern>(ops[1])
        assertIs<OpCodes.GC.ExternConvertAny>(ops[2])
    }

    // ========================================================================
    // No UnparsedOpCode regression
    // ========================================================================

    @Test
    fun `trunc_sat should not produce UnparsedOpCode`() = runTest {
        val ops = parseFunctionOpcodes("wat/multibyte/trunc_sat_all.wat", 0)
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "FC-prefix instructions should all parse: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `gc struct ops should not produce UnparsedOpCode`() = runTest {
        val ops = parseOpcodes("wat/multibyte/gc_struct_ops.wat")
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "GC struct instructions should all parse: ${unparsed.firstOrNull()?.e?.message}")
    }

    @Test
    fun `gc array ops should not produce UnparsedOpCode`() = runTest {
        val ops = parseOpcodes("wat/multibyte/gc_array_ops.wat")
        val unparsed = ops.filterIsInstance<UnparsedOpCode>()
        assertTrue(unparsed.isEmpty(), "GC array instructions should all parse: ${unparsed.firstOrNull()?.e?.message}")
    }
}