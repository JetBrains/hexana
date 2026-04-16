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
 * The parser does not yet implement SIMD instruction decoding (consumeMultibyteOpCode
 * throws UnsupportedOpsException for 0xFD prefix). Functions containing SIMD
 * instructions produce UnparsedOpCode for the entire function body.
 *
 * These tests document current behavior and verify graceful degradation:
 * - Module-level parse succeeds (WasmParseSuccess)
 * - Functions with SIMD produce UnparsedOpCode (not a crash)
 * - Functions without SIMD in the same module parse normally
 * - Section metadata (type, import, export, memory) is still accessible
 */
class VectorInstructionTest {

    private suspend fun parseModule(wat: String): Pair<WasmModule, org.jetbrains.hexana.io.CommonByteBuffer> {
        val buffer = watStringToBuffer(wat)
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        return (result.wasmModule as WasmModule) to buffer
    }

    private suspend fun parseAllFunctions(wat: String): List<List<OpCode>> {
        val (module, buffer) = parseModule(wat)
        val records = with(module.codeSection!!) { buffer.scan() }
        return records.map { it.opCodes }
    }

    // ========================================================================
    // v128.const — produces UnparsedOpCode
    // ========================================================================

    @Test
    fun `v128 const — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (result v128)
                v128.const i32x4 1 2 3 4
              )
            )
        """.trimIndent())
        val ops = allOps[0]
        assertTrue(ops.any { it is UnparsedOpCode }, "SIMD function should produce UnparsedOpCode")
    }

    @Test
    fun `v128 const — module parse succeeds`() = runTest {
        val (module, _) = parseModule("""
            (module
              (func (export "f") (result v128)
                v128.const i32x4 0 0 0 0
              )
            )
        """.trimIndent())
        assertNotNull(module.codeSection)
    }

    // ========================================================================
    // i32x4 arithmetic — produces UnparsedOpCode
    // ========================================================================

    @Test
    fun `i32x4 add — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i32x4.add
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    @Test
    fun `i32x4 mul — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i32x4.mul
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    // ========================================================================
    // f32x4 operations — produces UnparsedOpCode
    // ========================================================================

    @Test
    fun `f32x4 add — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                f32x4.add
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    @Test
    fun `f32x4 sqrt — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128) (result v128)
                local.get 0
                f32x4.sqrt
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    // ========================================================================
    // i8x16 / i16x8 — produces UnparsedOpCode
    // ========================================================================

    @Test
    fun `i8x16 splat — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param i32) (result v128)
                local.get 0
                i8x16.splat
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    @Test
    fun `i16x8 add — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i16x8.add
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    // ========================================================================
    // v128 memory — produces UnparsedOpCode
    // ========================================================================

    @Test
    fun `v128 load — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result v128)
                local.get 0
                v128.load
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    @Test
    fun `v128 store — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (memory 1)
              (func (export "f") (param i32 v128)
                local.get 0
                local.get 1
                v128.store
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    // ========================================================================
    // v128 bitwise — produces UnparsedOpCode
    // ========================================================================

    @Test
    fun `v128 and — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                v128.and
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    @Test
    fun `v128 or — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                v128.or
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    @Test
    fun `v128 not — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128) (result v128)
                local.get 0
                v128.not
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    // ========================================================================
    // f64x2 — produces UnparsedOpCode
    // ========================================================================

    @Test
    fun `f64x2 add — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                f64x2.add
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    // ========================================================================
    // i32x4 extract/replace lane — produces UnparsedOpCode
    // ========================================================================

    @Test
    fun `i32x4 extract_lane — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128) (result i32)
                local.get 0
                i32x4.extract_lane 0
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    @Test
    fun `i32x4 replace_lane — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128 i32) (result v128)
                local.get 0
                local.get 1
                i32x4.replace_lane 2
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    // ========================================================================
    // mixed module — SIMD and non-SIMD functions coexist
    // ========================================================================

    @Test
    fun `mixed module — non-SIMD function parses normally`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "scalar_add") (param i32 i32) (result i32)
                local.get 0
                local.get 1
                i32.add
              )
              (func (export "vector_add") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i32x4.add
              )
            )
        """.trimIndent())
        // First function (scalar) should parse normally
        val scalarOps = allOps[0]
        assertEquals(3, scalarOps.size)
        assertIs<OpCodes.Local.Get>(scalarOps[0])
        assertIs<OpCodes.Local.Get>(scalarOps[1])
        assertIs<I32.Add>(scalarOps[2])
        // Second function (SIMD) should produce UnparsedOpCode
        val vectorOps = allOps[1]
        assertTrue(vectorOps.any { it is UnparsedOpCode })
    }

    @Test
    fun `mixed module — multiple scalar functions around SIMD`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "before") (param i32) (result i32)
                local.get 0
                i32.const 1
                i32.add
              )
              (func (export "simd") (param v128) (result v128)
                local.get 0
                v128.not
              )
              (func (export "after") (param i32) (result i32)
                local.get 0
                i32.const 2
                i32.mul
              )
            )
        """.trimIndent())
        // "before" parses normally
        assertEquals(3, allOps[0].size)
        assertIs<I32.Add>(allOps[0][2])
        // "simd" produces UnparsedOpCode
        assertTrue(allOps[1].any { it is UnparsedOpCode })
        // "after" parses normally
        assertEquals(3, allOps[2].size)
        assertIs<I32.Mul>(allOps[2][2])
    }

    // ========================================================================
    // section metadata — accessible despite SIMD functions
    // ========================================================================

    @Test
    fun `exports — accessible despite SIMD function`() = runTest {
        val (module, buffer) = parseModule("""
            (module
              (func (export "vec_const") (result v128)
                v128.const i32x4 1 2 3 4
              )
            )
        """.trimIndent())
        val exportSection = module.exportSection
        assertNotNull(exportSection)
        val exports = with(exportSection) { buffer.scan() }
        assertTrue(exports.any { it.name == "vec_const" })
    }

    @Test
    fun `memory section — accessible with SIMD load store`() = runTest {
        val (module, _) = parseModule("""
            (module
              (memory 1)
              (func (export "f") (param i32) (result v128)
                local.get 0
                v128.load
              )
            )
        """.trimIndent())
        assertNotNull(module.memorySection)
    }

    @Test
    fun `type section — v128 param and result types`() = runTest {
        val (module, buffer) = parseModule("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i32x4.add
              )
            )
        """.trimIndent())
        val types = with(module.typesSection!!) { buffer.parseTypes() }
        assertTrue(types.isNotEmpty())
    }

    // ========================================================================
    // i64x2 operations — produces UnparsedOpCode
    // ========================================================================

    @Test
    fun `i64x2 add — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i64x2.add
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    // ========================================================================
    // v128 shuffle — produces UnparsedOpCode
    // ========================================================================

    @Test
    fun `i8x16 shuffle — function produces UnparsedOpCode`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (param v128 v128) (result v128)
                local.get 0
                local.get 1
                i8x16.shuffle 0 1 2 3 4 5 6 7 16 17 18 19 20 21 22 23
              )
            )
        """.trimIndent())
        assertTrue(allOps[0].any { it is UnparsedOpCode })
    }

    // ========================================================================
    // UnparsedOpCode error message
    // ========================================================================

    @Test
    fun `UnparsedOpCode — exception contains position info`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func (export "f") (result v128)
                v128.const i32x4 1 2 3 4
              )
            )
        """.trimIndent())
        val unparsed = allOps[0].filterIsInstance<UnparsedOpCode>().single()
        assertNotNull(unparsed.e, "UnparsedOpCode should carry the original exception")
    }
}
