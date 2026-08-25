package org.jetbrains.hexana.shared.scripting

/**
 * Rewrites calls to the output globals into `yield` expressions, so a script can
 * be run as a generator that pauses each time it produces output.
 *
 * The browser host needs this: it executes scripts on the UI thread, and stepping
 * a generator is what lets it hand control back to the event loop often enough to
 * paint partial output instead of freezing until the script finishes.
 *
 * This is a scanner rather than a regular expression because a regular expression
 * cannot tell code from text. `(?<!\w)(print|table|row)\s*\(` looks sufficient and
 * is not: it rewrites `tab.row(i)` into `tab.yield row(i)` — a syntax error that
 * breaks the documented table API — and it rewrites the `row(s)` inside a string
 * such as `"12 row(s)"`, corrupting the script's own output.
 *
 * Where the language is genuinely ambiguous (a `/` that could open a regular
 * expression or divide), the scanner errs toward *not* rewriting. A missed rewrite
 * only means the script yields less often; a wrong one is a syntax error.
 */
object ScriptYieldTransform {

    /** Globals whose calls are turned into `yield` expressions. */
    val OUTPUT_GLOBALS: Set<String> = setOf("print", "table", "row")

    /**
     * Keywords after which an identifier is being declared, not called.
     * `function row(...)` must not become `function yield row(...)`.
     */
    private val DECLARATION_KEYWORDS = setOf("function", "class", "var", "let", "const", "new")

    fun apply(source: String, globals: Set<String> = OUTPUT_GLOBALS): String {
        val out = StringBuilder(source.length + 64)
        // Nesting of template literals and the `${}` holes inside them, so that
        // code in a hole is scanned as code and text around it as text.
        val templateBraceDepth = ArrayDeque<Int>()
        var braceDepth = 0
        var previousSignificant: Char? = null
        var previousIdentifier: String? = null
        var index = 0

        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)

            when {
                char == '/' && next == '/' -> {
                    val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                    out.append(source, index, end)
                    index = end
                }

                char == '/' && next == '*' -> {
                    val close = source.indexOf("*/", index + 2)
                    val end = if (close < 0) source.length else close + 2
                    out.append(source, index, end)
                    index = end
                }

                char == '\'' || char == '"' -> {
                    val end = endOfQuoted(source, index, char)
                    out.append(source, index, end)
                    index = end
                    previousSignificant = char
                    previousIdentifier = null
                }

                char == '`' -> {
                    out.append(char)
                    templateBraceDepth.addLast(braceDepth)
                    index++
                    index = appendTemplateText(source, index, out)
                    previousSignificant = '`'
                    previousIdentifier = null
                }

                char == '$' && next == '{' && templateBraceDepth.isNotEmpty() -> {
                    out.append("\${")
                    braceDepth++
                    index += 2
                    previousSignificant = '{'
                    previousIdentifier = null
                }

                char == '}' && templateBraceDepth.isNotEmpty() && braceDepth == templateBraceDepth.last() + 1 -> {
                    // Closing a `${}` hole: back to template text.
                    out.append(char)
                    braceDepth--
                    index++
                    index = appendTemplateText(source, index, out)
                    previousSignificant = '`'
                    previousIdentifier = null
                }

                char == '/' && regexCanStartAfter(previousSignificant, previousIdentifier) -> {
                    val end = endOfRegex(source, index)
                    out.append(source, index, end)
                    index = end
                    previousSignificant = '/'
                    previousIdentifier = null
                }

                char.isIdentifierStart() -> {
                    var end = index
                    while (end < source.length && source[end].isIdentifierPart()) end++
                    val identifier = source.substring(index, end)

                    val isCall = identifier in globals &&
                        previousSignificant != '.' &&
                        previousIdentifier !in DECLARATION_KEYWORDS &&
                        nextSignificant(source, end) == '('
                    if (isCall) out.append("yield ")
                    out.append(identifier)

                    index = end
                    previousSignificant = identifier.last()
                    previousIdentifier = identifier
                }

                else -> {
                    if (char == '{') braceDepth++
                    if (char == '}') braceDepth--
                    out.append(char)
                    if (!char.isWhitespace()) {
                        previousSignificant = char
                        previousIdentifier = null
                    }
                    index++
                }
            }
        }

        return out.toString()
    }

    /** Appends template text up to the next `${` or the closing backtick. */
    private fun appendTemplateText(source: String, start: Int, out: StringBuilder): Int {
        var index = start
        while (index < source.length) {
            val char = source[index]
            if (char == '\\' && index + 1 < source.length) {
                out.append(char).append(source[index + 1])
                index += 2
                continue
            }
            if (char == '`') {
                out.append(char)
                return index + 1
            }
            if (char == '$' && source.getOrNull(index + 1) == '{') {
                return index
            }
            out.append(char)
            index++
        }
        return index
    }

    private fun endOfQuoted(source: String, start: Int, quote: Char): Int {
        var index = start + 1
        while (index < source.length) {
            val char = source[index]
            if (char == '\\') {
                index += 2
                continue
            }
            if (char == quote || char == '\n') return index + 1
            index++
        }
        return source.length
    }

    /**
     * End of a regular-expression literal, or of the line when it does not close —
     * an unterminated scan must not swallow the rest of the script.
     */
    private fun endOfRegex(source: String, start: Int): Int {
        var index = start + 1
        var inClass = false
        while (index < source.length) {
            val char = source[index]
            when {
                char == '\\' -> index++
                char == '\n' -> return index
                char == '[' -> inClass = true
                char == ']' -> inClass = false
                char == '/' && !inClass -> {
                    index++
                    // Trailing flags belong to the literal.
                    while (index < source.length && source[index].isIdentifierPart()) index++
                    return index
                }
            }
            index++
        }
        return source.length
    }

    /**
     * Whether a `/` here opens a regular expression rather than dividing.
     *
     * Division follows a value: an identifier, a literal, or a closing bracket.
     * Anything else — an operator, a comma, an opening bracket, the start of the
     * source — means a literal. Keywords are values' opposite, so `return /x/` is
     * a literal too.
     */
    private fun regexCanStartAfter(previousSignificant: Char?, previousIdentifier: String?): Boolean {
        if (previousSignificant == null) return true
        if (previousIdentifier != null) return previousIdentifier in REGEX_PRECEDING_KEYWORDS
        return previousSignificant !in VALUE_ENDING_CHARS
    }

    private val REGEX_PRECEDING_KEYWORDS = setOf(
        "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield",
    )

    private val VALUE_ENDING_CHARS = setOf(')', ']', '}', '`')

    private fun nextSignificant(source: String, start: Int): Char? {
        var index = start
        while (index < source.length && source[index].isWhitespace()) index++
        return source.getOrNull(index)
    }

    private fun Char.isIdentifierStart(): Boolean = isLetter() || this == '_' || this == '$'

    private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_' || this == '$'
}
