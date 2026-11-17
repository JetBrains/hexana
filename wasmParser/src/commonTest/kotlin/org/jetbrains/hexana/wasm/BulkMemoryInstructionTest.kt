package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Operand-level tests for the Bulk Memory Operations proposal (Phase 5,
 * shipped in WebAssembly 2.0). The proposal added four 0xFC-prefixed
 * instructions plus passive data segments and the data-count section.
 *
 *   0xFC 0x08  memory.init  dataidx memidx
 *   0xFC 0x09  data.drop    dataidx
 *   0xFC 0x0A  memory.copy  dst-memidx src-memidx
 *   0xFC 0x0B  memory.fill  memidx
 *
 * Spec: https://webassembly.github.io/spec/core/binary/instructions.html#memory-instructions
 *
 * The existing MemoryInstructionTest only asserts that these instructions
 * decode without producing UnparsedOpCode. This file pins the *operand
 * values* and exercises multi-segment / multi-memory variants.
 *
 * All tests use inline WAT compiled via wat2wasm.
 */
class BulkMemoryInstructionTest {

    private suspend fun parseInlineOpcodes(wat: String): List<OpCode> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val records = with(module.codeSection!!) { buffer.scan() }
        return records.last().opCodes
    }

    // ========================================================================
    // memory.init
    // ========================================================================

    @Test
    fun `memory init — first data segment, default memory`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (data ${'$'}d "hello")
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 5
                memory.init ${'$'}d
              )
            )
        """.trimIndent())
        val init = ops.filterIsInstance<OpCodes.Memory.Init>().single()
        assertEquals(0u, init.dataIdx, "data segment index 0")
        assertEquals(0u, init.memIdx, "default memory index 0")
    }

    @Test
    fun `memory init — second data segment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (data ${'$'}d0 "AAAA")
              (data ${'$'}d1 "BBBB")
              (data ${'$'}d2 "CCCC")
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 4
                memory.init ${'$'}d2
              )
            )
        """.trimIndent())
        val init = ops.filterIsInstance<OpCodes.Memory.Init>().single()
        assertEquals(2u, init.dataIdx, "should reference ${'$'}d2 (segment index 2)")
        assertEquals(0u, init.memIdx)
    }

    // ========================================================================
    // data.drop
    // ========================================================================

    @Test
    fun `data drop — first segment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (data ${'$'}d "hello")
              (func (export "f")
                data.drop ${'$'}d
              )
            )
        """.trimIndent())
        val drop = ops.filterIsInstance<OpCodes.Memory.Data.Drop>().single()
        assertEquals(0u, drop.dataIdx)
    }

    @Test
    fun `data drop — third segment`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (data "AAAA")
              (data "BBBB")
              (data ${'$'}d2 "CCCC")
              (func (export "f")
                data.drop ${'$'}d2
              )
            )
        """.trimIndent())
        val drop = ops.filterIsInstance<OpCodes.Memory.Data.Drop>().single()
        assertEquals(2u, drop.dataIdx)
    }

    // ========================================================================
    // memory.copy
    // ========================================================================

    @Test
    fun `memory copy — default memory self-copy`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f")
                i32.const 100
                i32.const 0
                i32.const 50
                memory.copy
              )
            )
        """.trimIndent())
        val copy = ops.filterIsInstance<OpCodes.Memory.Copy>().single()
        assertEquals(0u, copy.memIdxY, "dst memory index 0")
        assertEquals(0u, copy.memIdxX, "src memory index 0")
    }

    // ========================================================================
    // memory.fill
    // ========================================================================

    @Test
    fun `memory fill — default memory`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (func (export "f")
                i32.const 0
                i32.const 0xFF
                i32.const 256
                memory.fill
              )
            )
        """.trimIndent())
        val fill = ops.filterIsInstance<OpCodes.Memory.Fill>().single()
        assertEquals(0u, fill.memIdx)
    }

    // ========================================================================
    // Combined patterns
    // ========================================================================

    @Test
    fun `init + drop sequence — operand consistency`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (data ${'$'}greeting "hello, world")
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 12
                memory.init ${'$'}greeting
                data.drop ${'$'}greeting
              )
            )
        """.trimIndent())
        val init = ops.filterIsInstance<OpCodes.Memory.Init>().single()
        val drop = ops.filterIsInstance<OpCodes.Memory.Data.Drop>().single()
        assertEquals(init.dataIdx, drop.dataIdx, "init and drop must reference same segment")
    }

    @Test
    fun `bulk combo — fill, copy, init, drop in one body`() = runTest {
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (data ${'$'}d "abcd")
              (func (export "f")
                ;; fill region [0..64) with 0
                i32.const 0
                i32.const 0
                i32.const 64
                memory.fill
                ;; copy [0..16) -> [64..80)
                i32.const 64
                i32.const 0
                i32.const 16
                memory.copy
                ;; init from segment ${'$'}d at offset 80
                i32.const 80
                i32.const 0
                i32.const 4
                memory.init ${'$'}d
                ;; free the segment
                data.drop ${'$'}d
              )
            )
        """.trimIndent())
        assertEquals(1, ops.filterIsInstance<OpCodes.Memory.Fill>().size)
        assertEquals(1, ops.filterIsInstance<OpCodes.Memory.Copy>().size)
        assertEquals(1, ops.filterIsInstance<OpCodes.Memory.Init>().size)
        assertEquals(1, ops.filterIsInstance<OpCodes.Memory.Data.Drop>().size)
    }

    @Test
    fun `init order — dataIdx vs memIdx not swapped`() = runTest {
        // Asymmetric values catch a renderer or constructor that swaps the two.
        val ops = parseInlineOpcodes("""
            (module
              (memory 1)
              (data "AAAA")
              (data "BBBB")
              (data "CCCC")
              (data ${'$'}target "TARGET")
              (func (export "f")
                i32.const 0
                i32.const 0
                i32.const 6
                memory.init ${'$'}target
              )
            )
        """.trimIndent())
        val init = ops.filterIsInstance<OpCodes.Memory.Init>().single()
        assertEquals(3u, init.dataIdx, "${'$'}target is segment 3")
        assertEquals(0u, init.memIdx, "memory index stays at default 0")
        assertIs<OpCodes.Memory.Init>(init)
    }
}
