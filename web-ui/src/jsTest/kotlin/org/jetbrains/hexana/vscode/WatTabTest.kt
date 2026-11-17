package org.jetbrains.hexana.vscode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WatTabTest {
    @Test
    fun escapeHtmlEscapesAllFiveReservedCharacters() {
        assertEquals("&amp;", escapeHtml("&"))
        assertEquals("&lt;", escapeHtml("<"))
        assertEquals("&gt;", escapeHtml(">"))
        assertEquals("&quot;", escapeHtml("\""))
        assertEquals("&#39;", escapeHtml("'"))
    }

    @Test
    fun escapeHtmlDoesNotDoubleEncodeAmpersandInExistingEscapes() {
        // "&" must be replaced first so an already-escaped "&quot;" does not
        // become "&amp;quot;".
        assertEquals("&amp;quot;", escapeHtml("&quot;"))
    }

    @Test
    fun escapeHtmlNeutralizesAttributeInjectionViaDoubleQuote() {
        // Attacker-controlled custom-section name breaking out of a
        // double-quoted data-id="..." attribute.
        val attack = "a\" style=\"x"
        val escaped = escapeHtml(attack)
        assertFalse(escaped.contains("\""), "escaped output must not contain a raw double-quote: $escaped")
        assertEquals("a&quot; style=&quot;x", escaped)
    }

    @Test
    fun escapeHtmlNeutralizesAttributeInjectionViaSingleQuote() {
        val attack = "a' onx='y"
        val escaped = escapeHtml(attack)
        assertFalse(escaped.contains("'"), "escaped output must not contain a raw single-quote: $escaped")
        assertEquals("a&#39; onx=&#39;y", escaped)
    }
}
