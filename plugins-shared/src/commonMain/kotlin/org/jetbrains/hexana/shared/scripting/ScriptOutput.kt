package org.jetbrains.hexana.shared.scripting

/**
 * The `print`, `table`, and `row` script globals.
 *
 * Every host binds this one implementation, so a script produces byte-identical
 * output in the IDE, in VS Code, and in the CLI.
 *
 * `table`/`row` emit tab-separated lines, which is the shape the script-tab
 * renderers already recognise as a table. Their value over hand-rolled
 * `print(a + "\t" + b)` is that the convention is documented and discoverable,
 * cells containing tabs or newlines are escaped instead of silently shifting
 * columns, and short rows are padded so the table keeps rendering.
 *
 * @param emit receives each finished output line
 */
class ScriptOutput(private val emit: (String) -> Unit) {

    private var columnCount: Int = 0

    /** Appends a line with the arguments joined by a single space. */
    fun print(values: List<ScriptValue>) {
        // Text ends the current table: the renderers segment output into blocks,
        // and a row emitted after this would land outside the table as a stray
        // tab-separated line rather than being appended to it.
        columnCount = 0
        emit(values.joinToString(" ") { formatValue(it) })
    }

    /** Declares the table header row and fixes the column count for [row]. */
    fun table(columns: List<ScriptValue>) {
        columnCount = columns.size
        emit(columns.joinToString(CELL_SEPARATOR) { escapeCell(formatValue(it)) })
    }

    /**
     * Appends one data row. Rows shorter than the declared header are padded
     * with empty cells; longer rows keep every cell.
     */
    fun row(cells: List<ScriptValue>) {
        if (columnCount == 0) {
            throw IllegalStateException(
                "row(...) requires a preceding table(...) call that declares the column headers. " +
                    "Printing text ends the current table, so call table(...) again to start a new one."
            )
        }
        val text = buildString {
            for (index in 0 until maxOf(cells.size, columnCount)) {
                if (index > 0) append(CELL_SEPARATOR)
                val cell = cells.getOrNull(index)?.let { escapeCell(formatValue(it)) } ?: ""
                append(cell)
            }
        }
        emit(text)
    }

    /** The script globals this output surface contributes, by global name. */
    fun bindings(): Map<String, ScriptValue.Fn> = mapOf(
        "print" to scriptFunction("print(...values)", minArgs = 0, maxArgs = Int.MAX_VALUE) { args ->
            print(args.all())
            ScriptValue.Null
        },
        "table" to scriptFunction("table(...columns)", minArgs = 1, maxArgs = Int.MAX_VALUE) { args ->
            table(args.all())
            ScriptValue.Null
        },
        "row" to scriptFunction("row(...cells)", minArgs = 0, maxArgs = Int.MAX_VALUE) { args ->
            row(args.all())
            ScriptValue.Null
        },
    )

    companion object {
        /**
         * Cell separator for [table] and [row].
         *
         * Tab rather than comma: it needs no quoting rules, and it is the
         * delimiter the script-tab renderers probe for first.
         */
        const val CELL_SEPARATOR: String = "\t"

        /**
         * Formats a value the way JavaScript's own string conversion would, so
         * that an integral number prints as `5` and not `5.0`.
         */
        fun formatValue(value: ScriptValue): String = when (value) {
            is ScriptValue.Null -> "null"
            is ScriptValue.Str -> value.value
            is ScriptValue.Bool -> value.value.toString()
            is ScriptValue.Num -> formatNumber(value.value)
            is ScriptValue.Arr -> (0 until value.size).joinToString(",") { formatValue(value.at(it)) }
            is ScriptValue.Fn -> "function ${value.name}"
            is ScriptValue.Obj -> "[object]"
            is ScriptValue.Host -> value.value.toString()
        }

        fun formatNumber(value: Number): String = when (value) {
            is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
            is Float -> formatNumber(value.toDouble())
            else -> value.toString()
        }

        /** Replaces the characters that would otherwise shift or split a cell. */
        fun escapeCell(text: String): String {
            if (text.none { it == '\t' || it == '\n' || it == '\r' }) return text
            return text.map { if (it == '\t' || it == '\n' || it == '\r') ' ' else it }.joinToString("")
        }
    }
}
