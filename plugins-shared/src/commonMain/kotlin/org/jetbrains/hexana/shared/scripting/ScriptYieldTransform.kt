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
     * Globals whose call must *evaluate* to what the host sends back, so the
     * script suspends at the call and resumes with its result.
     *
     * Only `open(path)` works this way, and only in a host that answers it out of
     * band. Unlike an output call, the value matters, so the rewrite has to be
     * `(yield open(...))` rather than a bare `yield` prefix: `yield` binds looser
     * than member access, so `yield open(p).size` would read `.size` off the
     * request and hand the host nothing.
     */
    val SUSPENDING_GLOBALS: Set<String> = setOf(SCRIPT_OPEN_GLOBAL)

    /**
     * Keywords after which an identifier is being declared, not called.
     * `function row(...)` must not become `function yield row(...)`.
     */
    private val DECLARATION_KEYWORDS = setOf("function", "class", "var", "let", "const", "new")

    /**
     * Keywords whose `(...)` is a control-flow head, not a parameter list, so the
     * `{` that follows opens a block rather than a function body.
     */
    private val CONTROL_FLOW_KEYWORDS = setOf("if", "for", "while", "switch", "catch", "with")

    /**
     * @param globals calls rewritten to `yield <call>`, for their preemption
     *   value only
     * @param suspending calls rewritten to `(yield <call>)`, whose result the
     *   host substitutes; see [SUSPENDING_GLOBALS]
     */
    fun apply(
        source: String,
        globals: Set<String> = OUTPUT_GLOBALS,
        suspending: Set<String> = emptySet(),
    ): String {
        val out = StringBuilder(source.length + 64)
        // Nesting of template literals and the `${}` holes inside them, so that
        // code in a hole is scanned as code and text around it as text.
        val templateBraceDepth = ArrayDeque<Int>()
        var braceDepth = 0
        var previousSignificant: Char? = null
        var previousIdentifier: String? = null
        var index = 0
        // Depths at which a nested function body was opened. A `yield` inside one
        // is a syntax error — that body is not the generator — so a call there is
        // left alone. See [functionBodyOpensHere].
        val functionBodyDepths = ArrayDeque<Int>()
        var parenDepth = 0
        val controlFlowParenDepths = ArrayDeque<Int>()
        var lastParenWasControlFlow = false
        var pendingArrowBody = false
        var bracketDepth = 0
        // Braceless arrow bodies, which are function bodies with no `{` to track.
        // See [conciseArrowBodyEndsHere].
        val conciseArrowBodies = ArrayDeque<ConciseArrowBody>()
        // Paren depths of suspending calls awaiting their closing `)`, which is
        // where the wrapping parenthesis has to be emitted.
        val suspendingCloseDepths = ArrayDeque<Int>()

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

                    val isCall = (identifier in globals || identifier in suspending) &&
                        previousSignificant != '.' &&
                        previousIdentifier !in DECLARATION_KEYWORDS &&
                        nextSignificant(source, end) == '(' &&
                        functionBodyDepths.isEmpty() &&
                        conciseArrowBodies.isEmpty()
                    if (isCall) {
                        if (identifier in suspending) {
                            out.append("(yield ")
                            suspendingCloseDepths.addLast(parenDepth + 1)
                        } else {
                            out.append("yield ")
                        }
                    }
                    out.append(identifier)

                    index = end
                    previousSignificant = identifier.last()
                    previousIdentifier = identifier
                }

                else -> {
                    var closesSuspendingCall = false
                    when (char) {
                        '(' -> {
                            parenDepth++
                            if (previousIdentifier in CONTROL_FLOW_KEYWORDS) {
                                controlFlowParenDepths.addLast(parenDepth)
                            }
                        }
                        ')' -> {
                            lastParenWasControlFlow = controlFlowParenDepths.lastOrNull() == parenDepth
                            if (lastParenWasControlFlow) controlFlowParenDepths.removeLast()
                            if (suspendingCloseDepths.lastOrNull() == parenDepth) {
                                suspendingCloseDepths.removeLast()
                                closesSuspendingCall = true
                            }
                            parenDepth--
                        }
                        '[' -> bracketDepth++
                        ']' -> bracketDepth--
                        '=' -> if (next == '>') {
                            // Which arrow form this is decides how its body is
                            // tracked, and only the token after `=>` tells them
                            // apart.
                            if (nextSignificant(source, index + 2) == '{') {
                                pendingArrowBody = true
                            } else {
                                conciseArrowBodies.addLast(
                                    ConciseArrowBody(parenDepth, braceDepth, bracketDepth),
                                )
                            }
                        }
                        '{' -> {
                            braceDepth++
                            if (functionBodyOpensHere(previousSignificant, lastParenWasControlFlow, pendingArrowBody)) {
                                functionBodyDepths.addLast(braceDepth)
                            }
                            pendingArrowBody = false
                        }
                        '}' -> {
                            if (functionBodyDepths.lastOrNull() == braceDepth) functionBodyDepths.removeLast()
                            braceDepth--
                        }
                        ';' -> pendingArrowBody = false
                    }
                    while (
                        conciseArrowBodies.isNotEmpty() &&
                        conciseArrowBodyEndsHere(
                            conciseArrowBodies.last(),
                            char,
                            previousSignificant,
                            parenDepth,
                            braceDepth,
                            bracketDepth,
                        )
                    ) {
                        conciseArrowBodies.removeLast()
                    }
                    out.append(char)
                    if (closesSuspendingCall) out.append(')')
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

    /**
     * Whether the `{` just reached opens a function body rather than a block.
     *
     * A scanner cannot parse, so this is a heuristic on the token before it: a
     * `{` after `)` is a body unless that `)` closed a control-flow head, and a
     * `{` after `=>` is always one. That covers `function f() {}`, a method
     * (`foo() {}` in a class or an object literal), and both arrow forms.
     *
     * It is built to fail safe. Deciding "function" wrongly costs an output call
     * its yield point — the call still runs, the script just pauses less often —
     * and makes an `open` there report the nested-call restriction instead of
     * reading the file. Deciding "block" wrongly re-creates the behaviour that
     * existed before this tracking: an illegal `yield` that the host reports as a
     * compilation error.
     */
    private fun functionBodyOpensHere(
        previousSignificant: Char?,
        lastParenWasControlFlow: Boolean,
        pendingArrowBody: Boolean,
    ): Boolean {
        if (pendingArrowBody) return true
        return previousSignificant == ')' && !lastParenWasControlFlow
    }

    /** Nesting a braceless arrow body started at, which is where it also ends. */
    private class ConciseArrowBody(
        val parenDepth: Int,
        val braceDepth: Int,
        val bracketDepth: Int,
    )

    /**
     * Whether the token just consumed ended the braceless arrow body [body].
     *
     * `v => print(v)` has no `{` to match, so the body ends where its expression
     * does: a bracket that closes past the nesting the arrow started at, a `;` or
     * `,` at that nesting, or a line break that is not obviously a continuation —
     * the same places the parser would end it.
     *
     * Both mistakes are contained. Ending too early re-creates the old behaviour
     * for whatever follows on that line. Ending too late costs the rest of the
     * statement its yields, and — since `open` is rewritten the same way — makes
     * an `open` there report the nested-call restriction instead of reading the
     * file, which is what a genuinely nested `open` already does.
     */
    private fun conciseArrowBodyEndsHere(
        body: ConciseArrowBody,
        char: Char,
        previousSignificant: Char?,
        parenDepth: Int,
        braceDepth: Int,
        bracketDepth: Int,
    ): Boolean = when (char) {
        ')' -> parenDepth < body.parenDepth
        ']' -> bracketDepth < body.bracketDepth
        '}' -> braceDepth < body.braceDepth
        ';' -> parenDepth <= body.parenDepth
        ',' -> parenDepth <= body.parenDepth &&
            braceDepth <= body.braceDepth &&
            bracketDepth <= body.bracketDepth

        '\n' -> parenDepth <= body.parenDepth &&
            braceDepth <= body.braceDepth &&
            bracketDepth <= body.bracketDepth &&
            (previousSignificant == null || previousSignificant !in EXPRESSION_CONTINUING_CHARS)

        else -> false
    }

    /**
     * Characters after which a line break cannot end an expression, so a
     * braceless arrow body written across lines is not cut in half.
     */
    private val EXPRESSION_CONTINUING_CHARS = setOf(
        ',', '+', '-', '*', '/', '%', '&', '|', '^', '=', '<', '>', '?', ':', '(', '[', '{', '.', '!', '~',
    )

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
