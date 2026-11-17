package org.jetbrains.hexana.suite

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.jetbrains.hexana.mcp.scripting.HexanaScriptStatus
import org.jetbrains.hexana.mcp.scripting.HexanaSuiteRunner
import org.jetbrains.hexana.run.HexanaScriptProcessHandler
import org.jetbrains.hexana.run.collectionErrorServiceMessages
import org.jetbrains.hexana.run.discoverSuiteFiles
import org.jetbrains.hexana.run.parseArtifactEntries
import org.jetbrains.hexana.run.suiteTreeToServiceMessages
import org.jetbrains.hexana.shared.scripting.ConsoleSeverity
import org.jetbrains.hexana.shared.scripting.SuiteNodeStatus
import java.io.File

/**
 * Runs a Hexana test suite in-process on a pooled thread and drives the SM
 * test-runner console through TeamCity service messages emitted on the
 * [HexanaScriptProcessHandler]. Mirrors the CLI `test` command: same file
 * discovery, same synthetic "(collection)" failure for collection errors, and
 * the same exit-code mapping (0 all pass, 1 any FAIL or collection error,
 * 2 usage error).
 */
class HexanaSuiteCommandState(
    private val configuration: HexanaSuiteRunConfiguration,
    private val environment: ExecutionEnvironment,
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val consoleProperties = HexanaSuiteConsoleProperties(configuration, executor ?: environment.executor)
        val handler = HexanaScriptProcessHandler()
        val consoleView = SMTestRunnerConnectionUtil.createAndAttachConsole(
            HexanaSuiteConsoleProperties.FRAMEWORK_NAME,
            handler,
            consoleProperties,
        )

        handler.addProcessListener(object : ProcessAdapter() {
            override fun startNotified(event: ProcessEvent) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    val exitCode = runSuiteConfiguration(configuration, handler)
                    handler.notifyProcessTerminated(exitCode)
                }
            }
        })

        val result = DefaultExecutionResult(consoleView, handler)
        val rerunAction = consoleProperties.createRerunFailedTestsAction(consoleView)
        if (rerunAction != null && consoleView is SMTRunnerConsoleView) {
            rerunAction.setModelProvider { consoleView.resultsViewer }
            result.setRestartActions(rerunAction)
        }
        return result
    }
}

/**
 * Executes every suite file of [configuration] and streams console entries
 * plus service messages to [handler]. Runs on a pooled thread; returns the
 * CLI-mirrored exit code.
 */
internal fun runSuiteConfiguration(
    configuration: HexanaSuiteRunConfiguration,
    handler: HexanaScriptProcessHandler,
): Int {
    val suiteFiles = discoverSuiteFiles(configuration.suitePath)
    if (suiteFiles.isEmpty()) {
        handler.notifyTextAvailable("test: no suite files found.\n", ProcessOutputType.STDERR)
        return 2
    }

    val artifacts = parseArtifactEntries(configuration.artifactValues, configuration.freeFormArtifacts).toMap()
    val libRoot = configuration.libRoot.takeIf { it.isNotBlank() }
    val seed = configuration.seed.takeIf { it.isNotBlank() }
    val testTimeoutMillis = configuration.testTimeoutMillis.toLongOrNull()?.takeIf { it > 0 }
        ?: HexanaSuiteRunner.DEFAULT_TEST_TIMEOUT_MILLIS
    val testPathFilter = configuration.testPathFilter

    // allowAll: runs are user-initiated with user-picked files; the sandbox still blocks host
    // access, processes, threads, and writes. For suite runs, artifacts are host-mapped paths
    // and the module filesystem stays rooted, so no script-chosen path reaches the host.
    val runner = HexanaSuiteRunner(
        limits = HexanaScriptLimits.interactive(),
        allowedFilePolicy = AllowedFilePolicy.allowAll,
    )

    var anyFailure = false
    for (filePath in suiteFiles) {
        val fileName = File(filePath).name
        val result = runner.runSuite(
            suiteFilePath = filePath,
            artifacts = artifacts,
            libRoot = libRoot,
            seed = seed,
            testTimeoutMillis = testTimeoutMillis,
            testPathFilter = testPathFilter,
            onConsole = { entry ->
                val prefix = when (entry.severity) {
                    ConsoleSeverity.LOG -> "[log]"
                    ConsoleSeverity.ERROR -> "[error]"
                    ConsoleSeverity.DEBUG -> "[debug]"
                }
                handler.notifyTextAvailable("$prefix ${entry.text}\n", ProcessOutputType.STDERR)
            },
        )

        val messages: List<String>
        if (result.status != HexanaScriptStatus.SUCCESS) {
            // Collection error / artifact binding error / timeout before tests ran.
            anyFailure = true
            for (diagnostic in result.diagnostics) {
                handler.notifyTextAvailable(
                    "[error] ${diagnostic.code}: ${diagnostic.message}\n",
                    ProcessOutputType.STDERR,
                )
            }
            val message = result.diagnostics.firstOrNull()?.message ?: result.status.name
            messages = collectionErrorServiceMessages(fileName, message)
        } else {
            val tree = result.suite ?: continue
            if (tree.status == SuiteNodeStatus.FAIL) anyFailure = true
            messages = suiteTreeToServiceMessages(fileName, tree)
        }
        for (message in messages) {
            handler.notifyTextAvailable(message + "\n", ProcessOutputType.STDOUT)
        }
    }

    return if (anyFailure) 1 else 0
}
