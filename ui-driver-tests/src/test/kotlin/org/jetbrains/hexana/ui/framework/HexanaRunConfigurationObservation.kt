package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.getRunContentManager
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.UiText.Companion.asString
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val defaultRunConfigurationTerminationTimeout = 2.minutes
private const val runConfigurationTerminationPollIntervalSeconds = 1

internal data class HexanaRunConfigurationConsoleResult(
    val consoleOutput: String,
    val terminationState: String,
    val waitTimeout: Duration,
)

internal fun Driver.awaitRunConfigurationConsoleResult(
    timeout: Duration = defaultRunConfigurationTerminationTimeout,
): HexanaRunConfigurationConsoleResult {
    val terminationState = waitForRunConfigurationProcessTermination(timeout)
    return HexanaRunConfigurationConsoleResult(
        consoleOutput = readRunConfigurationConsoleOutput(),
        terminationState = terminationState,
        waitTimeout = timeout,
    )
}

private fun Driver.waitForRunConfigurationProcessTermination(timeout: Duration): String {
    val project = singleProject()
    val runContentManager = getRunContentManager(project)
    var lastState = "no selected process content"

    try {
        waitFor(
            message = "Waiting for the selected run configuration process content to terminate",
            timeout = timeout,
            interval = runConfigurationTerminationPollIntervalSeconds.seconds,
        ) {
            val content = runContentManager.getSelectedContent()
            lastState = when {
                content == null -> "no selected process content"
                content.getProcessHandler() == null -> "selected content has no process handler: $content"
                content.getProcessHandler()?.isProcessTerminated() == true ->
                    "selected content terminated: $content"
                else -> "selected content is still running: $content"
            }
            content?.getProcessHandler()?.isProcessTerminated() == true
        }
    } catch (t: Throwable) {
        throw AssertionError(
            "Timed out after $timeout waiting for the selected run configuration process content to terminate; " +
                "last state: $lastState",
            t,
        )
    }

    return lastState
}

private fun Driver.readRunConfigurationConsoleOutput(): String {
    try {
        var output = ""
        ideFrame {
            output = x { byClass("ConsoleViewImpl") }
                .x { byAccessibleName("Editor") }
                .getAllTexts().asString()
        }
        return output
    } catch (t: Throwable) {
        throw AssertionError(
            "Unable to read run configuration console output from the selected console view",
            t,
        )
    }
}
