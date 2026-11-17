package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.table.Element
import org.jetbrains.hexana.wasm.opcodes.table.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Tests for the bulk-table / element-drop instructions added by the
 * Reference Types proposal:
 *
 *   0xFC 0x0C  table.init  elemidx tableidx
 *   0xFC 0x0D  elem.drop   elemidx
 *   0xFC 0x0E  table.copy  dstidx srcidx
 *   0xFC 0x0F  table.grow  tableidx
 *   0xFC 0x10  table.size  tableidx
 *   0xFC 0x11  table.fill  tableidx
 *
 * Spec: https://webassembly.github.io/spec/core/binary/instructions.html#table-instructions
 *
 * Existing TableInstructionTest covers only `table.get` / `table.set`;
 * this file fills in the remaining six. Inline WAT is compiled via wat2wasm.
 */
class BulkTableInstructionTest {

    private suspend fun parseInlineOpcodes(wat: String): List<OpCode> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        return records.last().opCodes
    }

    // ========================================================================
    // table.init
    // ========================================================================

    @Test
    fun `table init — table 0, element segment 0`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 8 funcref)
              (func ${'$'}target)
              (elem ${'$'}seg func ${'$'}target)
              (func (export "f")
                i32.const 0   ;; dst offset in table
                i32.const 0   ;; src offset in segment
                i32.const 1   ;; len
                table.init 0 ${'$'}seg
              )
            )
        """.trimIndent())
        val init = ops.filterIsInstance<Table.Init>().single()
        assertEquals(0u, init.value0, "elementIdx (binary order)")
        assertEquals(0u, init.value1, "tableIdx (binary order)")
    }

    @Test
    fun `table init — second table, second element segment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table ${'$'}t0 4 funcref)
              (table ${'$'}t1 4 funcref)
              (func ${'$'}f0)
              (func ${'$'}f1)
              (elem ${'$'}s0 func ${'$'}f0)
              (elem ${'$'}s1 func ${'$'}f1)
              (func (export "init_t1_s1")
                i32.const 0
                i32.const 0
                i32.const 1
                table.init ${'$'}t1 ${'$'}s1
              )
            )
        """.trimIndent())
        val init = ops.filterIsInstance<Table.Init>().single()
        assertEquals(1u, init.value0, "elementIdx ${'$'}s1 = 1")
        assertEquals(1u, init.value1, "tableIdx ${'$'}t1 = 1")
    }

    // ========================================================================
    // elem.drop
    // ========================================================================

    @Test
    fun `elem drop — first segment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 4 funcref)
              (func ${'$'}f0)
              (elem ${'$'}s0 func ${'$'}f0)
              (func (export "drop")
                elem.drop ${'$'}s0
              )
            )
        """.trimIndent())
        val drop = ops.filterIsInstance<Element.Drop>().single()
        assertEquals(0u, drop.value)
    }

    @Test
    fun `elem drop — third segment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 4 funcref)
              (func ${'$'}f)
              (elem ${'$'}s0 func ${'$'}f)
              (elem ${'$'}s1 func ${'$'}f)
              (elem ${'$'}s2 func ${'$'}f)
              (func (export "drop")
                elem.drop ${'$'}s2
              )
            )
        """.trimIndent())
        val drop = ops.filterIsInstance<Element.Drop>().single()
        assertEquals(2u, drop.value)
    }

    // ========================================================================
    // table.copy
    // ========================================================================

    @Test
    fun `table copy — single table self-copy`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 8 funcref)
              (func (export "f")
                i32.const 0   ;; dst
                i32.const 4   ;; src
                i32.const 2   ;; len
                table.copy 0 0
              )
            )
        """.trimIndent())
        val copy = ops.filterIsInstance<Table.Copy>().single()
        assertEquals(0u, copy.value0, "dst tableIdx")
        assertEquals(0u, copy.value1, "src tableIdx")
    }

    @Test
    fun `table copy — between two tables`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table ${'$'}dst 4 funcref)
              (table ${'$'}src 4 funcref)
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 1
                table.copy ${'$'}dst ${'$'}src
              )
            )
        """.trimIndent())
        val copy = ops.filterIsInstance<Table.Copy>().single()
        assertEquals(0u, copy.value0, "dst = ${'$'}dst (table 0)")
        assertEquals(1u, copy.value1, "src = ${'$'}src (table 1)")
    }

    // ========================================================================
    // table.grow
    // ========================================================================

    @Test
    fun `table grow — funcref table`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 1 funcref)
              (func (export "g") (result i32)
                ref.null func
                i32.const 4
                table.grow 0
              )
            )
        """.trimIndent())
        val grow = ops.filterIsInstance<Table.Grow>().single()
        assertEquals(0u, grow.value)
    }

    @Test
    fun `table grow — second externref table`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 1 funcref)
              (table 1 externref)
              (func (export "g") (result i32)
                ref.null extern
                i32.const 8
                table.grow 1
              )
            )
        """.trimIndent())
        val grow = ops.filterIsInstance<Table.Grow>().single()
        assertEquals(1u, grow.value)
    }

    // ========================================================================
    // table.size
    // ========================================================================

    @Test
    fun `table size — table 0`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 3 funcref)
              (func (export "s") (result i32)
                table.size 0
              )
            )
        """.trimIndent())
        assertEquals(1, ops.size)
        val size = assertIs<Table.Size>(ops.single())
        assertEquals(0u, size.value)
    }

    @Test
    fun `table size — second table`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 3 funcref)
              (table 5 externref)
              (func (export "s") (result i32)
                table.size 1
              )
            )
        """.trimIndent())
        val size = ops.filterIsInstance<Table.Size>().single()
        assertEquals(1u, size.value)
    }

    // ========================================================================
    // table.fill
    // ========================================================================

    @Test
    fun `table fill — externref with null`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 8 externref)
              (func (export "f")
                i32.const 0
                ref.null extern
                i32.const 4
                table.fill 0
              )
            )
        """.trimIndent())
        val fill = ops.filterIsInstance<Table.Fill>().single()
        assertEquals(0u, fill.value)
        assertIs<I32.Const>(ops[0])
        assertIs<OpCodes.Ref.Null>(ops[1])
        assertIs<I32.Const>(ops[2])
    }

    @Test
    fun `table fill — second table`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 4 funcref)
              (table 4 externref)
              (func (export "f")
                i32.const 0
                ref.null extern
                i32.const 1
                table.fill 1
              )
            )
        """.trimIndent())
        val fill = ops.filterIsInstance<Table.Fill>().single()
        assertEquals(1u, fill.value)
    }

    // ========================================================================
    // combined: table.size then table.grow
    // ========================================================================

    @Test
    fun `bulk combo — size then grow then size`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 1 funcref)
              (func (export "f") (result i32)
                table.size 0
                drop
                ref.null func
                i32.const 4
                table.grow 0
                drop
                table.size 0
              )
            )
        """.trimIndent())
        assertEquals(2, ops.filterIsInstance<Table.Size>().size)
        assertEquals(1, ops.filterIsInstance<Table.Grow>().size)
    }

    // ========================================================================
    // combined: table.init followed by elem.drop
    // ========================================================================

    @Test
    fun `bulk combo — init then drop`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (table 4 funcref)
              (func ${'$'}f)
              (elem ${'$'}s func ${'$'}f)
              (func (export "load_then_free")
                i32.const 0
                i32.const 0
                i32.const 1
                table.init 0 ${'$'}s
                elem.drop ${'$'}s
              )
            )
        """.trimIndent())
        val init = ops.filterIsInstance<Table.Init>().single()
        val drop = ops.filterIsInstance<Element.Drop>().single()
        assertEquals(0u, init.value0)
        assertEquals(0u, init.value1)
        assertEquals(0u, drop.value)
    }
}
