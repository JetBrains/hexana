package org.jetbrains.hexana.shared.scripting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptOutputTest {

    private val lines = mutableListOf<String>()
    private val output = ScriptOutput { lines += it }

    private fun str(value: String) = ScriptValue.Str(value)
    private fun num(value: Number) = ScriptValue.Num(value)

    /**
     * The rule the script-tab renderers use to decide whether output is a table:
     * the first two lines must split into the same number of cells, at least two.
     * Anything table()/row() emits has to satisfy it.
     */
    private fun rendersAsTable(lines: List<String>): Boolean {
        if (lines.size < 2) return false
        val header = lines[0].split(ScriptOutput.CELL_SEPARATOR)
        val first = lines[1].split(ScriptOutput.CELL_SEPARATOR)
        return header.size >= 2 && header.size == first.size
    }

    @Test
    fun printJoinsArgumentsWithASpace() {
        output.print(listOf(str("format:"), str("wasm"), num(3)))

        assertEquals(listOf("format: wasm 3"), lines)
    }

    @Test
    fun printFormatsValuesTheWayJavaScriptWould() {
        output.print(listOf(num(5)))
        output.print(listOf(num(5.0)))
        output.print(listOf(num(2.5)))
        output.print(listOf(ScriptValue.Null))
        output.print(listOf(ScriptValue.Bool(true)))
        output.print(listOf(ScriptValue.Arr(2) { num(it) }))

        // 5.0 must print as "5": a script that reads an integral value and prints
        // it should not see a Kotlin-shaped ".0" suffix.
        assertEquals(listOf("5", "5", "2.5", "null", "true", "0,1"), lines)
    }

    @Test
    fun printWithNoArgumentsEmitsABlankLine() {
        output.print(emptyList())

        assertEquals(listOf(""), lines)
    }

    @Test
    fun tableAndRowEmitOutputTheRenderersRecogniseAsATable() {
        output.table(listOf(str("Section"), str("Size")))
        output.row(listOf(str("code"), num(1024)))
        output.row(listOf(str("data"), num(64)))

        assertEquals(
            listOf("Section\tSize", "code\t1024", "data\t64"),
            lines,
        )
        assertTrue(rendersAsTable(lines))
    }

    @Test
    fun shortRowsArePaddedSoTheTableKeepsRendering() {
        output.table(listOf(str("A"), str("B"), str("C")))
        output.row(listOf(str("only-one")))

        assertEquals(listOf("A\tB\tC", "only-one\t\t"), lines)
        assertTrue(rendersAsTable(lines), "a short row must not break table detection")
    }

    @Test
    fun longRowsKeepEveryCell() {
        output.table(listOf(str("A"), str("B")))
        output.row(listOf(str("1"), str("2"), str("3")))

        assertEquals(listOf("A\tB", "1\t2\t3"), lines)
    }

    @Test
    fun cellsContainingSeparatorsAreEscapedInsteadOfShiftingColumns() {
        output.table(listOf(str("Name"), str("Note")))
        output.row(listOf(str("a\tb"), str("first\nsecond\r\nthird")))

        assertEquals(listOf("Name\tNote", "a b\tfirst second  third"), lines)
        assertTrue(rendersAsTable(lines))
        assertEquals(2, lines[1].split(ScriptOutput.CELL_SEPARATOR).size)
    }

    @Test
    fun rowWithoutATableExplainsWhatIsMissing() {
        val error = assertFailsWith<IllegalStateException> { output.row(listOf(str("x"))) }

        assertTrue(error.message!!.contains("table("), error.message!!)
    }

    @Test
    fun aSecondTableCallRedeclaresTheColumnCount() {
        output.table(listOf(str("A"), str("B")))
        output.row(listOf(str("1"), str("2")))
        output.table(listOf(str("X"), str("Y"), str("Z")))
        output.row(listOf(str("9")))

        assertEquals(listOf("A\tB", "1\t2", "X\tY\tZ", "9\t\t"), lines)
    }

    @Test
    fun bindingsExposeExactlyTheDocumentedGlobals() {
        assertEquals(setOf("print", "table", "row"), output.bindings().keys)
    }

    @Test
    fun boundFunctionsValidateTheirArity() {
        val bindings = output.bindings()

        // print() and row() accept anything; table() needs at least one column.
        bindings.getValue("print").invoke(emptyList())
        val error = assertFailsWith<IllegalArgumentException> {
            bindings.getValue("table").invoke(emptyList())
        }
        assertTrue(error.message!!.contains("table"), error.message!!)
    }

    @Test
    fun boundFunctionsWriteThroughToTheSameOutput() {
        val bindings = output.bindings()
        bindings.getValue("table").invoke(listOf(str("A"), str("B")))
        bindings.getValue("row").invoke(listOf(num(1), num(2)))

        assertEquals(listOf("A\tB", "1\t2"), lines)
    }
}
