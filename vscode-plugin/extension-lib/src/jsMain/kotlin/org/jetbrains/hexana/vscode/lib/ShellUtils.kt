@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

/**
 * Splits a string into shell-like arguments, respecting double-quoted segments.
 *
 * For example `--invoke "add(1, 2)"` becomes `["--invoke", "add(1, 2)"]`.
 */
@JsExport
fun splitShellArgs(input: String): Array<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    for (ch in input) {
        when {
            ch == '"' -> inQuotes = !inQuotes
            ch == ' ' && !inQuotes -> {
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current.clear()
                }
            }
            else -> current.append(ch)
        }
    }
    if (current.isNotEmpty()) {
        result.add(current.toString())
    }
    return result.toTypedArray()
}

private val SAFE_ARG_REGEX = Regex("^[a-zA-Z0-9_./:=-]+$")

/**
 * Escapes a single shell argument for safe use in a terminal command.
 */
@JsExport
fun shellEscape(arg: String): String {
    if (SAFE_ARG_REGEX.matches(arg)) return arg
    return "'" + arg.replace("'", "'\\''") + "'"
}
