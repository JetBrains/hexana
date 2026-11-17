package org.jetbrains.hexana.cli.scripting

import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.jetbrains.hexana.mcp.scripting.HexanaScriptStatus
import org.jetbrains.hexana.mcp.scripting.HexanaSuiteRunner
import org.jetbrains.hexana.shared.scripting.ConsoleSeverity
import org.jetbrains.hexana.shared.scripting.SUITE_COLLECTION_ERROR_TEST_NAME
import org.jetbrains.hexana.shared.scripting.SuiteNodeKind
import org.jetbrains.hexana.shared.scripting.SuiteNodeResult
import org.jetbrains.hexana.shared.scripting.SuiteNodeStatus
import java.io.File
import java.nio.file.Path

public const val RUN_SCRIPT_SUITE_TOOL_NAME: String = "run_script_suite"
public const val TEST_COMMAND_NAME: String = "test"

public data class RunSuiteCliRequest(
    val suiteFiles: List<String>,
    val artifacts: Map<String, String> = emptyMap(),
    val libRoot: String? = null,
    val seed: String? = null,
    val testTimeoutMillis: Long? = null,
    val limits: HexanaScriptLimits = HexanaScriptLimits.interactive(),
    val allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
    val junitReportPath: Path? = null,
)

public data class RunSuiteCliOutput(
    val stdout: String,
    val stderr: String = "",
    val exitCode: Int = 0,
)

/** Supported output formats for the suite tool; always RAW. */
public fun suiteSupportedOutputFormats(): Set<ToolOutputFormat> =
    setOf(ToolOutputFormat.RAW)

/**
 * Top-level entry point for the `hexana-cli-script test` command.
 *
 * - Discovers suite files from [RunSuiteCliRequest.suiteFiles] (file paths or a
 *   single directory; directory is walked for `*.test.js`, sorted)
 * - Runs each file through [HexanaSuiteRunner.runSuite]
 * - Streams console entries to stderr with level prefixes
 * - Writes a human-readable test report to stdout
 * - Writes a JUnit XML report to [RunSuiteCliRequest.junitReportPath] if set
 * - Returns exit code 0 on all-pass, 1 on any FAIL or SCRIPT_ERROR, 2 on usage error
 */
public fun runSuiteCli(request: RunSuiteCliRequest): RunSuiteCliOutput {
    val resolvedFiles = resolveInputs(request.suiteFiles)
    if (resolvedFiles.isEmpty()) {
        return RunSuiteCliOutput(
            stdout = "",
            stderr = "test: no suite files found.\n",
            exitCode = 2,
        )
    }

    val runner = HexanaSuiteRunner(
        limits = request.limits,
        allowedFilePolicy = request.allowedFilePolicy,
    )

    // Per-file results for JUnit and final summary
    val suiteResults = mutableListOf<Pair<String, SuiteNodeResult>>()
    val stderrLines = StringBuilder()
    var anyFailure = false

    for (filePath in resolvedFiles) {
        val scriptResult = runner.runSuite(
            suiteFilePath = filePath,
            artifacts = request.artifacts,
            libRoot = request.libRoot,
            seed = request.seed,
            testTimeoutMillis = request.testTimeoutMillis ?: HexanaSuiteRunner.DEFAULT_TEST_TIMEOUT_MILLIS,
            onConsole = { entry ->
                val prefix = when (entry.severity) {
                    ConsoleSeverity.LOG -> "[log] "
                    ConsoleSeverity.ERROR -> "[error] "
                    ConsoleSeverity.DEBUG -> "[debug] "
                }
                stderrLines.append("$prefix${entry.text}\n")
            },
        )

        // Console entries already forwarded via onConsole above.
        // Only append any diagnostics for non-SUCCESS runs to stderr.
        if (scriptResult.status != HexanaScriptStatus.SUCCESS) {
            anyFailure = true
            for (d in scriptResult.diagnostics) {
                stderrLines.append("[error] ${d.code}: ${d.message}\n")
            }
            // Synthesize a failed suite node so the report still shows the file.
            val fileName = File(filePath).name
            val diagnostic = scriptResult.diagnostics.firstOrNull()?.message ?: scriptResult.status.name
            val syntheticTest = SuiteNodeResult(
                name = SUITE_COLLECTION_ERROR_TEST_NAME,
                kind = SuiteNodeKind.TEST,
                status = SuiteNodeStatus.FAIL,
                durationMillis = 0,
                failureMessage = diagnostic,
            )
            val syntheticTree = SuiteNodeResult(
                name = fileName,
                kind = SuiteNodeKind.SUITE,
                status = SuiteNodeStatus.FAIL,
                durationMillis = scriptResult.durationMillis,
                children = listOf(syntheticTest),
            )
            suiteResults += filePath to syntheticTree
        } else {
            val tree = scriptResult.suite
            if (tree != null) {
                if (tree.status == SuiteNodeStatus.FAIL) anyFailure = true
                suiteResults += filePath to tree
            }
        }
    }

    val stdout = buildReport(suiteResults)

    if (request.junitReportPath != null) {
        JUnitXmlWriter.write(suiteResults, request.junitReportPath)
    }

    return RunSuiteCliOutput(
        stdout = stdout,
        stderr = stderrLines.toString(),
        exitCode = if (anyFailure) 1 else 0,
    )
}

