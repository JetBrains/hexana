package org.jetbrains.hexana.run

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.jetbrains.hexana.mcp.scripting.HexanaScriptResult
import org.jetbrains.hexana.mcp.scripting.HexanaScriptRunner
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSource
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ConsoleSeverity
import org.jetbrains.hexana.shared.scripting.ScriptAnchor
import org.jetbrains.hexana.shared.scripting.ScriptInvocation
import org.jetbrains.hexana.ui.panel.ScriptFindingsStore
import org.jetbrains.hexana.ui.panel.navigateToFunctionInWat
import java.io.OutputStream

/**
 * Runs a Hexana script in-process on a pooled thread and streams results to the
 * Run console. The IDE run console is driven by a [HexanaScriptProcessHandler]
 * that the pooled thread notifies as output arrives.
 *
 * This is the execution seam: keep it as a single callable function so a CDP
 * inspector could wrap it later.
 */
class HexanaScriptCommandState(
    private val configuration: HexanaScriptRunConfiguration,
    private val environment: ExecutionEnvironment,
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val consoleView = ConsoleViewImpl(environment.project, false)
        val handler = HexanaScriptProcessHandler()
        consoleView.attachToProcess(handler)

        ApplicationManager.getApplication().executeOnPooledThread {
            val exitCode = runScript(configuration, handler, consoleView)
            handler.notifyProcessTerminated(exitCode)
        }

        return DefaultExecutionResult(consoleView, handler)
    }
}

/**
 * Executes the script described by [config] and streams output to [handler]/[console].
 *
 * This is a pure function with no IDE platform state beyond what is passed in.
 * Returns exit code 0 (success with no ERROR findings) or 1 (ERROR finding or failure).
 */
internal fun runScript(
    config: HexanaScriptRunConfiguration,
    handler: HexanaScriptProcessHandler,
    console: ConsoleViewImpl,
): Int {
    val scriptPath = config.scriptPath
    val inputPaths = config.inputPaths
    val primaryPath = inputPaths.firstOrNull() ?: ""
    val extraPaths = inputPaths.drop(1)

    val flagTokens = buildFlagTokensFromConfig(config)
    val argv = buildList {
        add(scriptPath)
        addAll(flagTokens)
    }
    val invocation = ScriptInvocation(argv = argv, extraInputPaths = extraPaths)

    val runner = HexanaScriptRunner(
        limits = HexanaScriptLimits.interactive(),
        allowedFilePolicy = AllowedFilePolicy.allowAll,
    )

    val source = HexanaScriptSource.FilePath(scriptPath)
    val input: BinaryInput = if (primaryPath.isNotEmpty()) {
        BinaryInput.FilePath(primaryPath)
    } else {
        // Minimal valid wasm: magic (4 bytes) + version (4 bytes)
        BinaryInput.RawBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00))
    }

    val result: HexanaScriptResult = runner.run(input, source, invocation)

    streamResultToConsole(result, handler, console, config)

    // Push findings to the ScriptFindingsStore so the Problems tab reflects them.
    if (primaryPath.isNotEmpty()) {
        val primaryFile = LocalFileSystem.getInstance().findFileByPath(primaryPath)
        if (primaryFile != null) {
            ScriptFindingsStore.getInstance(config.project)
                .replaceFindings(primaryFile, scriptPath.substringAfterLast('/'), result.findings)
        }
    }

    return exitCodeFor(result)
}

/**
 * Streams [result] lines, console entries, and findings to the run console.
 * Called from a pooled thread; all console writes via [handler.notifyTextAvailable]
 * are thread-safe.
 */
