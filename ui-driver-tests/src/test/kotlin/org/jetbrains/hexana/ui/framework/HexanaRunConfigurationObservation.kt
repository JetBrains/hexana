package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.model.RdTarget
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.common.JEditorUiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.waitFor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val defaultRunConfigurationTerminationTimeout = 2.minutes
private val consoleOutputFlushTimeout = 10.seconds
private const val runConfigurationTerminationPollIntervalSeconds = 1
private const val HEXANA_PLUGIN_ID = "org.jetbrains.hexana"

internal data class HexanaRunConfigurationConsoleResult(
    val consoleOutput: String,
    val terminationState: String,
    val waitTimeout: Duration,
)

internal data class HexanaSelectedRunContent(
    val displayName: String?,
    val contentIdentity: String?,
    val processHandlerIdentity: String?,
    val processTerminated: Boolean?,
)

internal fun Driver.selectedRunConfigurationContent(): HexanaSelectedRunContent {
    val project = singleProject()
    val content = hexanaRunContentManager(project).getSelectedContent()
    val processHandler = content?.getProcessHandler()
    return HexanaSelectedRunContent(
        displayName = content?.getDisplayName(),
        contentIdentity = content?.toString(),
        processHandlerIdentity = processHandler?.toString(),
        processTerminated = processHandler?.isProcessTerminated(),
    )
}

internal fun Driver.awaitRunConfigurationConsoleResult(
    expectedConfigurationName: String,
    previousSelectedContent: HexanaSelectedRunContent?,
    timeout: Duration = defaultRunConfigurationTerminationTimeout,
): HexanaRunConfigurationConsoleResult {
    val terminationState = waitForRunConfigurationProcessTermination(
        timeout = timeout,
        expectedConfigurationName = expectedConfigurationName,
        previousSelectedContent = previousSelectedContent,
    )
    val consoleOutput = waitForRunConfigurationConsoleOutput()
    return HexanaRunConfigurationConsoleResult(
        consoleOutput = consoleOutput,
        terminationState = terminationState,
        waitTimeout = timeout,
    )
}

private fun Driver.waitForRunConfigurationProcessTermination(
    timeout: Duration,
    expectedConfigurationName: String,
    previousSelectedContent: HexanaSelectedRunContent?,
): String {
    val project = singleProject()
    val runContentManager = hexanaRunContentManager(project)
    var lastState = "no selected process content"

    try {
        waitFor(
            message = "Waiting for the selected run configuration process content to terminate",
            timeout = timeout,
            interval = runConfigurationTerminationPollIntervalSeconds.seconds,
        ) {
            val content = runContentManager.getSelectedContent()
            val processHandler = content?.getProcessHandler()
            val processTerminated = processHandler?.isProcessTerminated()
            val currentContent = HexanaSelectedRunContent(
                displayName = content?.getDisplayName(),
                contentIdentity = content?.toString(),
                processHandlerIdentity = processHandler?.toString(),
                processTerminated = processTerminated,
            )
            val isExpectedContent = content?.getDisplayName() == expectedConfigurationName
            val isPreviousContent = previousSelectedContent != null &&
                currentContent.contentIdentity == previousSelectedContent.contentIdentity &&
                currentContent.processHandlerIdentity == previousSelectedContent.processHandlerIdentity
            lastState = when {
                content == null -> "no selected process content"
                !isExpectedContent ->
                    "selected content is ${content.getDisplayName()}, expected $expectedConfigurationName"
                isPreviousContent -> "selected content is still the pre-launch content: $content"
                processHandler == null -> "selected content has no process handler: $content"
                processTerminated == true ->
                    "selected content terminated: $content"
                else -> "selected content is still running: $content"
            }
            isExpectedContent && !isPreviousContent && processTerminated == true
        }
    } catch (t: Throwable) {
        throw AssertionError(
            "Timed out after $timeout waiting for run configuration '$expectedConfigurationName' to terminate; " +
                "last state: $lastState",
            t,
        )
    }

    return lastState
}

private fun Driver.waitForRunConfigurationConsoleOutput(): String {
    var output = ""
    try {
        waitFor(
            message = "Waiting for run configuration console output to flush",
            timeout = consoleOutputFlushTimeout,
            interval = 1.seconds,
        ) {
            output = readRunConfigurationConsoleOutput()
            output.contains("Process finished with exit code")
        }
    } catch (_: Throwable) {
        return output.ifBlank { readRunConfigurationConsoleOutput() }
    }
    return output
}

private fun Driver.readRunConfigurationConsoleOutput(): String {
    try {
        var output = ""
        ideFrame {
            output = x { byClass("ConsoleViewImpl") }
                .x(JEditorUiComponent::class.java) { byAccessibleName("Editor") }
                .text
        }
        return output
    } catch (t: Throwable) {
        throw AssertionError(
            "Unable to read run configuration console output from the selected console view",
            t,
        )
    }
}

/**
 * Resolves `RunContentManager` through the Hexana plugin classloader.
 *
 * The driver-sdk `getRunContentManager` refs carry no plugin id, so the IDE-side invoker loads them through the
 * performanceTesting plugin classloader. Newer IDE builds no longer expose `intellij.platform.execution` there and
 * every lookup fails with "No such class 'com.intellij.execution.ui.RunContentManager' in plugin null"; the Hexana
 * plugin depends on the execution module, so its classloader always sees these classes.
 */
private fun Driver.hexanaRunContentManager(project: Project): HexanaRunContentManagerRef =
    service<HexanaRunContentManagerRef>(project, RdTarget.BACKEND)

@Remote("com.intellij.execution.ui.RunContentManager", plugin = HEXANA_PLUGIN_ID)
internal interface HexanaRunContentManagerRef {
    fun getSelectedContent(): HexanaRunContentDescriptorRef?
}

@Remote("com.intellij.execution.ui.RunContentDescriptor", plugin = HEXANA_PLUGIN_ID)
internal interface HexanaRunContentDescriptorRef {
    fun getDisplayName(): String
    fun getProcessHandler(): HexanaProcessHandlerRef?
}

@Remote("com.intellij.execution.process.ProcessHandler", plugin = HEXANA_PLUGIN_ID)
internal interface HexanaProcessHandlerRef {
    fun isProcessTerminated(): Boolean
}
