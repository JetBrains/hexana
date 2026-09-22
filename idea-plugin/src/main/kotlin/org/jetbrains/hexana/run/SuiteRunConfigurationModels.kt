package org.jetbrains.hexana.run

import org.jetbrains.hexana.shared.scripting.SUITE_COLLECTION_ERROR_TEST_NAME
import org.jetbrains.hexana.shared.scripting.SuiteArtifactDeclaration
import org.jetbrains.hexana.shared.scripting.SuiteNodeKind
import org.jetbrains.hexana.shared.scripting.SuiteNodeResult
import org.jetbrains.hexana.shared.scripting.SuiteNodeStatus
import java.io.File

/**
 * Pure model functions for the Hexana Suite run configuration: TeamCity
 * service-message rendering of a [SuiteNodeResult] tree, CLI command building,
 * artifact "role=path" parsing, suite-file discovery, and the describe-path
 * mapping used by rerun-failed. No IDE platform types so everything here is
 * unit-testable without a fixture (and without GraalJS).
 */

/** Protocol of the SM locationHint URLs emitted for suite nodes. */
internal const val HEXANA_SCRIPT_LOCATION_PROTOCOL = "hexana-script"

/**
 * Escapes [s] for a TeamCity service-message attribute value.
 *
 * Full table: `|` -> `||`, `'` -> `|'`, `[` -> `|[`, `]` -> `|]`,
 * `\n` -> `|n`, `\r` -> `|r`, U+0085 -> `|x`, U+2028 -> `|l`, U+2029 -> `|p`.
 *
 * Implemented locally because the TeamCity `serviceMessages` library ships no
 * builder class in the IDE distribution (only the parser side).
 */
internal fun tcEscape(s: String): String = buildString(s.length) {
    for (ch in s) {
        when (ch) {
            '|' -> append("||")
            '\'' -> append("|'")
            '[' -> append("|[")
            ']' -> append("|]")
            '\n' -> append("|n")
            '\r' -> append("|r")
            '\u0085' -> append("|x")
            '\u2028' -> append("|l")
            '\u2029' -> append("|p")
            else -> append(ch)
        }
    }
}

/**
 * The single local builder for `##teamcity[...]` service messages. Every
 * message this feature emits goes through here; attribute order is the order
 * of [attributes].
 */
internal fun tcMessage(name: String, attributes: List<Pair<String, String>>): String = buildString {
    append("##teamcity[").append(name)
    for ((key, value) in attributes) {
        append(' ').append(key).append("='").append(tcEscape(value)).append('\'')
    }
    append(']')
}

/** `hexana-script://<absoluteFile>:<line>` hint, or null when the node has no source location. */
internal fun suiteLocationHint(sourceFile: String?, sourceLine: Int?): String? =
    if (sourceFile != null && sourceLine != null) {
        "$HEXANA_SCRIPT_LOCATION_PROTOCOL://$sourceFile:$sourceLine"
    } else {
        null
    }

/**
 * Converts one suite file's result tree into the ordered list of TeamCity
 * service messages that drive the SM test runner console.
 *
 * - The root node is rendered as a suite named [fileName] (the engine's root
 *   name is the same file name; the parameter keeps the mapping explicit).
 * - SUITE children become nested testSuiteStarted/testSuiteFinished pairs.
 * - TEST nodes: PASS -> testStarted + testFinished(duration); FAIL ->
 *   testStarted + testFailed(message, details=stack line) + testFinished;
 *   SKIP -> a standalone testIgnored.
 * - locationHint is attached when the node carries a source location.
 *
 * Hostile names (containing `|`, quotes, brackets, newlines) are safe: every
 * attribute value passes through [tcEscape].
 */
internal fun suiteTreeToServiceMessages(fileName: String, root: SuiteNodeResult): List<String> {
    val messages = mutableListOf<String>()
    messages += tcMessage("testSuiteStarted", listOf("name" to fileName))
    for (child in root.children) appendNodeMessages(messages, child)
    messages += tcMessage("testSuiteFinished", listOf("name" to fileName))
    return messages
}

