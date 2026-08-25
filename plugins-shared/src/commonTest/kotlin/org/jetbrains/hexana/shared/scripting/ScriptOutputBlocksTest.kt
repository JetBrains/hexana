package org.jetbrains.hexana.shared.scripting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptOutputBlocksTest {

    private fun blocks(vararg lines: String) = parseScriptOutputBlocks(lines.toList())

    private fun text(block: ScriptOutputBlock): List<String> =
        (block as ScriptOutputBlock.Text).lines

    private fun table(block: ScriptOutputBlock): ScriptOutputBlock.Table =
        block as ScriptOutputBlock.Table

    /** Renders the block kinds compactly so assertions read as a layout. */
    private fun shape(blocks: List<ScriptOutputBlock>): List<String> = blocks.map { block ->
        when (block) {
            is ScriptOutputBlock.Text -> "text(${block.lines.size})"
            is ScriptOutputBlock.Table -> "table(${block.columns.size}x${block.rows.size})"
        }
    }

    @Test
    fun emptyOutputProducesNoBlocks() {
        assertEquals(emptyList(), parseScriptOutputBlocks(emptyList()))
    }

    @Test
    fun plainTextStaysOneTextBlock() {
        val result = blocks("hello", "world")

        assertEquals(listOf("text(2)"), shape(result))
        assertEquals(listOf("hello", "world"), text(result.single()))
    }

    @Test
    fun aSingleLineIsNeverATable() {
        // One tab-separated line on its own has no rows, so it is not a table.
        assertEquals(listOf("text(1)"), shape(blocks("Name\tSize")))
    }

    @Test
    fun tabSeparatedLinesBecomeATableWithAHeader() {
        val result = blocks("Name\tSize", "code\t1024", "data\t64")

        assertEquals(listOf("table(2x2)"), shape(result))
        val parsed = table(result.single())
        assertEquals(listOf("Name", "Size"), parsed.columns)
        assertEquals(listOf(listOf("code", "1024"), listOf("data", "64")), parsed.rows)
    }

    @Test
    fun textBeforeATableStaysText() {
        // The case from the starter template: a heading, a blank line, then a table.
        val result = blocks(
            "sample.wasm — Core Wasm, 55 bytes",
            "",
            "Region\tSize",
            "Types\t10",
        )

        assertEquals(listOf("text(2)", "table(2x1)"), shape(result))
        assertEquals(listOf("sample.wasm — Core Wasm, 55 bytes", ""), text(result[0]))
        assertEquals(listOf("Region", "Size"), table(result[1]).columns)
    }

    @Test
    fun textAndTablesInterleaveInScriptOrder() {
        val result = blocks(
            "Sections",
            "Name\tSize",
            "a\t1",
            "",
            "Symbols",
            "Symbol\tAddress\tKind",
            "main\t0x1000\tFUNC",
            "done",
        )

        assertEquals(
            listOf("text(1)", "table(2x1)", "text(2)", "table(3x1)", "text(1)"),
            shape(result),
        )
        assertEquals(listOf("", "Symbols"), text(result[2]))
        assertEquals(listOf("Symbol", "Address", "Kind"), table(result[3]).columns)
        assertEquals(listOf("done"), text(result[4]))
    }

    @Test
    fun twoTablesInARowWithDifferentColumnCountsSplit() {
        val result = blocks("A\tB", "1\t2", "X\tY\tZ", "7\t8\t9")

        assertEquals(listOf("table(2x1)", "table(3x1)"), shape(result))
    }

    @Test
    fun aBlankLineSeparatesTwoTablesWithTheSameShape() {
        val result = blocks("A\tB", "1\t2", "", "C\tD", "3\t4")

        assertEquals(listOf("table(2x1)", "text(1)", "table(2x1)"), shape(result))
    }

    @Test
    fun headerCellsAreTrimmedButDataCellsAreNot() {
        // Headers are labels; data may be deliberately padded or aligned.
        val result = table(blocks(" Name \t Size ", "code\t 1024 ").single())

        assertEquals(listOf("Name", "Size"), result.columns)
        assertEquals(listOf(listOf("code", " 1024 ")), result.rows)
    }

    @Test
    fun aTabbedLineWithOneCellIsNotATable() {
        // A trailing tab alone does not make a column, but it does split into two
        // cells, so require the run to agree on the count.
        assertEquals(listOf("text(2)"), shape(blocks("note:\tvalue", "unrelated prose")))
    }

    @Test
    fun csvOutputIsStillRenderedAsOneTable() {
        // Pre-existing behavior for scripts that print comma-separated output.
        val result = blocks("Name,Size", "code,1024", "data,64")

        assertEquals(listOf("table(2x2)"), shape(result))
        assertEquals(listOf("Name", "Size"), table(result.single()).columns)
    }

    @Test
    fun proseIsNotTurnedIntoACsvTable() {
        val result = blocks(
            "Tabs: Exports, Imports, Modules",
            "First bytes: 00 61 73 6d",
        )

        // Both lines have a different comma count, so this stays text.
        assertEquals(listOf("text(2)"), shape(result))
    }

    @Test
    fun commasAreNotSegmentedIntoRunsOnceTabsArePresent() {
        // With tabs in the output, commas are never a delimiter — otherwise a
        // narrated line would break the surrounding table apart.
        val result = blocks("A\tB", "1\t2", "prose, with, commas")

        assertEquals(listOf("table(2x1)", "text(1)"), shape(result))
    }

    @Test
    fun rowsWithMissingTrailingCellsKeepTheirPlace() {
        // ScriptOutput pads short rows, so a padded row has empty trailing cells.
        val result = table(blocks("A\tB\tC", "1\t\t").single())

        assertEquals(listOf(listOf("1", "", "")), result.rows)
    }

    @Test
    fun outputProducedByTheOutputGlobalsRoundTripsBackIntoBlocks() {
        // End-to-end with the real writer: what print/table/row emit must parse
        // back into the blocks the script described.
        val lines = mutableListOf<String>()
        val output = ScriptOutput { lines += it }
        output.print(listOf(ScriptValue.Str("Regions")))
        output.print(emptyList())
        output.table(listOf(ScriptValue.Str("Name"), ScriptValue.Str("Size")))
        output.row(listOf(ScriptValue.Str("code"), ScriptValue.Num(1024)))
        output.row(listOf(ScriptValue.Str("data"), ScriptValue.Num(64)))
        output.print(listOf(ScriptValue.Str("done")))

        val result = parseScriptOutputBlocks(lines)

        assertEquals(listOf("text(2)", "table(2x2)", "text(1)"), shape(result))
        assertEquals(listOf(listOf("code", "1024"), listOf("data", "64")), table(result[1]).rows)
    }

    // -- filtering -----------------------------------------------------------

    @Test
    fun aBlankQueryKeepsEverything() {
        val original = blocks("text", "A\tB", "1\t2")

        assertEquals(shape(original), shape(filterScriptOutputBlocks(original, "  ")))
    }

    @Test
    fun filteringKeepsMatchingTextLinesAndTableRows() {
        val original = blocks(
            "header line",
            "Name\tSize",
            "code\t1024",
            "data\t64",
            "footer",
        )

        val filtered = filterScriptOutputBlocks(original, "code")

        assertEquals(listOf("table(2x1)"), shape(filtered))
        assertEquals(listOf(listOf("code", "1024")), table(filtered.single()).rows)
        assertEquals(listOf("Name", "Size"), table(filtered.single()).columns)
    }

    @Test
    fun filteringIsCaseInsensitiveAndSearchesEveryCell() {
        val original = blocks("Name\tSize", "code\t1024")

        assertEquals(1, table(filterScriptOutputBlocks(original, "CODE").single()).rows.size)
        assertEquals(1, table(filterScriptOutputBlocks(original, "1024").single()).rows.size)
        assertTrue(filterScriptOutputBlocks(original, "nope").isEmpty())
    }

    @Test
    fun filteringDoesNotMatchAgainstColumnHeaders() {
        // Matching the header would keep every row of a table whose column name
        // happens to contain the query, which reads as "the filter did nothing".
        val original = blocks("Name\tSize", "code\t1024")

        assertTrue(filterScriptOutputBlocks(original, "Name").isEmpty())
    }

    @Test
    fun aFilteredTableKeepsItsHeaderEvenThoughHeadersAreNotMatched() {
        // Headers are not content, so they are neither matched nor filtered away:
        // a surviving table still has to read as a table.
        val original = blocks("Symbol\tAddress", "main\t0x1000", "start\t0x2000")

        val filtered = filterScriptOutputBlocks(original, "0x1000")

        assertEquals(listOf("Symbol", "Address"), table(filtered.single()).columns)
        assertEquals(listOf(listOf("main", "0x1000")), table(filtered.single()).rows)
    }

    @Test
    fun filteringSpansTextAndTableContentInOnePass() {
        // The pane filter is the single filtering UI for a mixed document, so one
        // query has to reach both the prose and the rows.
        val original = blocks(
            "region .text is hot",
            "Name\tSize",
            ".text\t212",
            ".data\t16",
            "unrelated note",
        )

        val filtered = filterScriptOutputBlocks(original, ".text")

        assertEquals(listOf("text(1)", "table(2x1)"), shape(filtered))
        assertEquals(listOf("region .text is hot"), text(filtered[0]))
        assertEquals(listOf(listOf(".text", "212")), table(filtered[1]).rows)
    }
}
