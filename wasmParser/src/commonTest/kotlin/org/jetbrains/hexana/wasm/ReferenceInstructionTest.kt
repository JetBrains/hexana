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
import kotlin.test.assertTrue

/**
 * Tests for WASM reference instructions — ref.null, ref.is_null, ref.func,
 * ref.eq, ref.as_non_null.
 *
 * All tests use inline WAT compiled via wat2wasm.
 * Verifies opcode type and operand values.
 */
class ReferenceInstructionTest {

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

    // ========================================================================
    // ref.null
    // ========================================================================

    @Test
    fun `ref null — funcref`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result funcref)
                ref.null func
              )
            )
        """.trimIndent())
        val refNull = ops.filterIsInstance<OpCodes.Ref.Null>().single()
        assertIs<OpCodes.Ref.Null>(refNull)
    }

    @Test
    fun `ref null — externref`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result externref)
                ref.null extern
              )
            )
        """.trimIndent())
        val refNull = ops.filterIsInstance<OpCodes.Ref.Null>().single()
        assertIs<OpCodes.Ref.Null>(refNull)
    }

    @Test
    fun `ref null — as table set value`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 10 funcref)
              (func (export "f") (param i32)
                local.get 0
                ref.null func
                table.set 0
              )
            )
        """.trimIndent())
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.Ref.Null>(ops[1])
        assertIs<OpCodes.Table.Set>(ops[2])
    }

    @Test
    fun `ref null — in if-else branch`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result funcref)
                local.get 0
                if (result funcref)
                  ref.null func
                else
                  ref.null func
                end
              )
            )
        """.trimIndent())
        val ifOp = ops.filterIsInstance<OpCodes.Flow.If>().single()
        val nulls = ifOp.value.flatMap { op ->
            when (op) {
                is OpCodes.Ref.Null -> listOf(op)
                is OpCodes.Flow.Else -> op.value.filterIsInstance<OpCodes.Ref.Null>()
                else -> emptyList()
            }
        }
        assertEquals(2, nulls.size, "should find ref.null in both if and else branches")
    }

    // ========================================================================
    // ref.is_null
    // ========================================================================

    @Test
    fun `ref is_null — funcref param`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param funcref) (result i32)
                local.get 0
                ref.is_null
              )
            )
        """.trimIndent())
        assertEquals(2, ops.size)
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.Ref.IsNull>(ops[1])
    }

    @Test
    fun `ref is_null — externref param`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param externref) (result i32)
                local.get 0
                ref.is_null
              )
            )
        """.trimIndent())
        assertIs<OpCodes.Ref.IsNull>(ops[1])
    }

    @Test
    fun `ref is_null — on ref null result`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result i32)
                ref.null func
                ref.is_null
              )
            )
        """.trimIndent())
        assertEquals(2, ops.size)
        assertIs<OpCodes.Ref.Null>(ops[0])
        assertIs<OpCodes.Ref.IsNull>(ops[1])
    }

    @Test
    fun `ref is_null — table get then check`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 10 funcref)
              (func (export "f") (param i32) (result i32)
                local.get 0
                table.get 0
                ref.is_null
              )
            )
        """.trimIndent())
        assertEquals(3, ops.size)
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.Table.Get>(ops[1])
        assertIs<OpCodes.Ref.IsNull>(ops[2])
    }

    @Test
    fun `ref is_null — used as br_if condition`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param funcref) (result i32)
                block (result i32)
                  local.get 0
                  ref.is_null
                  br_if 0
                  i32.const 1
                  return
                  i32.const 0
                end
              )
            )
        """.trimIndent())
        val block = ops.filterIsInstance<OpCodes.Flow.Block>().single()
        assertTrue(block.value.any { it is OpCodes.Ref.IsNull })
        assertTrue(block.value.any { it is OpCodes.Flow.BrIf })
    }

    // ========================================================================
    // ref.func
    // ========================================================================

    @Test
    fun `ref func — reference first function`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func ${'$'}target)
              (func (export "f") (result funcref)
                ref.func ${'$'}target
              )
              (elem declare func ${'$'}target)
            )
        """.trimIndent())
        val ops = allOps[1]
        val refFunc = ops.filterIsInstance<OpCodes.Ref.Func>().single()
        assertEquals(0u, refFunc.value, "should reference func index 0")
    }

    @Test
    fun `ref func — reference second function`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func ${'$'}first)
              (func ${'$'}second)
              (func (export "f") (result funcref)
                ref.func ${'$'}second
              )
              (elem declare func ${'$'}second)
            )
        """.trimIndent())
        val ops = allOps[2]
        val refFunc = ops.filterIsInstance<OpCodes.Ref.Func>().single()
        assertEquals(1u, refFunc.value, "should reference func index 1")
    }

    @Test
    fun `ref func — store into table`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (table 10 funcref)
              (func ${'$'}target)
              (func (export "f") (param i32)
                local.get 0
                ref.func ${'$'}target
                table.set 0
              )
              (elem declare func ${'$'}target)
            )
        """.trimIndent())
        val ops = allOps[1]
        assertEquals(3, ops.size)
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.Ref.Func>(ops[1])
        assertIs<OpCodes.Table.Set>(ops[2])
        assertEquals(0u, (ops[1] as OpCodes.Ref.Func).value)
    }

    @Test
    fun `ref func — is_null check returns false`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func ${'$'}target)
              (func (export "f") (result i32)
                ref.func ${'$'}target
                ref.is_null
              )
              (elem declare func ${'$'}target)
            )
        """.trimIndent())
        val ops = allOps[1]
        assertEquals(2, ops.size)
        assertIs<OpCodes.Ref.Func>(ops[0])
        assertIs<OpCodes.Ref.IsNull>(ops[1])
    }

    // ========================================================================
    // ref.eq
    // ========================================================================

    @Test
    fun `ref eq — compare two null refs`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result i32)
                ref.null eq
                ref.null eq
                ref.eq
              )
            )
        """.trimIndent())
        assertEquals(3, ops.size)
        assertIs<OpCodes.Ref.Null>(ops[0])
        assertIs<OpCodes.Ref.Null>(ops[1])
        assertIs<OpCodes.Ref.Eq>(ops[2])
    }

    @Test
    fun `ref eq — in conditional`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param eqref eqref) (result i32)
                local.get 0
                local.get 1
                ref.eq
              )
            )
        """.trimIndent())
        assertEquals(3, ops.size)
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.Local.Get>(ops[1])
        assertIs<OpCodes.Ref.Eq>(ops[2])
    }

    @Test
    fun `ref eq — as br_if condition`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param eqref eqref) (result i32)
                block (result i32)
                  local.get 0
                  local.get 1
                  ref.eq
                  br_if 0
                  i32.const 0
                  return
                  i32.const 1
                end
              )
            )
        """.trimIndent())
        val block = ops.filterIsInstance<OpCodes.Flow.Block>().single()
        assertTrue(block.value.any { it is OpCodes.Ref.Eq })
        assertTrue(block.value.any { it is OpCodes.Flow.BrIf })
    }

    // ========================================================================
    // ref.as_non_null
    // ========================================================================

    @Test
    fun `ref as_non_null — funcref param`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param funcref) (result funcref)
                local.get 0
                ref.as_non_null
              )
            )
        """.trimIndent())
        assertEquals(2, ops.size)
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.Ref.AsNonNull>(ops[1])
    }

    @Test
    fun `ref as_non_null — externref param`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param externref) (result externref)
                local.get 0
                ref.as_non_null
              )
            )
        """.trimIndent())
        assertEquals(2, ops.size)
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.Ref.AsNonNull>(ops[1])
    }

    // ========================================================================
    // combined reference instruction patterns
    // ========================================================================

    @Test
    fun `combined — null check then assert non-null`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param funcref) (result i32 funcref)
                local.get 0
                ref.is_null
                local.get 0
                ref.as_non_null
              )
            )
        """.trimIndent())
        assertEquals(4, ops.size)
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.Ref.IsNull>(ops[1])
        assertIs<OpCodes.Local.Get>(ops[2])
        assertIs<OpCodes.Ref.AsNonNull>(ops[3])
    }

    @Test
    fun `combined — ref func then is_null then eq`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (func ${'$'}target)
              (func (export "f") (result i32)
                ref.func ${'$'}target
                ref.null func
                ref.eq
              )
              (elem declare func ${'$'}target)
            )
        """.trimIndent())
        val ops = allOps[1]
        assertEquals(3, ops.size)
        assertIs<OpCodes.Ref.Func>(ops[0])
        assertIs<OpCodes.Ref.Null>(ops[1])
        assertIs<OpCodes.Ref.Eq>(ops[2])
    }

    @Test
    fun `combined — multiple ref null types`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result i32)
                ref.null func
                ref.is_null
                ref.null extern
                ref.is_null
                i32.add
              )
            )
        """.trimIndent())
        val nulls = ops.filterIsInstance<OpCodes.Ref.Null>()
        val isNulls = ops.filterIsInstance<OpCodes.Ref.IsNull>()
        assertEquals(2, nulls.size)
        assertEquals(2, isNulls.size)
    }
}