private fun appendNodeMessages(messages: MutableList<String>, node: SuiteNodeResult) {
    val hint = suiteLocationHint(node.sourceFile, node.sourceLine)
    when (node.kind) {
        SuiteNodeKind.SUITE -> {
            messages += tcMessage("testSuiteStarted", attrs(node.name, hint))
            for (child in node.children) appendNodeMessages(messages, child)
            messages += tcMessage("testSuiteFinished", listOf("name" to node.name))
        }
        SuiteNodeKind.TEST -> when (node.status) {
            SuiteNodeStatus.SKIP -> messages += tcMessage("testIgnored", attrs(node.name, hint))
            SuiteNodeStatus.PASS, SuiteNodeStatus.FAIL -> {
                messages += tcMessage("testStarted", attrs(node.name, hint))
                if (node.status == SuiteNodeStatus.FAIL) {
                    val failedAttrs = mutableListOf("name" to node.name)
                    failedAttrs += "message" to (node.failureMessage ?: "Test failed")
                    node.failureStackLine?.let { failedAttrs += "details" to it }
                    messages += tcMessage("testFailed", failedAttrs)
                }
                messages += tcMessage(
                    "testFinished",
                    listOf("name" to node.name, "duration" to node.durationMillis.toString()),
                )
            }
        }
    }
}

private fun attrs(name: String, locationHint: String?): List<Pair<String, String>> =
    if (locationHint != null) listOf("name" to name, "locationHint" to locationHint)
    else listOf("name" to name)

/**
 * Service messages for a suite file whose collection pass failed
 * (SCRIPT_ERROR / INVALID_INPUT / TIMEOUT before any test ran): a suite named
 * after the file with one synthetic failed test named "(collection)", carrying
 * the diagnostics text. Mirrors the CLI's synthetic report entry.
 */
internal fun collectionErrorServiceMessages(fileName: String, message: String): List<String> = listOf(
    tcMessage("testSuiteStarted", listOf("name" to fileName)),
    tcMessage("testStarted", listOf("name" to SUITE_COLLECTION_ERROR_TEST_NAME)),
    tcMessage("testFailed", listOf("name" to SUITE_COLLECTION_ERROR_TEST_NAME, "message" to message)),
    tcMessage("testFinished", listOf("name" to SUITE_COLLECTION_ERROR_TEST_NAME, "duration" to "0")),
    tcMessage("testSuiteFinished", listOf("name" to fileName)),
)

/**
 * Maps an SM proxy name chain to the engine's describe path.
 *
 * [namesFromFileSuiteToTest] is the chain of proxy names starting at the
 * file-level suite (the child of the invisible SM root) down to the test.
 * The engine's test-path filter excludes the root group (the suite file), so
 * the describe path is the chain minus its first element. Returns null for a
 * chain too short to contain a test.
 */
internal fun proxyPathOf(namesFromFileSuiteToTest: List<String>): List<String>? =
    if (namesFromFileSuiteToTest.size < 2) null else namesFromFileSuiteToTest.drop(1)

/**
 * Builds the CLI command equivalent of a suite run configuration:
 * `hexana-cli-script test <suitePath> [--artifact role=path]... [--lib-root d]
 * [--seed s] [--test-timeout ms]`. Argument order is pinned; matches the forms
 * accepted by the product CLI's `test` subcommand parser.
 */
fun buildSuiteCliCommand(
    suitePath: String,
    artifacts: List<Pair<String, String>>,
    libRoot: String?,
    seed: String?,
    testTimeoutMillis: Long?,
): List<String> {
    val cmd = mutableListOf("hexana-cli-script", "test", suitePath)
    for ((role, path) in artifacts) {
        cmd.add("--artifact")
        cmd.add("$role=$path")
    }
    if (!libRoot.isNullOrEmpty()) {
        cmd.add("--lib-root")
        cmd.add(libRoot)
    }
    if (!seed.isNullOrEmpty()) {
        cmd.add("--seed")
        cmd.add(seed)
    }
    if (testTimeoutMillis != null) {
        cmd.add("--test-timeout")
        cmd.add(testTimeoutMillis.toString())
    }
    return cmd
}

