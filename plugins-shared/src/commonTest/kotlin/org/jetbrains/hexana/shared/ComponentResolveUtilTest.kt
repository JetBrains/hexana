package org.jetbrains.hexana.shared

import org.jetbrains.hexana.wasm.components.ComponentPrimitiveCharType
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveString
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveValueType
import org.jetbrains.hexana.wasm.components.ComponentValueIdxType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentResolveUtilTest {

    private val charType = ComponentPrimitiveValueType(ComponentPrimitiveCharType)
    private val stringType = ComponentPrimitiveValueType(ComponentPrimitiveString)
    private val u32Type = ComponentPrimitiveValueType(ComponentPrimitiveU32Type)

    // -----------------------------------------------------------------------
    // isSingleUnicodeCharacter
    // -----------------------------------------------------------------------

    @Test
    fun singleUnicodeCharacterAcceptsBmpCharAndSpace() {
        assertTrue(isSingleUnicodeCharacter("x"))
        assertTrue(isSingleUnicodeCharacter(" "))
    }

    @Test
    fun singleUnicodeCharacterAcceptsSurrogatePair() {
        assertTrue(isSingleUnicodeCharacter("🚀")) // rocket emoji
    }

    @Test
    fun singleUnicodeCharacterRejectsEmptyAndMultiChar() {
        assertFalse(isSingleUnicodeCharacter(""))
        assertFalse(isSingleUnicodeCharacter("ab"))
        assertFalse(isSingleUnicodeCharacter("🚀x"))
        // Lone or reversed surrogates are not a valid code point
        assertFalse(isSingleUnicodeCharacter("\uDE80\uD83D"))
    }

    // -----------------------------------------------------------------------
    // formatComponentArgWave
    // -----------------------------------------------------------------------

    @Test
    fun formatWaveQuotesCharValues() {
        assertEquals("'x'", formatComponentArgWave("x", charType))
        assertEquals("' '", formatComponentArgWave(" ", charType))
        assertEquals("""'\''""", formatComponentArgWave("'", charType))
        assertEquals("""'\\'""", formatComponentArgWave("\\", charType))
    }

    @Test
    fun formatWaveQuotesStringValues() {
        assertEquals("\"hello world\"", formatComponentArgWave("hello world", stringType))
        assertEquals("\"\"", formatComponentArgWave("", stringType))
        assertEquals(""""he\"ll\\o"""", formatComponentArgWave("he\"ll\\o", stringType))
    }

    @Test
    fun formatWavePassesOtherTypesBare() {
        assertEquals("42", formatComponentArgWave("42", u32Type))
        assertEquals("red", formatComponentArgWave("red", ComponentValueIdxType(0u)))
    }
}
