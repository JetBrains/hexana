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
    // Inline WAT — GC struct extras (struct_new_default, struct_set, packed reads)
    // ========================================================================

    @Test
    fun `inline — struct_new_default and struct_set`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}point (struct (field ${'$'}x (mut i32))))
              (func (export "f") (param i32)
                struct.new_default ${'$'}point
                local.get 0
                struct.set ${'$'}point ${'$'}x
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val newDefault = ops.filterIsInstance<OpCodes.GC.StructNewDefault>().single()
        assertEquals(0u, newDefault.value, "type index = 0 (\$point)")
        val set = ops.filterIsInstance<OpCodes.GC.StructSet>().single()
        assertEquals(0u, set.typeIndex)
        assertEquals(0u, set.fieldIndex, "\$x is field 0")
    }

    @Test
    fun `inline — struct_get_s and struct_get_u on packed fields`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}rec (struct (field ${'$'}b (mut i8)) (field ${'$'}s (mut i16))))
              (func (export "f") (param (ref null ${'$'}rec)) (result i32)
                local.get 0
                struct.get_s ${'$'}rec ${'$'}b
                local.get 0
                struct.get_u ${'$'}rec ${'$'}s
                i32.add
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val getS = ops.filterIsInstance<OpCodes.GC.StructGetSignExtended>().single()
        assertEquals(0u, getS.typeIndex)
        assertEquals(0u, getS.fieldIndex, "\$b is field 0")
        val getU = ops.filterIsInstance<OpCodes.GC.StructGetZeroExtended>().single()
        assertEquals(0u, getU.typeIndex)
        assertEquals(1u, getU.fieldIndex, "\$s is field 1")
    }

    // ========================================================================
    // Inline WAT — GC array extras (new/get/set/fill/copy/init from data & elem)
    // ========================================================================

    @Test
    fun `inline — array_new`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}arr (array (mut i32)))
              (func (export "f") (param i32 i32) (result (ref ${'$'}arr))
                local.get 0
                local.get 1
                array.new ${'$'}arr
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val arrayNew = ops.filterIsInstance<OpCodes.GC.ArrayNew>().single()
        assertEquals(0u, arrayNew.value, "type index = 0 (\$arr)")
    }

    @Test
    fun `inline — array_new_default`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}arr (array (mut i32)))
              (func (export "f") (param i32) (result (ref ${'$'}arr))
                local.get 0
                array.new_default ${'$'}arr
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val arrayNewDefault = ops.filterIsInstance<OpCodes.GC.ArrayNewDefault>().single()
        assertEquals(0u, arrayNewDefault.value, "type index = 0 (\$arr)")
    }

    @Test
    fun `inline — array_get and array_set`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}arr (array (mut i32)))
              (func (export "f") (param (ref null ${'$'}arr) i32 i32) (result i32)
                local.get 0
                local.get 1
                local.get 2
                array.set ${'$'}arr
                local.get 0
                local.get 1
                array.get ${'$'}arr
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val set = ops.filterIsInstance<OpCodes.GC.ArraySet>().single()
        assertEquals(0u, set.value, "type index = 0")
        val get = ops.filterIsInstance<OpCodes.GC.ArrayGet>().single()
        assertEquals(0u, get.value, "type index = 0")
    }

    @Test
    fun `inline — array_get_s and array_get_u on packed bytes`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}bytes (array (mut i8)))
              (func (export "f") (param (ref null ${'$'}bytes) i32) (result i32)
                local.get 0
                local.get 1
                array.get_s ${'$'}bytes
                local.get 0
                local.get 1
                array.get_u ${'$'}bytes
                i32.add
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val getS = ops.filterIsInstance<OpCodes.GC.ArrayGetSigned>().single()
        assertEquals(0u, getS.value)
        val getU = ops.filterIsInstance<OpCodes.GC.ArrayGetUnsigned>().single()
        assertEquals(0u, getU.value)
    }

    @Test
    fun `inline — array_fill`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}arr (array (mut i32)))
              (func (export "f") (param (ref ${'$'}arr) i32 i32 i32)
                local.get 0
                local.get 1
                local.get 2
                local.get 3
                array.fill ${'$'}arr
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val fill = ops.filterIsInstance<OpCodes.GC.ArrayFill>().single()
        assertEquals(0u, fill.value, "type index = 0")
    }

    @Test
    fun `inline — array_copy`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}arr (array (mut i32)))
              (func (export "f") (param (ref ${'$'}arr) i32 (ref ${'$'}arr) i32 i32)
                local.get 0
                local.get 1
                local.get 2
                local.get 3
                local.get 4
                array.copy ${'$'}arr ${'$'}arr
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val copy = ops.filterIsInstance<OpCodes.GC.ArrayCopy>().single()
        assertEquals(0u, copy.typeIndexDst, "dst array type index = 0")
        assertEquals(0u, copy.typeIndexSrc, "src array type index = 0")
    }

    @Test
    fun `inline — array_new_data`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}arr (array (mut i32)))
              (data ${'$'}d "\01\02\03\04")
              (func (export "f") (result (ref ${'$'}arr))
                i32.const 0
                i32.const 1
                array.new_data ${'$'}arr ${'$'}d
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val newData = ops.filterIsInstance<OpCodes.GC.ArrayNewData>().single()
        assertEquals(0u, newData.typeIndexDst, "array type index = 0 (\$arr)")
        assertEquals(0u, newData.typeIndexSrc, "data segment index = 0 (\$d)")
    }

    @Test
    fun `inline — array_init_data`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}arr (array (mut i32)))
              (data ${'$'}d "\01\02\03\04")
              (func (export "f") (param (ref ${'$'}arr) i32 i32 i32)
                local.get 0
                local.get 1
                local.get 2
                local.get 3
                array.init_data ${'$'}arr ${'$'}d
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val init = ops.filterIsInstance<OpCodes.GC.ArrayInitData>().single()
        assertEquals(0u, init.value, "array type index = 0")
        assertEquals(0u, init.dataIndex, "data segment index = 0")
    }

    @Test
    fun `inline — array_new_elem`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}farr (array (mut funcref)))
              (elem ${'$'}e func ${'$'}f0)
              (func (export "f") (result (ref ${'$'}farr))
                i32.const 0
                i32.const 1
                array.new_elem ${'$'}farr ${'$'}e
              )
              (func ${'$'}f0 (nop))
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val newElem = ops.filterIsInstance<OpCodes.GC.ArrayNewElement>().single()
        assertEquals(0u, newElem.value, "array type index = 0")
        assertEquals(0u, newElem.elemIndex, "elem segment index = 0")
    }

    @Test
    fun `inline — array_init_elem`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}farr (array (mut funcref)))
              (elem ${'$'}e func ${'$'}f0)
              (func (export "f") (param (ref ${'$'}farr) i32 i32 i32)
                local.get 0
                local.get 1
                local.get 2
                local.get 3
                array.init_elem ${'$'}farr ${'$'}e
              )
              (func ${'$'}f0 (nop))
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val init = ops.filterIsInstance<OpCodes.GC.ArrayInitElem>().single()
        assertEquals(0u, init.value, "array type index = 0")
        assertEquals(0u, init.elemIndex, "elem segment index = 0")
    }

    // ========================================================================
    // Inline WAT — GC ref.test / ref.cast (heaptype carries target type index)
    // ========================================================================

    @Test
    fun `inline — ref_test non-nullable carries target type index`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}base (struct (field i32)))
              (type ${'$'}derived (sub ${'$'}base (struct (field i32) (field i32))))
              (func (export "f") (param (ref null ${'$'}base)) (result i32)
                local.get 0
                ref.test (ref ${'$'}derived)
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val refTest = ops.filterIsInstance<OpCodes.GC.RefTest>().single()
        assertEquals(1, refTest.value.type, "target heaptype = type 1 (\$derived)")
    }

    @Test
    fun `inline — ref_test nullable carries target type index`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}base (struct (field i32)))
              (type ${'$'}derived (sub ${'$'}base (struct (field i32) (field i32))))
              (func (export "f") (param (ref null ${'$'}base)) (result i32)
                local.get 0
                ref.test (ref null ${'$'}derived)
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val refTestNull = ops.filterIsInstance<OpCodes.GC.RefTestNullable>().single()
        assertEquals(1, refTestNull.value.type, "target heaptype = type 1 (\$derived)")
    }

    @Test
    fun `inline — ref_cast non-nullable carries target type index`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}base (struct (field i32)))
              (type ${'$'}derived (sub ${'$'}base (struct (field i32) (field i32))))
              (func (export "f") (param (ref null ${'$'}base)) (result (ref ${'$'}derived))
                local.get 0
                ref.cast (ref ${'$'}derived)
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val refCast = ops.filterIsInstance<OpCodes.GC.RefCast>().single()
        assertEquals(1, refCast.value.type, "target heaptype = type 1 (\$derived)")
    }

    @Test
    fun `inline — ref_cast nullable carries target type index`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (type ${'$'}base (struct (field i32)))
              (type ${'$'}derived (sub ${'$'}base (struct (field i32) (field i32))))
              (func (export "f") (param (ref null ${'$'}base)) (result (ref null ${'$'}derived))
                local.get 0
                ref.cast (ref null ${'$'}derived)
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val refCastNull = ops.filterIsInstance<OpCodes.GC.RefCastNullable>().single()
        assertEquals(1, refCastNull.value.type, "target heaptype = type 1 (\$derived)")
    }

    // ========================================================================
    // Inline WAT — GC i31 (unsigned unbox)
    // ========================================================================

    @Test
    fun `inline — i31_get_u unboxes unsigned`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i32)
                local.get 0
                ref.i31
                i31.get_u
              )
            )
        """.trimIndent())
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.GC.RefI31>(ops[1])
        assertIs<OpCodes.GC.I31GetU>(ops[2])
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

    // ========================================================================
    // Inline WAT — br_on_cast / br_on_cast_fail
    //
    // Binary encoding (GC proposal, merged into WASM 3.0):
    //   0xFB 0x18 castflags:u8 label:u32 src_ht:heaptype dst_ht:heaptype   ; br_on_cast
    //   0xFB 0x19 castflags:u8 label:u32 src_ht:heaptype dst_ht:heaptype   ; br_on_cast_fail
    //
    // castflags is a 2-bit bitmask:
    //   bit 0 (0x01) — source ref is nullable
    //   bit 1 (0x02) — target ref is nullable
    //
    // Tests below pin all four nullability combinations, verify subsequent
    // instructions parse at the right offset (catches byte-shift regressions
    // if the castflags read is ever removed), and exercise immediate values.
    // ========================================================================

    private suspend fun parseInlineFlattened(wat: String): List<OpCode> =
        flattenOpcodes(parseInlineOpcodes(wat))

    private fun brOnCastWat(srcRef: String, dstRef: String): String = """
        (module
          (type ${'$'}base (struct (field i32)))
          (type ${'$'}derived (sub ${'$'}base (struct (field i32) (field i32))))
          (func (export "f") (param (ref null ${'$'}base)) (result i32)
            (block ${'$'}L (result $dstRef)
              local.get 0
              br_on_cast ${'$'}L $srcRef $dstRef
              drop
              i32.const 7
              return
            )
            drop
            i32.const 11
          )
        )
    """.trimIndent()

    private fun brOnCastFailWat(srcRef: String, dstRef: String): String = """
        (module
          (type ${'$'}base (struct (field i32)))
          (type ${'$'}derived (sub ${'$'}base (struct (field i32) (field i32))))
          (func (export "f") (param (ref null ${'$'}base)) (result i32)
            (block ${'$'}L (result $srcRef)
              local.get 0
              br_on_cast_fail ${'$'}L $srcRef $dstRef
              drop
              i32.const 13
              return
            )
            drop
            i32.const 17
          )
        )
    """.trimIndent()

    @Test
    fun `inline — br_on_cast non-nullable to non-nullable (flags=0)`() = runTest {
        val ops = parseInlineFlattened(brOnCastWat("(ref ${'$'}base)", "(ref ${'$'}derived)"))
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val br = ops.filterIsInstance<OpCodes.GC.BrOnCast>().single()
        assertEquals(0.toByte(), br.flags, "neither side nullable")
    }

    @Test
    fun `inline — br_on_cast nullable to non-nullable (flags=1)`() = runTest {
        val ops = parseInlineFlattened(brOnCastWat("(ref null ${'$'}base)", "(ref ${'$'}derived)"))
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val br = ops.filterIsInstance<OpCodes.GC.BrOnCast>().single()
        assertEquals(0x01.toByte(), br.flags, "src nullable")
    }

    @Test
    fun `inline — br_on_cast nullable to nullable (flags=3)`() = runTest {
        val ops = parseInlineFlattened(brOnCastWat("(ref null ${'$'}base)", "(ref null ${'$'}derived)"))
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val br = ops.filterIsInstance<OpCodes.GC.BrOnCast>().single()
        assertEquals(0x03.toByte(), br.flags, "both sides nullable")
    }

    @Test
    fun `inline — br_on_cast carries label, src and dst type indices`() = runTest {
        val ops = parseInlineFlattened(brOnCastWat("(ref null ${'$'}base)", "(ref ${'$'}derived)"))
        val br = ops.filterIsInstance<OpCodes.GC.BrOnCast>().single()
        // Block defines label 0; br_on_cast targets it.
        assertEquals(0u, br.value, "label index targets the enclosing block")
        // $base = type 0, $derived = type 1 (declared in that order).
        assertEquals(0, br.srcType.type, "src heaptype = type index 0 (\$base)")
        assertEquals(1, br.dstType.type, "dst heaptype = type index 1 (\$derived)")
    }

    @Test
    fun `inline — instruction after br_on_cast parses at correct offset`() = runTest {
        // If the castflags byte were skipped during parsing, every byte after
        // br_on_cast would shift by one and the i32.const 7 below would either
        // come out wrong or produce an UnparsedOpCode. Pin both.
        val ops = parseInlineFlattened(brOnCastWat("(ref null ${'$'}base)", "(ref ${'$'}derived)"))
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no byte-shift past br_on_cast")
        val constants = ops.filterIsInstance<I32.Const>()
        assertTrue(constants.any { it.value == 7 }, "i32.const 7 (after br_on_cast) parsed correctly")
        assertTrue(constants.any { it.value == 11 }, "i32.const 11 (after block) parsed correctly")
    }

    @Test
    fun `inline — br_on_cast_fail non-nullable to non-nullable (flags=0)`() = runTest {
        val ops = parseInlineFlattened(brOnCastFailWat("(ref ${'$'}base)", "(ref ${'$'}derived)"))
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val br = ops.filterIsInstance<OpCodes.GC.BrOnCastFail>().single()
        assertEquals(0.toByte(), br.flags, "neither side nullable")
    }

    @Test
    fun `inline — br_on_cast_fail nullable to non-nullable (flags=1)`() = runTest {
        val ops = parseInlineFlattened(brOnCastFailWat("(ref null ${'$'}base)", "(ref ${'$'}derived)"))
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val br = ops.filterIsInstance<OpCodes.GC.BrOnCastFail>().single()
        assertEquals(0x01.toByte(), br.flags, "src nullable")
    }

    @Test
    fun `inline — br_on_cast_fail nullable to nullable (flags=3)`() = runTest {
        val ops = parseInlineFlattened(brOnCastFailWat("(ref null ${'$'}base)", "(ref null ${'$'}derived)"))
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no UnparsedOpCode")
        val br = ops.filterIsInstance<OpCodes.GC.BrOnCastFail>().single()
        assertEquals(0x03.toByte(), br.flags, "both sides nullable")
    }

    @Test
    fun `inline — instruction after br_on_cast_fail parses at correct offset`() = runTest {
        val ops = parseInlineFlattened(brOnCastFailWat("(ref null ${'$'}base)", "(ref ${'$'}derived)"))
        assertTrue(ops.filterIsInstance<UnparsedOpCode>().isEmpty(), "no byte-shift past br_on_cast_fail")
        val constants = ops.filterIsInstance<I32.Const>()
        assertTrue(constants.any { it.value == 13 }, "i32.const 13 (after br_on_cast_fail) parsed correctly")
        assertTrue(constants.any { it.value == 17 }, "i32.const 17 (after block) parsed correctly")
    }
}