/**
 * Parses stored artifact entries plus free-form text into ordered
 * (role, path) pairs. Each entry / line has the form `role=path` (first `=`
 * splits; role must be non-empty). Malformed or blank lines are skipped.
 * A later entry for the same role overrides an earlier one.
 */
internal fun parseArtifactEntries(entries: List<String>, freeForm: String): List<Pair<String, String>> {
    val byRole = LinkedHashMap<String, String>()
    for (raw in entries + freeForm.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val eq = line.indexOf('=')
        if (eq <= 0 || eq == line.lastIndex) continue
        byRole[line.substring(0, eq)] = line.substring(eq + 1)
    }
    return byRole.toList()
}

/**
 * Resolves the configured suite path to the ordered list of suite files to
 * run. A directory is walked recursively for `*.test.js`, sorted; anything
 * else is passed through as-is (validation happens inside the runner).
 * The CLI walk in the `test` subcommand is the source of truth for discovery
 * semantics; this function mirrors it exactly.
 */
internal fun discoverSuiteFiles(suitePath: String): List<String> {
    val f = File(suitePath)
    if (f.isDirectory) {
        return f.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".test.js") }
            .map { it.path }
            .sorted()
            .toList()
    }
    return listOf(suitePath)
}

// ----- Declaration-driven artifact table -----

/**
 * One row in the artifact table shown in the suite settings editor.
 * Corresponds to one [SuiteArtifactDeclaration] from the suite's collection pass.
 */
data class ArtifactRow(
    val role: String,
    val formats: List<String>,
    val optional: Boolean,
    /** Current path value; empty string means not configured. */
    var path: String,
)

/**
 * Maps a list of [SuiteArtifactDeclaration] entries to table rows, merging in
 * any paths already stored as "role=path" strings in [storedValues].
 * Roles absent from [storedValues] get an empty path; unknown stored roles are
 * preserved only if their role appears in the declarations (unknown roles are
 * dropped; the table is authoritative).
 */
internal fun declarationsToRows(
    declarations: List<SuiteArtifactDeclaration>,
    storedValues: Map<String, String>,
): List<ArtifactRow> = declarations.map { decl ->
    ArtifactRow(
        role = decl.role,
        formats = decl.formats,
        optional = decl.optional,
        path = storedValues[decl.role] ?: "",
    )
}

/**
 * Converts table rows back to the "role=path" storage format.
 * Rows with an empty path are included only when [includeEmpty] is true
 * (the default is false; they carry no useful information to the runner).
 */
internal fun rowsToArtifactValues(
    rows: List<ArtifactRow>,
    includeEmpty: Boolean = false,
): List<String> = rows
    .filter { includeEmpty || it.path.isNotEmpty() }
    .map { "${it.role}=${it.path}" }

/**
 * Decodes a list of stored "role=path" strings into a map keyed by role.
 * The last entry for a given role wins (mirrors [parseArtifactEntries]).
 */
internal fun storedValuesToMap(values: List<String>): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (entry in values) {
        val eq = entry.indexOf('=')
        if (eq <= 0) continue
        result[entry.substring(0, eq)] = entry.substring(eq + 1)
    }
    return result
}

/**
 * Finds the single non-optional role in [declarations] that matches [binaryFormat].
 *
 * A role matches when either its `formats` list is empty (accepts any format) or
 * [binaryFormat] appears in `formats`. Returns the role name when exactly one
 * required (non-optional) role matches; null otherwise (zero matches, or multiple
 * matches where the suite needs a specific binary to be targeted).
 *
 * Used by `HexanaRunSuitesForBinaryAction` to decide which suite roles accept the
 * currently open binary.
 */
internal fun matchingSuiteRole(
    declarations: List<SuiteArtifactDeclaration>,
    binaryFormat: String,
): String? {
    val required = declarations.filter { !it.optional }
    val matching = required.filter { decl ->
        decl.formats.isEmpty() || binaryFormat in decl.formats
    }
    return if (matching.size == 1) matching.first().role else null
}
