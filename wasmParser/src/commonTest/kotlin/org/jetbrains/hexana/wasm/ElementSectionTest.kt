package org.jetbrains.hexana.wasm

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.tests.watStringToBuffer
import org.jetbrains.hexana.wasm.sections.ExprWasmElementSegment
import org.jetbrains.hexana.wasm.sections.FuncIdxWasmElementSegment
import org.jetbrains.hexana.wasm.sections.WasmElementSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for the element section across all eight encoding flags introduced by
 * the Reference Types proposal.
 *
 * Spec: https://webassembly.github.io/spec/core/binary/modules.html#element-section
 *
 * The first byte of every segment is a u32 flag in the range 0..7; the
 * remaining payload depends on it:
 *
 * | flag | mode        | table?   | elements as | extra header byte           |
 * |------|-------------|----------|-------------|-----------------------------|
 * | 0    | active      | implicit | funcidx     | —                           |
 * | 1    | passive     | —        | funcidx     | elemkind (must be 0x00)     |
 * | 2    | active      | explicit | funcidx     | elemkind (must be 0x00)     |
 * | 3    | declarative | —        | funcidx     | elemkind (must be 0x00)     |
 * | 4    | active      | implicit | expr        | —                           |
 * | 5    | passive     | —        | expr        | reftype                     |
 * | 6    | active      | explicit | expr        | reftype                     |
 * | 7    | declarative | —        | expr        | reftype                     |
 *
 * The first half of this file pins each flag value via inline WAT.
 *
 * The second half exposes the *parser gap* documented in the audit: for flags
 * 1, 2, and 3 the elemkind byte is read but neither validated nor stored
 * (`WasmSectionElement.kt:41,49,55` — `val rt = get()`). Spec section 5.5.12
 * permits only `0x00`. The negative tests below feed an illegal byte and assert
 * that the parser rejects it — these tests are EXPECTED TO FAIL TODAY and will
 * pass once the gap is closed (either by validating or by surfacing the byte
 * in the data model so a downstream consumer can validate).
 */
class ElementSectionTest {

    private suspend fun parseSegments(wat: String): List<WasmElementSegment> {
        val buffer = watStringToBuffer(wat)
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val section = module.elementSection ?: fail("module has no element section")
        return with(section) { buffer.scanElements() }
    }

    // ========================================================================
    // Flag matrix — one segment per flag, asserted by sealed-class subtype
    // ========================================================================

    @Test
    fun `flag 0 — active default, funcidx`() = runTest {
        val segments = parseSegments("""
            (module
              (table 4 funcref)
              (func ${'$'}f)
              (elem (i32.const 0) func ${'$'}f)
            )
        """.trimIndent())
        val seg = assertIs<FuncIdxWasmElementSegment.ActiveDefault>(segments.single())
        assertEquals(listOf(0u), seg.funcs)
        assertTrue(seg.offsetExpr.isNotEmpty(), "offset expression must be parsed")
    }

    @Test
    fun `flag 1 — passive, funcidx`() = runTest {
        val segments = parseSegments("""
            (module
              (table 4 funcref)
              (func ${'$'}f)
              (elem func ${'$'}f)
            )
        """.trimIndent())
        val seg = assertIs<FuncIdxWasmElementSegment.Passive>(segments.single())
        assertEquals(listOf(0u), seg.funcs)
    }

    @Test
    fun `flag 2 — active explicit table, funcidx`() = runTest {
        val segments = parseSegments("""
            (module
              (table ${'$'}t0 4 funcref)
              (table ${'$'}t1 4 funcref)
              (func ${'$'}f)
              (elem (table ${'$'}t1) (i32.const 0) func ${'$'}f)
            )
        """.trimIndent())
        val seg = assertIs<FuncIdxWasmElementSegment.ActiveExplicit>(segments.single())
        assertEquals(1u, seg.tableIndex, "explicit table index = 1")
        assertEquals(listOf(0u), seg.funcs)
    }

    @Test
    fun `flag 3 — declarative, funcidx`() = runTest {
        val segments = parseSegments("""
            (module
              (func ${'$'}f)
              (elem declare func ${'$'}f)
            )
        """.trimIndent())
        val seg = assertIs<FuncIdxWasmElementSegment.Declare>(segments.single())
        assertEquals(listOf(0u), seg.funcs)
    }

    @Test
    fun `flag 4 — active default, expression form`() = runTest {
        val segments = parseSegments("""
            (module
              (table 4 funcref)
              (func ${'$'}f)
              (elem (i32.const 0) funcref (ref.func ${'$'}f))
            )
        """.trimIndent())
        val seg = assertIs<ExprWasmElementSegment.ActiveDefault>(segments.single())
        assertEquals(1, seg.exprs.size)
    }

    @Test
    fun `flag 5 — passive, expression form (externref)`() = runTest {
        val segments = parseSegments("""
            (module
              (table 4 externref)
              (elem externref (ref.null extern))
            )
        """.trimIndent())
        val seg = assertIs<ExprWasmElementSegment.Passive>(segments.single())
        assertEquals(1, seg.exprs.size)
    }

    @Test
    fun `flag 6 — active explicit table, expression form`() = runTest {
        val segments = parseSegments("""
            (module
              (table ${'$'}t0 4 funcref)
              (table ${'$'}t1 4 funcref)
              (func ${'$'}f)
              (elem (table ${'$'}t1) (i32.const 0) funcref (ref.func ${'$'}f))
            )
        """.trimIndent())
        val seg = assertIs<ExprWasmElementSegment.ActiveExplicit>(segments.single())
        assertEquals(1u, seg.tableIndex)
        assertEquals(1, seg.exprs.size)
    }

