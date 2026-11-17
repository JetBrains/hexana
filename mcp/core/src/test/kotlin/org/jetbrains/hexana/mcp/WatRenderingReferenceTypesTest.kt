package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.table.Element
import org.jetbrains.hexana.wasm.opcodes.table.Table
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for WAT rendering of the Reference Types instructions.
 *
 * Audit (see top-level conversation) flagged that `Table.*` and `Element.Drop`
 * fall through to the reflective fallback in `wat.kt`. For most of the
 * `Table.*` opcodes the fallback happens to produce the right mnemonic, but
 * two cases are wrong:
 *
 *  - `Element.Drop` renders as `element.drop` instead of the spec mnemonic
 *    `elem.drop` — the class lives in the package `...opcodes.table.Element`
 *    and the mnemonic-from-class-name path lowercases to `element`.
 *
 *  - `Table.Init` renders its two operands in BINARY order (elem, table) but
 *    spec WAT is TABLE first: `table.init <tableidx> <elemidx>`.
 *
 * These tests pin the spec-correct output. The `elem.drop` and `table.init`
 * tests are EXPECTED TO FAIL TODAY and will pass once the renderer is fixed.
 *
 * Spec: https://webassembly.github.io/spec/core/text/instructions.html#table-instructions
 */
class WatRenderingReferenceTypesTest {

    private fun wat(op: OpCode): String = listOf(op).wat()

    // ========================================================================
    // Currently-correct: single-operand table.* mnemonics
    // ========================================================================

    @Test
    fun `table get renders mnemonic and table index`() {
        assertEquals("table.get 0", wat(Table.Get(0u, offset = 0)))
    }

    @Test
    fun `table set renders mnemonic and non-zero table index`() {
        assertEquals("table.set 3", wat(Table.Set(3u, offset = 0)))
    }

    @Test
    fun `table grow renders mnemonic and table index`() {
        assertEquals("table.grow 0", wat(Table.Grow(0u, offset = 0)))
    }

    @Test
    fun `table size renders mnemonic and table index`() {
        assertEquals("table.size 1", wat(Table.Size(1u, offset = 0)))
    }

    @Test
    fun `table fill renders mnemonic and table index`() {
        assertEquals("table.fill 2", wat(Table.Fill(2u, offset = 0)))
    }

    // ========================================================================
    // Two-operand table.copy — operand order matches binary AND WAT spec here
    //   binary:   0xFC 0x0E dst src
    //   wat:      table.copy <dst> <src>
    // (constructor stores them in that order, so renderer is correct.)
    // ========================================================================

    @Test
    fun `table copy renders dst then src`() {
        assertEquals("table.copy 0 1", wat(Table.Copy(tableIdx0 = 0u, tableIdx1 = 1u, offset = 0)))
    }

    // ========================================================================
    // GAP: table.init — operand-order bug
    //   binary:   0xFC 0x0C elemidx tableidx
    //   wat:      table.init <tableidx> <elemidx>   ← TABLE FIRST in text
    //
    // Constructor argument order matches binary (elementIdx, tableIdx); the
    // generic OpCodeWithTwoValue renderer prints them as `value0 value1`,
    // so today the rendered output is `table.init <elem> <table>`.
    //
    // This test pins the spec-correct ordering. EXPECTED TO FAIL TODAY.
    // ========================================================================

    @Test
    fun `table init renders table index before element index per spec WAT`() {
        val op = Table.Init(elementIdx = 7u, tableIdx = 3u, offset = 0)
        assertEquals("table.init 3 7", wat(op))
    }

    // ========================================================================
    // GAP: elem.drop — mnemonic bug
    //
    // Class lives at `org.jetbrains.hexana.wasm.opcodes.table.Element$Drop`.
    // The reflective fallback splits the simple name by `$` and joins with `.`,
    // producing `element.drop`. Spec mnemonic is `elem.drop`.
    //
    // EXPECTED TO FAIL TODAY.
    // ========================================================================

    @Test
    fun `elem drop renders spec mnemonic 'elem dot drop'`() {
        assertEquals("elem.drop 0", wat(Element.Drop(0u, offset = 0)))
    }

    @Test
    fun `elem drop renders non-zero element index`() {
        assertEquals("elem.drop 5", wat(Element.Drop(5u, offset = 0)))
    }

    // ========================================================================
    // Joined sequence — round-trip a Reference Types-heavy block
    // ========================================================================

    @Test
    fun `combined render — table init then elem drop`() {
        val ops: List<OpCode> = listOf(
            Table.Init(elementIdx = 0u, tableIdx = 0u, offset = 0),
            Element.Drop(0u, offset = 0)
        )
        assertEquals(
            """
            table.init 0 0
            elem.drop 0
            """.trimIndent(),
            ops.wat()
        )
    }
}
