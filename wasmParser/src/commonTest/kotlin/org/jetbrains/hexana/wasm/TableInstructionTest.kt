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

/**
 * Tests for WASM table instructions — table.get, table.set.
 *
 * All tests use inline WAT compiled via wat2wasm.
 * Verifies opcode type and table index operand.
 */
class TableInstructionTest {

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
    // table.get — funcref
    // ========================================================================

    @Test
    fun `table get — funcref table index 0`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 10 funcref)
              (func (export "f") (param i32) (result funcref)
                local.get 0
                table.get 0
              )
            )
        """.trimIndent())
        assertIs<OpCodes.Local.Get>(ops[0])
        val get = ops.filterIsInstance<OpCodes.Table.Get>().single()
        assertEquals(0u, get.value)
    }

    @Test
    fun `table get — second table`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 1 funcref)
              (table 1 externref)
              (func (export "f") (param i32) (result externref)
                local.get 0
                table.get 1
              )
            )
        """.trimIndent())
        val get = ops.filterIsInstance<OpCodes.Table.Get>().single()
        assertEquals(1u, get.value, "should reference table index 1")
    }

    @Test
    fun `table get — externref table`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 5 externref)
              (func (export "f") (param i32) (result externref)
                local.get 0
                table.get 0
              )
            )
        """.trimIndent())
        val get = ops.filterIsInstance<OpCodes.Table.Get>().single()
        assertEquals(0u, get.value)
    }

    @Test
    fun `table get — with constant index`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 10 funcref)
              (func (export "f") (result funcref)
                i32.const 3
                table.get 0
              )
            )
        """.trimIndent())
        assertIs<I32.Const>(ops[0])
        assertEquals(3, (ops[0] as I32.Const).value)
        val get = ops.filterIsInstance<OpCodes.Table.Get>().single()
        assertEquals(0u, get.value)
    }

    // ========================================================================
    // table.set — funcref
    // ========================================================================

    @Test
    fun `table set — funcref table index 0`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 10 funcref)
              (func (export "f") (param i32 funcref)
                local.get 0
                local.get 1
                table.set 0
              )
            )
        """.trimIndent())
        val set = ops.filterIsInstance<OpCodes.Table.Set>().single()
        assertEquals(0u, set.value)
    }

    @Test
    fun `table set — second table`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 1 funcref)
              (table 1 externref)
              (func (export "f") (param i32 externref)
                local.get 0
                local.get 1
                table.set 1
              )
            )
        """.trimIndent())
        val set = ops.filterIsInstance<OpCodes.Table.Set>().single()
        assertEquals(1u, set.value, "should reference table index 1")
    }

    @Test
    fun `table set — externref table`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 5 externref)
              (func (export "f") (param i32 externref)
                local.get 0
                local.get 1
                table.set 0
              )
            )
        """.trimIndent())
        val set = ops.filterIsInstance<OpCodes.Table.Set>().single()
        assertEquals(0u, set.value)
    }

    @Test
    fun `table set — with ref null`() = runTest {
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
        val set = ops.filterIsInstance<OpCodes.Table.Set>().single()
        assertEquals(0u, set.value)
    }

    // ========================================================================
    // table.get + table.set combined
    // ========================================================================

    @Test
    fun `table get and set — swap two slots`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 10 funcref)
              (func (export "swap") (param i32 i32)
                (local funcref)
                ;; tmp = table[a]
                local.get 0
                table.get 0
                local.set 2
                ;; table[a] = table[b]
                local.get 0
                local.get 1
                table.get 0
                table.set 0
                ;; table[b] = tmp
                local.get 1
                local.get 2
                table.set 0
              )
            )
        """.trimIndent())
        val gets = ops.filterIsInstance<OpCodes.Table.Get>()
        val sets = ops.filterIsInstance<OpCodes.Table.Set>()
        assertEquals(2, gets.size, "two table.get instructions")
        assertEquals(2, sets.size, "two table.set instructions")
        gets.forEach { assertEquals(0u, it.value) }
        sets.forEach { assertEquals(0u, it.value) }
    }

    @Test
    fun `table get and set — copy between tables`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 5 externref)
              (table 5 externref)
              (func (export "copy") (param i32 i32)
                ;; table1[param1] = table0[param0]
                local.get 1
                local.get 0
                table.get 0
                table.set 1
              )
            )
        """.trimIndent())
        val get = ops.filterIsInstance<OpCodes.Table.Get>().single()
        val set = ops.filterIsInstance<OpCodes.Table.Set>().single()
        assertEquals(0u, get.value, "get from table 0")
        assertEquals(1u, set.value, "set into table 1")
    }

    // ========================================================================
    // table.get with call_indirect
    // ========================================================================

    @Test
    fun `table with call_indirect — funcref lookup and indirect call`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (type (func (param i32) (result i32)))
              (table 2 funcref)
              (func ${'$'}double (type 0) (param i32) (result i32)
                local.get 0
                i32.const 2
                i32.mul
              )
              (func (export "call_table") (param i32) (result i32)
                local.get 0
                i32.const 0
                call_indirect (type 0)
              )
              (elem (i32.const 0) func ${'$'}double)
            )
        """.trimIndent())
        val ops = allOps[1]
        val callIndirect = ops.filterIsInstance<OpCodes.Flow.CallIndirect>().single()
        assertIs<OpCodes.Flow.CallIndirect>(callIndirect)
    }

    // ========================================================================
    // table with elem segment initialization
    // ========================================================================

    @Test
    fun `table get — after elem initialization`() = runTest {
        val allOps = parseAllFunctions("""
            (module
              (type (func (result i32)))
              (table 2 funcref)
              (func ${'$'}fortytwo (result i32) i32.const 42)
              (func (export "f") (result funcref)
                i32.const 0
                table.get 0
              )
              (elem (i32.const 0) func ${'$'}fortytwo)
            )
        """.trimIndent())
        val ops = allOps[1]
        assertIs<I32.Const>(ops[0])
        val get = ops.filterIsInstance<OpCodes.Table.Get>().single()
        assertEquals(0u, get.value)
    }

    // ========================================================================
    // table instructions — opcode sequence verification
    // ========================================================================

    @Test
    fun `table get — full opcode sequence`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 10 funcref)
              (func (export "f") (param i32) (result funcref)
                local.get 0
                table.get 0
              )
            )
        """.trimIndent())
        assertEquals(2, ops.size)
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.Table.Get>(ops[1])
    }

    @Test
    fun `table set — full opcode sequence`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 10 externref)
              (func (export "f") (param i32 externref)
                local.get 0
                local.get 1
                table.set 0
              )
            )
        """.trimIndent())
        assertEquals(3, ops.size)
        assertIs<OpCodes.Local.Get>(ops[0])
        assertIs<OpCodes.Local.Get>(ops[1])
        assertIs<OpCodes.Table.Set>(ops[2])
    }

    @Test
    fun `table set — with ref func`() = runTest {
        val ops = parseAllFunctions("""
            (module
              (table 10 funcref)
              (func ${'$'}noop)
              (func (export "f") (param i32)
                local.get 0
                ref.func ${'$'}noop
                table.set 0
              )
              (elem declare func ${'$'}noop)
            )
        """.trimIndent())
        val fnOps = ops[1]
        assertIs<OpCodes.Local.Get>(fnOps[0])
        assertIs<OpCodes.Ref.Func>(fnOps[1])
        assertIs<OpCodes.Table.Set>(fnOps[2])
        assertEquals(0u, (fnOps[2] as OpCodes.Table.Set).value)
    }

    @Test
    fun `table get — ref is_null check`() = runTest {
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
}