    @Test
    fun `flag 7 — declarative, expression form`() = runTest {
        val segments = parseSegments("""
            (module
              (func ${'$'}f)
              (elem declare funcref (ref.func ${'$'}f))
            )
        """.trimIndent())
        val seg = assertIs<ExprWasmElementSegment.Declare>(segments.single())
        assertEquals(1, seg.exprs.size)
    }

    // ========================================================================
    // Multi-segment & multi-flag mix
    // ========================================================================

    @Test
    fun `multi-segment — flags 0 and 3 in one module`() = runTest {
        val segments = parseSegments("""
            (module
              (table 4 funcref)
              (func ${'$'}f)
              (elem (i32.const 0) func ${'$'}f)
              (elem declare func ${'$'}f)
            )
        """.trimIndent())
        assertEquals(2, segments.size)
        assertIs<FuncIdxWasmElementSegment.ActiveDefault>(segments[0])
        assertIs<FuncIdxWasmElementSegment.Declare>(segments[1])
    }

    // ========================================================================
    // Multi-table support — table imports stay valid alongside locals
    // ========================================================================

    @Test
    fun `multiple tables — local + imported tables coexist`() = runTest {
        val buffer = watStringToBuffer("""
            (module
              (import "env" "imp_t" (table 1 funcref))
              (table 2 funcref)
              (table 3 externref)
            )
        """.trimIndent())
        val module = (WasmParser().parse(buffer) as WasmParseSuccess).wasmModule as WasmModule
        val tableSection = module.tableSection ?: fail("expected table section")
        val tables = with(tableSection) { buffer.scanTable() }
        assertEquals(2, tables.size, "two locally-defined tables")
    }

    // ========================================================================
    // PARSER GAP — illegal elemkind byte for flags 1 / 2 / 3
    //
    // Per WasmSectionElement.kt the parser does `val rt = get()` but never
    // validates or stores the byte. Spec only allows 0x00 (funcref elemkind).
    // The tests below feed 0xFF and expect rejection. They will FAIL until the
    // gap is closed.
    // ========================================================================

    /**
     * Minimal valid WASM module ending in an element section with one
     * passive (flag 1) segment whose elemkind is the supplied byte.
     */
    private fun moduleWithPassiveElemkind(elemkindByte: Int): ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,    // magic + version
        // Type section: () -> ()
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        // Function section: 1 func, type 0
        0x03, 0x02, 0x01, 0x00,
        // Element section: 1 passive segment, elemkind=<arg>, funcs=[0]
        0x09, 0x05,
        0x01,                                              // 1 segment
        0x01,                                              // flag = 1 (passive)
        elemkindByte.toByte(),                             // elemkind
        0x01, 0x00,                                        // funcs: [0]
        // Code section: empty body
        0x0A, 0x04, 0x01, 0x02, 0x00, 0x0B
    )

    private fun moduleWithActiveExplicitElemkind(elemkindByte: Int): ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x04, 0x04, 0x01, 0x70, 0x00, 0x01,                // table: 1 entry, funcref, limits min=1
        0x09, 0x09,
        0x01,                                              // 1 segment
        0x02,                                              // flag = 2 (active explicit)
        0x00,                                              // tableidx 0
        0x41, 0x00, 0x0B,                                  // i32.const 0; end
        elemkindByte.toByte(),                             // elemkind
        0x01, 0x00,                                        // funcs: [0]
        0x0A, 0x04, 0x01, 0x02, 0x00, 0x0B
    )

    private fun moduleWithDeclarativeElemkind(elemkindByte: Int): ByteArray = byteArrayOf(
        0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00,
        0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x09, 0x05,
        0x01,                                              // 1 segment
        0x03,                                              // flag = 3 (declarative)
        elemkindByte.toByte(),                             // elemkind
        0x01, 0x00,                                        // funcs: [0]
        0x0A, 0x04, 0x01, 0x02, 0x00, 0x0B
    )

    private suspend fun parsesSuccessfully(bytes: ByteArray): Boolean {
        val buffer = bytes.toCommonByteBuffer()
        return WasmParser().parse(buffer) is WasmParseSuccess
    }

    @Test
    fun `flag 1 — legal elemkind 0x00 parses`() = runTest {
        assertTrue(parsesSuccessfully(moduleWithPassiveElemkind(0x00)))
    }

    @Test
    fun `flag 2 — legal elemkind 0x00 parses`() = runTest {
        assertTrue(parsesSuccessfully(moduleWithActiveExplicitElemkind(0x00)))
    }

    @Test
    fun `flag 3 — legal elemkind 0x00 parses`() = runTest {
        assertTrue(parsesSuccessfully(moduleWithDeclarativeElemkind(0x00)))
    }

    /**
     * GAP: flag 1 with illegal elemkind byte should be rejected (or at minimum
     * the byte should be captured so a validator can reject it). The current
     * parser silently accepts any byte, so this test fails today.
     */
    @Test
    fun `flag 1 — illegal elemkind 0xFF must be rejected`() = runTest {
        assertFalse(parsesSuccessfully(moduleWithPassiveElemkind(0xFF)),
            "spec 5.5.12: passive funcidx segments require elemkind == 0x00")
    }

    @Test
    fun `flag 2 — illegal elemkind 0xFF must be rejected`() = runTest {
        assertFalse(parsesSuccessfully(moduleWithActiveExplicitElemkind(0xFF)),
            "spec 5.5.12: active-explicit funcidx segments require elemkind == 0x00")
    }

    @Test
    fun `flag 3 — illegal elemkind 0xFF must be rejected`() = runTest {
        assertFalse(parsesSuccessfully(moduleWithDeclarativeElemkind(0xFF)),
            "spec 5.5.12: declarative funcidx segments require elemkind == 0x00")
    }
}