internal fun streamResultToConsole(
    result: HexanaScriptResult,
    handler: HexanaScriptProcessHandler,
    console: ConsoleViewImpl,
    config: HexanaScriptRunConfiguration,
) {
    // Output lines -> STDOUT
    for (line in result.output.lines) {
        handler.notifyTextAvailable(line + "\n", ProcessOutputType.STDOUT)
    }

    // Console entries -> STDERR with severity prefix
    for (entry in result.console) {
        val prefix = when (entry.severity) {
            ConsoleSeverity.LOG -> "[log]"
            ConsoleSeverity.ERROR -> "[error]"
            ConsoleSeverity.DEBUG -> "[debug]"
        }
        handler.notifyTextAvailable("$prefix ${entry.text}\n", ProcessOutputType.STDERR)
    }

    // Diagnostics -> STDERR
    for (diag in result.diagnostics) {
        handler.notifyTextAvailable("[diagnostic] ${diag.code}: ${diag.message}\n", ProcessOutputType.STDERR)
    }

    // Findings summary -> clickable hyperlinks in the console
    if (result.findings.isNotEmpty()) {
        handler.notifyTextAvailable("--- Findings (${result.findings.size}) ---\n", ProcessOutputType.STDOUT)
        for (finding in result.findings) {
            val severity = finding.severity.name
            val anchor = finding.anchor
            val anchorText = when (anchor) {
                is ScriptAnchor.Function -> " (func ${anchor.funcidx})"
                is ScriptAnchor.ByteRange -> " (offset 0x${anchor.offset.toString(16)})"
                null -> ""
            }
            val line = "$severity ${finding.checkId} ${finding.message}$anchorText\n"

            // Print as a hyperlink when there is a navigable anchor and an input file
            val primaryPath = config.inputPaths.firstOrNull() ?: ""
            if (anchor != null && primaryPath.isNotEmpty()) {
                ApplicationManager.getApplication().invokeLater {
                    console.printHyperlink(line) {
                        navigateToAnchor(primaryPath, anchor, config)
                    }
                }
            } else {
                handler.notifyTextAvailable(line, ProcessOutputType.STDOUT)
            }
        }
    }

    // Usage errors
    for (error in result.usageErrors) {
        handler.notifyTextAvailable("[usage-error] $error\n", ProcessOutputType.STDERR)
    }
}

private fun navigateToAnchor(
    binaryPath: String,
    anchor: ScriptAnchor,
    config: HexanaScriptRunConfiguration,
) {
    val project = config.project
    val file = LocalFileSystem.getInstance().findFileByPath(binaryPath) ?: return
    when (anchor) {
        is ScriptAnchor.Function -> {
            navigateToFunctionInWat(file, project, anchor.funcidx.toInt())
        }
        is ScriptAnchor.ByteRange -> {
            val editors = FileEditorManager.getInstance(project).openFile(file, false)
            val editor = editors.filterIsInstance<HexanaFileEditor>().firstOrNull() ?: return
            editor.stateHolder.scrollHexViewTo(anchor.offset, setCursor = true)
        }
    }
}

/**
 * Returns the exit code for a script result.
 *
 * - 2 when there are usage errors (undeclared flags, etc.), matching the CLI.
 * - 1 when the script itself failed or any ERROR finding is present.
 * - 0 otherwise.
 */
internal fun exitCodeFor(result: HexanaScriptResult): Int {
    if (result.usageErrors.isNotEmpty()) return 2
    if (!result.success) return 1
    if (result.findings.any { it.severity == CheckSeverity.ERROR }) return 1
    return 0
}

/** Converts persisted flag values + free-form to argv tokens (after script path). */
internal fun buildFlagTokensFromConfig(config: HexanaScriptRunConfiguration): List<String> {
    val stored = config.flagValues
    if (stored.isNotEmpty()) return decodeStoredFlagTokens(stored)
    // Degraded mode: split free-form text
    return config.freeFormFlags.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}

/**
 * A minimal [ProcessHandler] that the pooled script thread drives by calling
 * [notifyTextAvailable] and [notifyProcessTerminated].
 *
 * No real process is spawned; the handler delegates all I/O to the caller.
 */
class HexanaScriptProcessHandler : ProcessHandler() {
    override fun destroyProcessImpl() { notifyProcessTerminated(1) }
    override fun detachProcessImpl() { notifyProcessDetached() }
    override fun detachIsDefault(): Boolean = false
    override fun getProcessInput(): OutputStream? = null

    public override fun notifyTextAvailable(text: String, outputType: com.intellij.openapi.util.Key<*>) {
        super.notifyTextAvailable(text, outputType)
    }

    public override fun notifyProcessTerminated(exitCode: Int) {
        super.notifyProcessTerminated(exitCode)
    }
}
