package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for WAT rendering of the four Bulk Memory Operations instructions.
 *
 * Spec: https://webassembly.github.io/spec/core/text/instructions.html#memory-instructions
 *
 *   memory.init <dataIdx> [<memIdx>]   ;; memidx defaults to 0 when implicit
 *   data.drop   <dataIdx>
 *   memory.copy [<dstMemIdx> <srcMemIdx>]
 *   memory.fill [<memIdx>]
 *
 * `wat.kt` has no explicit `when` arms for any of these; they all fall to the
 * reflective fallback. The fallback paths today produce:
 *
 *   memory.init      → "memory.init"      (operand dataIdx is silently lost)
 *   memory.copy      → "memory.copy"      (correct for default memory)
 *   memory.fill      → "memory.fill"      (correct for default memory)
 *   data.drop        → "memory.data.drop" (wrong mnemonic AND no operand)
 *
 * The tests below pin the spec-correct output. Two are EXPECTED TO FAIL TODAY:
 *
 *  - `memory.init` — operand dataIdx missing.
 *  - `data.drop`   — wrong mnemonic AND operand missing.
 *
 * The `memory.copy` / `memory.fill` default-memory tests are GREEN today
 * because the spec text for default memory is just the bare mnemonic.
 */
class WatRenderingBulkMemoryTest {

    private fun wat(op: OpCode): String = listOf(op).wat()

    // ========================================================================
    // memory.init — GAP: dataIdx operand dropped
    // ========================================================================

    @Test
    fun `memory init renders mnemonic and data segment index`() {
        val op = OpCodes.Memory.Init(dataIdx = 0u, memIdx = 0u, offset = 0)
        assertEquals("memory.init 0", wat(op))
    }

    @Test
    fun `memory init renders non-zero data segment index`() {
        val op = OpCodes.Memory.Init(dataIdx = 7u, memIdx = 0u, offset = 0)
        assertEquals("memory.init 7", wat(op))
    }

    // ========================================================================
    // data.drop — GAP: wrong mnemonic ("memory.data.drop") AND missing operand
    // ========================================================================

    @Test
    fun `data drop renders spec mnemonic with data segment index`() {
        val op = OpCodes.Memory.Data.Drop(dataIdx = 0u, offset = 0)
        assertEquals("data.drop 0", wat(op))
    }

    @Test
    fun `data drop renders non-zero data segment index`() {
        val op = OpCodes.Memory.Data.Drop(dataIdx = 5u, offset = 0)
        assertEquals("data.drop 5", wat(op))
    }

    // ========================================================================
    // memory.copy — GREEN today for default memory; renderer drops indices
    //               (acceptable when both are 0 since spec WAT shorthand
    //                emits no operands in that case).
    // ========================================================================

    @Test
    fun `memory copy renders bare mnemonic for default memory`() {
        val op = OpCodes.Memory.Copy(memIdxY = 0u, memIdxX = 0u, offset = 0)
        assertEquals("memory.copy", wat(op))
    }

    // ========================================================================
    // memory.fill — GREEN today for default memory.
    // ========================================================================

    @Test
    fun `memory fill renders bare mnemonic for default memory`() {
        val op = OpCodes.Memory.Fill(memIdx = 0u, offset = 0)
        assertEquals("memory.fill", wat(op))
    }

    // ========================================================================
    // Combined sequence — round-trip the typical init pattern
    // ========================================================================

    @Test
    fun `combined render — memory init then data drop`() {
        val ops: List<OpCode> = listOf(
            OpCodes.Memory.Init(dataIdx = 2u, memIdx = 0u, offset = 0),
            OpCodes.Memory.Data.Drop(dataIdx = 2u, offset = 0)
        )
        assertEquals(
            """
            memory.init 2
            data.drop 2
            """.trimIndent(),
            ops.wat()
        )
    }
}
