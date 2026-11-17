package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.table.Table
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for WASM variable instructions — local.get, local.set, local.tee,
 * global.get, global.set, table.get, table.set.
 *
 * All tests use inline WAT compiled via wat2wasm.
 * Verifies opcode type and operand index.
 */
class VariableInstructionTest {

    private suspend fun parseInlineOpcodes(wat: String): List<OpCode> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        return records[0].opCodes
    }

    // ========================================================================
    // local.get
    // ========================================================================

    @Test
    fun `local get — param 0`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i32)
                local.get 0
              )
            )
        """.trimIndent())
        val get = ops.filterIsInstance<OpCodes.Local.Get>().single()
        assertEquals(0u, get.value)
    }

    @Test
    fun `local get — param 1`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i64) (result i64)
                local.get 1
              )
            )
        """.trimIndent())
        val get = ops.filterIsInstance<OpCodes.Local.Get>().single()
        assertEquals(1u, get.value)
    }

    @Test
    fun `local get — declared local`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i32)
                (local i32)
                local.get 1
              )
            )
        """.trimIndent())
        val get = ops.filterIsInstance<OpCodes.Local.Get>().single()
        assertEquals(1u, get.value, "local index 1 is the declared local")
    }

    @Test
    fun `local get — multiple locals`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32 i32) (result i32)
                (local i32 i64 f32)
                local.get 4
              )
            )
        """.trimIndent())
        val get = ops.filterIsInstance<OpCodes.Local.Get>().single()
        assertEquals(4u, get.value, "local index 4 = param0, param1, local0, local1, local2")
    }

    // ========================================================================
    // local.set
    // ========================================================================

    @Test
    fun `local set — param 0`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32)
                i32.const 42
                local.set 0
              )
            )
        """.trimIndent())
        val set = ops.filterIsInstance<OpCodes.Local.Set>().single()
        assertEquals(0u, set.value)
    }

    @Test
    fun `local set — declared local`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32)
                (local i32)
                i32.const 99
                local.set 1
              )
            )
        """.trimIndent())
        val set = ops.filterIsInstance<OpCodes.Local.Set>().single()
        assertEquals(1u, set.value)
    }

    // ========================================================================
    // local.tee
    // ========================================================================

    @Test
    fun `local tee — sets and returns value`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i32)
                i32.const 7
                local.tee 0
              )
            )
        """.trimIndent())
        val tee = ops.filterIsInstance<OpCodes.Local.Tee>().single()
        assertEquals(0u, tee.value)
    }

    @Test
    fun `local tee — declared local`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result i32)
                (local i32)
                i32.const 5
                local.tee 0
              )
            )
        """.trimIndent())
        val tee = ops.filterIsInstance<OpCodes.Local.Tee>().single()
        assertEquals(0u, tee.value)
    }

    // ========================================================================
    // local.get + local.set + local.tee combined
    // ========================================================================

    @Test
    fun `local instructions — get, set, tee in sequence`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (param i32) (result i32)
                (local i32)
                local.get 0
                local.tee 1
                local.get 1
                i32.add
                local.set 1
                local.get 1
              )
            )
        """.trimIndent())
        val gets = ops.filterIsInstance<OpCodes.Local.Get>()
        val sets = ops.filterIsInstance<OpCodes.Local.Set>()
        val tees = ops.filterIsInstance<OpCodes.Local.Tee>()
        assertEquals(3, gets.size)
        assertEquals(1, sets.size)
        assertEquals(1, tees.size)
        assertEquals(0u, gets[0].value)
        assertEquals(1u, tees[0].value)
        assertEquals(1u, gets[1].value)
        assertEquals(1u, sets[0].value)
        assertEquals(1u, gets[2].value)
    }

    // ========================================================================
    // global.get
    // ========================================================================

    @Test
    fun `global get — immutable global`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (global i32 (i32.const 42))
              (func (export "f") (result i32)
                global.get 0
              )
            )
        """.trimIndent())
        val get = ops.filterIsInstance<OpCodes.Global.Get>().single()
        assertEquals(0u, get.value)
    }

    @Test
    fun `global get — second global`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (global i32 (i32.const 10))
              (global i64 (i64.const 20))
              (func (export "f") (result i64)
                global.get 1
              )
            )
        """.trimIndent())
        val get = ops.filterIsInstance<OpCodes.Global.Get>().single()
        assertEquals(1u, get.value)
    }

    // ========================================================================
    // global.set
    // ========================================================================

    @Test
    fun `global set — mutable global`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (global (mut i32) (i32.const 0))
              (func (export "f")
                i32.const 99
                global.set 0
              )
            )
        """.trimIndent())
        val set = ops.filterIsInstance<OpCodes.Global.Set>().single()
        assertEquals(0u, set.value)
    }

    @Test
    fun `global set — second mutable global`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (global (mut i32) (i32.const 0))
              (global (mut i64) (i64.const 0))
              (func (export "f")
                i64.const 123
                global.set 1
              )
            )
        """.trimIndent())
        val set = ops.filterIsInstance<OpCodes.Global.Set>().single()
        assertEquals(1u, set.value)
    }

    // ========================================================================
    // global.get + global.set combined
    // ========================================================================

    @Test
    fun `global get and set — increment pattern`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (global (mut i32) (i32.const 0))
              (func (export "f")
                global.get 0
                i32.const 1
                i32.add
                global.set 0
              )
            )
        """.trimIndent())
        val gets = ops.filterIsInstance<OpCodes.Global.Get>()
        val sets = ops.filterIsInstance<OpCodes.Global.Set>()
        assertEquals(1, gets.size)
        assertEquals(1, sets.size)
        assertEquals(0u, gets[0].value)
        assertEquals(0u, sets[0].value)
    }

    // ========================================================================
    // table.get
    // ========================================================================

    @Test
    fun `table get — funcref table`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 1 funcref)
              (func (export "f") (param i32) (result funcref)
                local.get 0
                table.get 0
              )
            )
        """.trimIndent())
        val get = ops.filterIsInstance<Table.Get>().single()
        assertEquals(0u, get.value)
    }

    // ========================================================================
    // table.set
    // ========================================================================

    @Test
    fun `table set — funcref table`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 1 funcref)
              (func (export "f") (param i32 funcref)
                local.get 0
                local.get 1
                table.set 0
              )
            )
        """.trimIndent())
        val set = ops.filterIsInstance<Table.Set>().single()
        assertEquals(0u, set.value)
    }

    // ========================================================================
    // mixed variable instructions
    // ========================================================================

    @Test
    fun `mixed — locals and globals in one function`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (global (mut i32) (i32.const 100))
              (func (export "f") (param i32) (result i32)
                (local i32)
                global.get 0
                local.get 0
                i32.add
                local.tee 1
                global.set 0
                local.get 1
              )
            )
        """.trimIndent())
        val localGets = ops.filterIsInstance<OpCodes.Local.Get>()
        val localSets = ops.filterIsInstance<OpCodes.Local.Set>()
        val localTees = ops.filterIsInstance<OpCodes.Local.Tee>()
        val globalGets = ops.filterIsInstance<OpCodes.Global.Get>()
        val globalSets = ops.filterIsInstance<OpCodes.Global.Set>()
        assertEquals(2, localGets.size)
        assertEquals(0, localSets.size)
        assertEquals(1, localTees.size)
        assertEquals(1, globalGets.size)
        assertEquals(1, globalSets.size)
    }

    @Test
    fun `local get — high index`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (func (export "f") (result i32)
                (local i32 i32 i32 i32 i32 i32 i32 i32 i32 i32)
                local.get 9
              )
            )
        """.trimIndent())
        val get = ops.filterIsInstance<OpCodes.Local.Get>().single()
        assertEquals(9u, get.value)
    }
}
