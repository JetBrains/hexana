package org.jetbrains.hexana.ui.panel

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.wasm.opcodes.OpCodes

class WasmGotoNavigationTest : UsefulTestCase() {

    private val sampleNames: Map<String, Int> = mapOf(
        "kotlin_alloc" to 42,
        "kotlin_free" to 43,
        "skia_paint_new" to 100,
        "main" to 7,
    )

    // -- parseFuncQuery --------------------------------------------------

    fun testParseFuncQueryAcceptsDecimal() {
        assertEquals(1234, parseFuncQuery("1234", sampleNames))
    }

    fun testParseFuncQueryAcceptsHex() {
        assertEquals(1234, parseFuncQuery("0x4d2", sampleNames))
        assertEquals(255, parseFuncQuery("0xFF", sampleNames))
    }

    fun testParseFuncQueryAcceptsRendererPlaceholder() {
        // `func$1234` is the fallback the renderer emits when no real name
        // is resolved — must round-trip back to the underlying funcidx.
        assertEquals(1234, parseFuncQuery("func\$1234", sampleNames))
        assertEquals(1234, parseFuncQuery("\$func\$1234", sampleNames))
        assertEquals(1234, parseFuncQuery("\$1234", sampleNames))
    }

    fun testParseFuncQueryExactNameMatch() {
        assertEquals(42, parseFuncQuery("kotlin_alloc", sampleNames))
        // Case-insensitive — the index stores lowercased keys.
        assertEquals(42, parseFuncQuery("Kotlin_Alloc", sampleNames))
    }

    fun testParseFuncQueryContainsMatch() {
        // Substring match — "paint" hits skia_paint_new.
        assertEquals(100, parseFuncQuery("paint", sampleNames))
    }

    fun testParseFuncQueryEmptyReturnsNull() {
        assertNull(parseFuncQuery("", sampleNames))
        assertNull(parseFuncQuery("   ", sampleNames))
    }

    fun testParseFuncQueryUnknownNameReturnsNull() {
        assertNull(parseFuncQuery("definitely_not_here", sampleNames))
    }

    fun testParseFuncQueryNegativeRejected() {
        // u32 only — negatives don't make sense for funcidx and shouldn't
        // accidentally resolve via the numeric branch.
        assertNull(parseFuncQuery("-1", sampleNames))
    }

    // -- parseCallTarget -------------------------------------------------

    fun testParseCallTargetDecimal() {
        val op = OpCodes.Flow.Call(value = 99u)
        assertEquals(42, parseCallTarget("call 42", op))
    }

    fun testParseCallTargetHex() {
        val op = OpCodes.Flow.Call(value = 99u)
        assertEquals(255, parseCallTarget("call 0xFF", op))
    }

    fun testParseCallTargetFallsBackToOriginal() {
        // Mid-edit: text doesn't parse → use the original opcode's value
        // so F4 still lands on a sensible target.
        val op = OpCodes.Flow.Call(value = 99u)
        assertEquals(99, parseCallTarget("cal", op))
        assertEquals(99, parseCallTarget("", op))
    }

    fun testParseCallTargetIgnoresTrailingAnnotation() {
        // The renderer can append `(; comment ;)` after the immediate;
        // we only need the first token after `call`.
        val op = OpCodes.Flow.Call(value = 99u)
        assertEquals(42, parseCallTarget("call 42 (; kotlin_alloc ;)", op))
    }
}
