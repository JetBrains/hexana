package org.jetbrains.hexana.run

import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebuggerManager

/**
 * Starts an XDebug session and returns the descriptor a
 * [com.intellij.execution.runners.GenericProgramRunner.doExecute] must return.
 *
 * On 2026.1+ (baseline 261) the `XDebugSessionBuilder` API is used:
 * `XDebugSession.getRunContentDescriptor` logs a "[Split debugger]" error
 * there, and `XSessionStartedResult.runContentDescriptor` is the designated
 * replacement. Older baselines (the plugin's floor is 252) predate the
 * builder API, so the legacy path is kept for them — the split-debugger
 * error does not exist on those versions. The paths live in separate
 * functions so the legacy IDE never touches the builder classes.
 */
internal fun startDebugSessionForRunner(
    environment: ExecutionEnvironment,
    starter: XDebugProcessStarter,
): RunContentDescriptor? =
    if (ApplicationInfo.getInstance().build.baselineVersion >= 261) {
        startWithSessionBuilder(environment, starter)
    } else {
        startWithLegacyApi(environment, starter)
    }

private fun startWithSessionBuilder(
    environment: ExecutionEnvironment,
    starter: XDebugProcessStarter,
): RunContentDescriptor? =
    XDebuggerManager.getInstance(environment.project)
        .newSessionBuilder(starter)
        .environment(environment)
        .startSession()
        .runContentDescriptor

@Suppress("DEPRECATION")
private fun startWithLegacyApi(
    environment: ExecutionEnvironment,
    starter: XDebugProcessStarter,
): RunContentDescriptor? =
    XDebuggerManager.getInstance(environment.project)
        .startSession(environment, starter)
        .runContentDescriptor

/**
 * Starts and shows an XDebug session that has no run configuration behind
 * it, such as one created by Run | Attach to Process. Uses the same
 * builder/legacy split as [startDebugSessionForRunner]. Must run on the EDT.
 */
internal fun startAttachedDebugSession(
    project: com.intellij.openapi.project.Project,
    sessionName: String,
    starter: XDebugProcessStarter,
) {
    if (ApplicationInfo.getInstance().build.baselineVersion >= 261) {
        startAttachedWithSessionBuilder(project, sessionName, starter)
    } else {
        startAttachedWithLegacyApi(project, sessionName, starter)
    }
}

private fun startAttachedWithSessionBuilder(
    project: com.intellij.openapi.project.Project,
    sessionName: String,
    starter: XDebugProcessStarter,
) {
    XDebuggerManager.getInstance(project)
        .newSessionBuilder(starter)
        .sessionName(sessionName)
        .startSession()
}

@Suppress("DEPRECATION")
private fun startAttachedWithLegacyApi(
    project: com.intellij.openapi.project.Project,
    sessionName: String,
    starter: XDebugProcessStarter,
) {
    XDebuggerManager.getInstance(project).startSessionAndShowTab(sessionName, null, starter)
}
