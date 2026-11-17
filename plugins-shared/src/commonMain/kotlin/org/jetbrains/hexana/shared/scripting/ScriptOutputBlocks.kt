package org.jetbrains.hexana.shared.scripting

/**
 * One rendered region of a script tab's output.
 *
 * Script output is a sequence of these, in the order the script produced them, so
 * a script can narrate: a few lines of text, a table, more text, another table.
 * Previously each host looked at the first two output lines and rendered the
 * whole tab as *either* one table *or* one single-column table of text — which
 * meant a script that printed a heading before its table got the heading and the
 * tab-separated rows jumbled into one column.
 */
sealed interface ScriptOutputBlock {

    /**
     * Position of this block in the unfiltered output.
     *
     * Filtering rebuilds blocks, so a renderer cannot use object identity or list
     * position to tell one from another. This survives filtering, which is what
     * lets a virtualized list keep a table's sort and selection attached to the
     * right table when the document around it shrinks.
     */
    val ordinal: Int

    /** Free-form lines from `print(...)`. Blank lines are kept as spacing. */
    class Text(val lines: List<String>, override val ordinal: Int = 0) : ScriptOutputBlock

    /** A table: the header row from `table(...)` and the rows from `row(...)`. */
    class Table(
        val columns: List<String>,
        val rows: List<List<String>>,
        override val ordinal: Int = 0,
    ) : ScriptOutputBlock
}

/**
 * Splits a script's output lines into text and table blocks.
 *
 * Segmentation is derived from the line stream rather than carried alongside it,
 * because that stream is the only thing some hosts receive: the webapp parser
 * worker posts lines over a message channel, and the CLI and MCP tool return
 * lines as their documented result shape. Deriving the structure keeps all four
 * hosts rendering identically from one implementation.
 *
 * Rules, in order:
 *
 * 1. If any line contains a tab, every maximal run of two or more consecutive
 *    tab-separated lines with the same cell count becomes a [ScriptOutputBlock.Table]
 *    (first line = header); everything else is text. This is what `table(...)` and
 *    `row(...)` emit, and it is also the long-standing hand-rolled
 *    `print(a + "\t" + b)` idiom.
 * 2. Otherwise, if the first two lines split by `,` into the same number of at
 *    least two fields, the whole output is one CSV table. This is the pre-existing
 *    whole-output CSV behavior, kept as-is: applying the run-based rule to commas
 *    would turn any two consecutive prose lines that happen to share a comma count
 *    into a table.
 * 3. Otherwise the whole output is one text block.
 */
fun parseScriptOutputBlocks(lines: List<String>): List<ScriptOutputBlock> {
    if (lines.isEmpty()) return emptyList()

    if (lines.any { it.contains(TAB) }) {
        return segmentTabSeparated(lines)
    }

    csvTable(lines)?.let { return listOf(it) }

    return listOf(ScriptOutputBlock.Text(lines))
}

private const val TAB = '\t'
private const val COMMA = ','
private const val MIN_TABLE_COLUMNS = 2
private const val MIN_TABLE_LINES = 2

private fun segmentTabSeparated(lines: List<String>): List<ScriptOutputBlock> {
    val blocks = mutableListOf<ScriptOutputBlock>()
    val pendingText = mutableListOf<String>()

    fun flushText() {
        if (pendingText.isNotEmpty()) {
            blocks += ScriptOutputBlock.Text(pendingText.toList(), ordinal = blocks.size)
            pendingText.clear()
        }
    }

    var index = 0
    while (index < lines.size) {
        val runLength = tableRunLength(lines, index)
        if (runLength == 0) {
            pendingText += lines[index]
            index++
            continue
        }

        flushText()
        val cells = (index until index + runLength).map { lines[it].split(TAB) }
        blocks += ScriptOutputBlock.Table(
            columns = cells.first().map { it.trim() },
            rows = cells.drop(1),
            ordinal = blocks.size,
        )
        index += runLength
    }

    flushText()
    return blocks
}

/**
 * Length of the table run starting at [start], or 0 when no table starts there.
 *
 * A run is at least [MIN_TABLE_LINES] consecutive lines that all split on tabs
 * into the same number of cells, at least [MIN_TABLE_COLUMNS] of them.
 */
private fun tableRunLength(lines: List<String>, start: Int): Int {
    val columnCount = tabCellCount(lines[start]) ?: return 0
    var end = start + 1
    while (end < lines.size && tabCellCount(lines[end]) == columnCount) {
        end++
    }
    val length = end - start
    return if (length >= MIN_TABLE_LINES) length else 0
}

private fun tabCellCount(line: String): Int? {
    if (!line.contains(TAB)) return null
    val count = line.count { it == TAB } + 1
    return if (count >= MIN_TABLE_COLUMNS) count else null
}

private fun csvTable(lines: List<String>): ScriptOutputBlock.Table? {
    if (lines.size < MIN_TABLE_LINES) return null
    val header = lines[0].split(COMMA)
    val first = lines[1].split(COMMA)
    if (header.size < MIN_TABLE_COLUMNS || header.size != first.size) return null

    return ScriptOutputBlock.Table(
        columns = header.map { it.trim() },
        rows = lines.drop(1).map { row -> row.split(COMMA) },
    )
}

/**
 * Filters blocks by a case-insensitive substring, dropping text lines and table
 * rows that do not match.
 *
 * The query is matched against content only — text lines and table cells — never
 * against column headers. Matching a header would keep every row of a table whose
 * column name happens to contain the query, which reads as "the filter did
 * nothing"; and a table whose rows all match still needs its header to be
 * readable, so the header is always kept rather than filtered alongside the rows.
 * Blocks left with no content are dropped.
 */
fun filterScriptOutputBlocks(
    blocks: List<ScriptOutputBlock>,
    query: String,
): List<ScriptOutputBlock> {
    if (query.isBlank()) return blocks

    return blocks.mapNotNull { block ->
        when (block) {
            is ScriptOutputBlock.Text -> {
                val matching = block.lines.filter { it.contains(query, ignoreCase = true) }
                if (matching.isEmpty()) null else ScriptOutputBlock.Text(matching, block.ordinal)
            }
            is ScriptOutputBlock.Table -> {
                val matching = block.rows.filter { row ->
                    row.any { cell -> cell.contains(query, ignoreCase = true) }
                }
                if (matching.isEmpty()) null else {
                    ScriptOutputBlock.Table(block.columns, matching, block.ordinal)
                }
            }
        }
    }
}
