package org.jetbrains.hexana.run

import org.jetbrains.hexana.shared.scripting.FlagSpec
import org.jetbrains.hexana.shared.scripting.FlagType

/**
 * Decodes a list of stored flag entries (format: "name=value" or "name=__BOOL_TRUE__")
 * into argv tokens suitable for passing after the script path.
 *
 * Each entry becomes either a single `--name` token (BOOL) or a `--name value` pair.
 * Extracted as a pure function so tests exercise the production logic directly.
 */
internal fun decodeStoredFlagTokens(stored: List<String>): List<String> =
    stored.flatMap { entry ->
        val eq = entry.indexOf('=')
        if (eq < 0) listOf("--$entry")
        else {
            val name = entry.substring(0, eq)
            val value = entry.substring(eq + 1)
            if (value == "__BOOL_TRUE__") listOf("--$name")
            else listOf("--$name", value)
        }
    }

/**
 * One row in the flags table shown in the settings editor.
 * Corresponds to one [FlagSpec] entry from the script's defineFlags call.
 */
data class FlagRow(
    val name: String,
    val spec: FlagSpec,
    /** Current value shown in the table. Null means "use default". */
    var value: String?,
)

/**
 * Model state for the flags form.
 *
 * When [declarations] is non-null and non-empty, the table form is shown.
 * When it is null or empty (degraded mode or collection pass failed), the
 * free-form text field is shown instead.
 */
data class FlagsFormModel(
    val rows: List<FlagRow>,
    val degraded: Boolean,
) {
    companion object {
        val Empty = FlagsFormModel(rows = emptyList(), degraded = true)

        fun fromDeclarations(declarations: Map<String, FlagSpec>): FlagsFormModel {
            if (declarations.isEmpty()) return Empty
            val rows = declarations.map { (name, spec) ->
                FlagRow(name = name, spec = spec, value = spec.default)
            }
            return FlagsFormModel(rows = rows, degraded = false)
        }
    }
}

/**
 * Converts a [FlagsFormModel] and a free-form string into the argv tail tokens
 * that appear after `--` in the CLI command.
 *
 * Table form: one token pair (or single token for BOOL) per row.
 * Free-form: the string is split on whitespace (naive; no shell quoting).
 */
fun buildFlagArgvTokens(model: FlagsFormModel, freeForm: String): List<String> {
    if (model.degraded) {
        return freeForm.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
    val tokens = mutableListOf<String>()
    for (row in model.rows) {
        val v = row.value
        when (row.spec.type) {
            FlagType.BOOL -> {
                val asTrue = v == "true" || v == null
                if (asTrue) tokens.add("--${row.name}")
            }
            else -> {
                if (v != null) {
                    tokens.add("--${row.name}")
                    tokens.add(v)
                }
            }
        }
    }
    return tokens
}

/**
 * Builds the CLI command equivalent of the current run configuration.
 *
 * Returns a list of tokens suitable for display (join with spaces).
 * Flag tokens go after `--` if any are present.
 *
 * @param scriptPath the resolved script file path
 * @param inputPaths ordered list: [0] = primary, [1..] = extras
 * @param baselinesDir optional baselines directory path
 * @param flagTokens pre-built flag argv tokens (from [buildFlagArgvTokens])
 */
fun buildCliCommand(
    scriptPath: String,
    inputPaths: List<String>,
    baselinesDir: String,
    flagTokens: List<String>,
): List<String> {
    val cmd = mutableListOf("hexana-cli-script", "run-script")
    val primary = inputPaths.firstOrNull() ?: ""
    if (primary.isNotEmpty()) cmd.add(primary)
    cmd.add("--script")
    cmd.add(scriptPath)
    for (extra in inputPaths.drop(1)) {
        cmd.add("--input")
        cmd.add(extra)
    }
    if (baselinesDir.isNotEmpty()) {
        cmd.add("--baselines-dir")
        cmd.add(baselinesDir)
    }
    if (flagTokens.isNotEmpty()) {
        cmd.add("--")
        cmd.addAll(flagTokens)
    }
    return cmd
}
