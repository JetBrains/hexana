package org.jetbrains.hexana.cli.core

import org.jetbrains.hexana.cli.runtime.CliTable
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.cli.runtime.ToolRenderOptions
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CliTableRendererTest {
    @Test
    fun unboundedTwoColumnGoldenRemainsExact() {
        assertEquals("Name | Count\n-----+------\nwasm | 2", render(CliTable(listOf("Name", "Count"), listOf(listOf("wasm", "2")))))
    }

    @Test
    fun narrowWrappingPreservesWordsHardWrappedTokensAndFitsEffectiveWidth() {
        val output = render(CliTable(listOf("Header name", "Value"), listOf(listOf("alpha beta", "abcdefgh"))), 13)

        assertEquals(listOf("Heade | Value", "r".padEnd(5) + " | ", "name".padEnd(5) + " | ", "------+------", "alpha | abcde", "beta".padEnd(5) + " | fgh").joinToString("\n"), output)
        assertTrue(output.lineSequence().all { CliTableRenderer.displayWidth(it) <= 13 }, output)
    }

    @Test
    fun supportsEmptyRowsOneAndThreeColumnsAndWrappedHeaders() {
        assertEquals("On\nly\n--", render(CliTable(listOf("Only"), emptyList()), 2))
        val output = render(CliTable(listOf("First header", "B", "C"), listOf(listOf("one", "two", "three"))), 15)
        assertTrue(output.lineSequence().all { CliTableRenderer.displayWidth(it) <= 15 }, output)
        assertTrue(output.contains("Fir"))
    }

    @Test
    fun graphemeClustersAndControlCharactersAreHandledSafely() {
        val output = render(CliTable(listOf("表", "e\u0301"), listOf(listOf("👩‍💻", "ok"))), 7)
        assertTrue(output.contains("👩‍💻"), output)
        assertTrue(output.contains("e\u0301"), output)
        assertTrue(output.lineSequence().all { CliTableRenderer.displayWidth(it) <= 7 }, output)
        assertEquals(2, CliTableRenderer.displayWidth("🇦🇲"))
        assertEquals(2, CliTableRenderer.displayWidth("\uD840\uDC00"))
        listOf("\t", "\u001B[31m", "\r", "\n", "\u200B", "\u202E", "\u2066").forEach { control ->
            assertFailsWith<IllegalArgumentException> { CliTable(listOf("Header$control"), emptyList()) }
        }
        listOf("\t", "\u001B[31m", "\r", "\n").forEach { control ->
            assertFailsWith<IllegalArgumentException> { CliTable(listOf("Header"), listOf(listOf("cell$control"))) }
        }
        assertFailsWith<IllegalArgumentException> { CliTable(listOf("\u0301"), emptyList()) }
        listOf("A \u200DB", "A\u200D\u200DB", "A \uFE0F", "\u202D", "\u2067", "\u2068", "\u2069", "\u2060", "\uDB40\uDC00").forEach { unsafe ->
            assertFailsWith<IllegalArgumentException> { CliTable(listOf(unsafe), emptyList()) }
            assertFailsWith<IllegalArgumentException> { CliTable(listOf("Header"), listOf(listOf(unsafe))) }
        }
        CliTable(listOf("e\u0301", "✈️", "👩‍💻"), listOf(listOf("", "ok", "ok")))
    }

    @Test
    fun tableInvariantsRejectInvalidShapes() {
        assertFailsWith<IllegalArgumentException> { CliTable(emptyList(), emptyList()) }
        assertFailsWith<IllegalArgumentException> { CliTable(listOf(" "), emptyList()) }
        assertFailsWith<IllegalArgumentException> { CliTable(listOf("A", "B"), listOf(listOf("one"))) }
    }

    @Test
    fun impossibleWidthDoesNotSplitWideGraphemes() {
        val error = assertFailsWith<org.jetbrains.hexana.cli.runtime.CliTerminalWidthException> {
            render(CliTable(listOf("A", "B"), listOf(listOf("👩‍💻", "🇦🇲"))), 1)
        }
        assertEquals(1, error.requestedWidth)
        assertEquals(7, error.minimumWidth)
    }

    @Test
    fun emojiPresentationClustersUseTwoColumnsAndStayWithinConstrainedWidth() {
        assertEquals(2, CliTableRenderer.displayWidth("✈️"))
        assertEquals(2, CliTableRenderer.displayWidth("1️⃣"))
        assertEquals(2, CliTableRenderer.displayWidth("1⃣"))
        assertEquals(1, CliTableRenderer.displayWidth("✈︎"))

        val output = render(CliTable(listOf("Icon"), listOf(listOf("✈️x"), listOf("1️⃣y"))), 2)

        assertEquals("Ic\non\n--\n✈️\nx\n1️⃣\ny", output)
        assertTrue(output.lineSequence().all { CliTableRenderer.displayWidth(it) <= 2 }, output)
    }

    @Test
    fun wrappingConsumesOneOrMoreBoundarySpacesWithoutAblankLine() {
        val output = render(CliTable(listOf("Value"), listOf(listOf("alpha   beta"), listOf("alpha beta"), listOf("abcdefgh"))), 5)
        assertEquals("Value\n-----\nalpha\nbeta\nalpha\nbeta\nabcde\nfgh", output)
        assertTrue(output.lineSequence().none { it.startsWith(' ') || it.endsWith(' ') })
    }

    private fun render(table: CliTable, width: Int? = null): String =
        CliTableRenderer.render(table, ToolRenderOptions(ToolOutputFormat.TABLE, terminalWidth = width))
}