// ---------------------------------------------------------------------------
// File discovery
// ---------------------------------------------------------------------------

/**
 * Resolves a list of paths to an ordered list of concrete suite file paths.
 *
 * - A single-element list pointing to a directory: walks `*.test.js` recursively, sorted
 * - Otherwise: each path is used as-is (validation happens inside the runner)
 */
internal fun resolveInputs(inputs: List<String>): List<String> {
    if (inputs.size == 1) {
        val f = File(inputs[0])
        if (f.isDirectory) {
            return f.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".test.js") }
                .map { it.path }
                .sorted()
                .toList()
        }
    }
    return inputs
}

// ---------------------------------------------------------------------------
// Report rendering
// ---------------------------------------------------------------------------

internal fun buildReport(suiteResults: List<Pair<String, SuiteNodeResult>>): String {
    val sb = StringBuilder()

    var totalPassed = 0
    var totalFailed = 0
    var totalSkipped = 0
    var totalMillis = 0L

    for ((_, rootNode) in suiteResults) {
        sb.append("Suite: ${rootNode.name}\n")
        for (child in rootNode.children) {
            appendNodeReport(sb, child, path = emptyList())
        }
        val (p, f, s) = countResults(rootNode)
        totalPassed += p
        totalFailed += f
        totalSkipped += s
        totalMillis += rootNode.durationMillis
        sb.append("\n")
    }

    sb.append("$totalPassed passed, $totalFailed failed, $totalSkipped skipped ($totalMillis ms)\n")
    return sb.toString()
}

private fun appendNodeReport(sb: StringBuilder, node: SuiteNodeResult, path: List<String>) {
    when (node.kind) {
        SuiteNodeKind.SUITE -> {
            for (child in node.children) {
                appendNodeReport(sb, child, path + node.name)
            }
        }
        SuiteNodeKind.TEST -> {
            val marker = when (node.status) {
                SuiteNodeStatus.PASS -> "PASS"
                SuiteNodeStatus.FAIL -> "FAIL"
                SuiteNodeStatus.SKIP -> "SKIP"
            }
            val fullName = (path + node.name).joinToString(" > ")
            sb.append("[$marker] $fullName (${node.durationMillis} ms)\n")
            if (node.status == SuiteNodeStatus.FAIL) {
                val msg = node.failureMessage
                if (msg != null) sb.append("       $msg\n")
                val stack = node.failureStackLine
                if (stack != null) sb.append("       at $stack\n")
            }
        }
    }
}

private data class Counts(val passed: Int, val failed: Int, val skipped: Int)

private fun countResults(node: SuiteNodeResult): Counts {
    if (node.kind == SuiteNodeKind.TEST) {
        return when (node.status) {
            SuiteNodeStatus.PASS -> Counts(1, 0, 0)
            SuiteNodeStatus.FAIL -> Counts(0, 1, 0)
            SuiteNodeStatus.SKIP -> Counts(0, 0, 1)
        }
    }
    return node.children.fold(Counts(0, 0, 0)) { acc, child ->
        val c = countResults(child)
        Counts(acc.passed + c.passed, acc.failed + c.failed, acc.skipped + c.skipped)
    }
}
