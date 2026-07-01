package org.jetbrains.hexana.run

import com.intellij.execution.Platform
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.frame.*
import com.intellij.xdebugger.frame.presentation.XValuePresentation

object WasmRunConfigTestHelper {

    /**
     * Sets a breakpoint in [sourceFileName] at [breakpointLine], launches
     * a debug session for [wasmFileName], waits for the breakpoint to be
     * hit, collects local variable names and values from the top stack
     * frame, resumes execution, and returns the result as a string.
     *
     * The returned string has the format:
     * ```
     * PAUSED
     * name1=value1
     * name2=value2
     * RESUMED
     * ```
     *
     * If the breakpoint is never hit within the timeout, the string
     * starts with `TIMEOUT` instead.
     */
    @JvmStatic
    fun debugWithBreakpointAndInspectVariables(
        project: Project,
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        engine: String,
        wasmtimeOptions: String,
        exportName: String,
        programArgs: String,
        wasmtimePath: String = "",
    ): String {
        removeAllBreakpoints(project)

        val basePath = project.basePath ?: error("project basePath is null")

        // Set breakpoint on the source file
        val sourceFilePath = "$basePath/$sourceFileName"
        val sourceVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(sourceFilePath)
            ?: error("source file not found: $sourceFilePath")

        val breakpointType = XDebuggerUtil.getInstance()
            .findBreakpointType(WasmLineBreakpointType::class.java)
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                val alreadyExists = bpManager.allBreakpoints.any { bp ->
                    bp.type == breakpointType &&
                        bp.sourcePosition?.file?.url == sourceVirtualFile.url &&
                        bp.sourcePosition?.line == breakpointLine - 1
                }
                if (!alreadyExists) {
                    bpManager.addLineBreakpoint(
                        breakpointType,
                        sourceVirtualFile.url,
                        breakpointLine - 1, // 0-based
                        null,
                    )
                }
            }
        }

        // Launch debug session
        val wasmFilePath = "$basePath/$wasmFileName"
        val wasmVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(wasmFilePath)
            ?: error("wasm file not found: $wasmFilePath")

        val runManager = RunManager.getInstance(project)
        val configurationType = ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
        val factory = configurationType.configurationFactories.first { it is WasmConfigurationFactory }

        val configName = "test-debug-bp-${wasmVirtualFile.nameWithoutExtension}"
        val settings = runManager.createConfiguration(configName, factory)
        val selectedEngine = WasmEngine.fromString(engine)

        (settings.configuration as WasmRunConfiguration).apply {
            this.wasmFilePath = wasmVirtualFile.path
            this.workingDirectory = wasmVirtualFile.parent?.path ?: ""
            this.engine = selectedEngine
            this.wasmtimeOptions = wasmtimeOptions
            this.exportName = exportName
            this.programArguments = programArgs
            if (wasmtimePath.isNotBlank()) this.wasmtimePath = wasmtimePath
        }

        val wasmBytes = wasmVirtualFile.contentsToByteArray()
        val resolved = WasmDependencyResolver.resolveDependencies(wasmBytes, project, excludeFile = wasmVirtualFile)
        (settings.configuration as WasmRunConfiguration).dependencies =
            resolved.map { (name, path) -> "$name=$path" }

        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(settings, executor)
        }

        // Wait for the debug session to pause at the breakpoint
        val result = StringBuilder()
        val deadlineMs = System.currentTimeMillis() + (
             if (SystemInfo.isWindows) 200_000 else 60_000
        )
        var paused = false

        while (System.currentTimeMillis() < deadlineMs) {
            val session = XDebuggerManager.getInstance(project).currentSession
            if (session != null && session.isSuspended) {
                paused = true
                break
            }
            Thread.sleep(500)
        }

        if (!paused) {
            return "TIMEOUT"
        }

        result.appendLine("PAUSED")

        // The IDE's own Variables pane calls computeChildren when the
        // session suspends. That call sends LLDB commands (frame select,
        // __vmctx->set(), frame variable) through the shared
        // LldbCommunicator. We must NOT call computeChildren ourselves
        // because the communicator is not thread-safe for concurrent
        // sendCommandAndCollectOutput calls — the second caller
        // overwrites the first caller's response collector, corrupting
        // both results.
        //
        // Instead, we wait for the IDE's computeChildren to finish
        // (which initializes __vmctx and resolves variables), then
        // send a single `frame variable` command to read the values.
        val session = XDebuggerManager.getInstance(project).currentSession!!

        // Wait for the IDE's own Variables pane computeChildren to
        // finish. It runs on a pooled thread and sends multiple LLDB
        // commands sequentially. 10 seconds is generous enough.
        Thread.sleep(if (SystemInfo.isWindows) 30_000 else 10_000)

        var collectedVariables = collectVariablesViaFrameVariable(session)

        // Retry if variables still look unresolved
        val userVars = collectedVariables.filter {
            it.first != "__vmctx" && it.first != "memory"
        }
        val looksResolved = userVars.isNotEmpty() && userVars.any { (_, value) ->
            value.isNotBlank() && value != "0" && !value.contains("collecting", ignoreCase = true)
                && !value.contains("unavailable", ignoreCase = true)
        }
        if (!looksResolved) {
            Thread.sleep(5_000)
            collectedVariables = collectVariablesViaFrameVariable(session)
        }

        for ((name, value) in collectedVariables) {
            result.appendLine("$name=$value")
        }

        // Resume execution
        ApplicationManager.getApplication().invokeLater {
            session.resume()
        }

        result.appendLine("RESUMED")
        return result.toString().trim()
    }

    @JvmStatic
    fun createAndDebugNodeJs(
        project: Project,
        wasmFileName: String,
        engine: String,
        nodeOptions: String,
        exportName: String,
        programArgs: String,
        nodePath: String = "",
    ) {
        removeAllBreakpoints(project)

        val basePath = project.basePath ?: error("project basePath is null")
        val wasmFilePath = "$basePath/$wasmFileName"
        val wasmVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(wasmFilePath)
            ?: error("wasm file not found: $wasmFilePath")

        val runManager = RunManager.getInstance(project)
        val configurationType = ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
        val factory = configurationType.configurationFactories.first { it is WasmConfigurationFactory }

        val configName = "test-debug-${wasmVirtualFile.nameWithoutExtension}"
        val settings = runManager.createConfiguration(configName, factory)
        val selectedEngine = WasmEngine.fromString(engine)

        (settings.configuration as WasmRunConfiguration).apply {
            this.wasmFilePath = wasmVirtualFile.path
            this.workingDirectory = wasmVirtualFile.parent?.path ?: ""
            this.engine = selectedEngine
            this.nodeOptions = nodeOptions
            this.exportName = exportName
            this.programArguments = programArgs
            if (nodePath.isNotBlank()) this.nodePath = nodePath
        }

        val wasmBytes = wasmVirtualFile.contentsToByteArray()
        val resolved = WasmDependencyResolver.resolveDependencies(wasmBytes, project, excludeFile = wasmVirtualFile)
        (settings.configuration as WasmRunConfiguration).dependencies =
            resolved.map { (name, path) -> "$name=$path" }

        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(settings, executor)
        }
    }

    @JvmStatic
    fun debugNodeJsWithBreakpointAndInspectVariables(
        project: Project,
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        engine: String,
        nodeOptions: String,
        exportName: String,
        programArgs: String,
        nodePath: String = "",
    ): String {
        removeAllBreakpoints(project)

        val basePath = project.basePath ?: error("project basePath is null")

        // Set breakpoint on the source file
        val sourceFilePath = "$basePath/$sourceFileName"
        val sourceVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(sourceFilePath)
            ?: error("source file not found: $sourceFilePath")

        val breakpointType = XDebuggerUtil.getInstance()
            .findBreakpointType(WasmLineBreakpointType::class.java)
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                val alreadyExists = bpManager.allBreakpoints.any { bp ->
                    bp.type == breakpointType &&
                        bp.sourcePosition?.file?.url == sourceVirtualFile.url &&
                        bp.sourcePosition?.line == breakpointLine - 1
                }
                if (!alreadyExists) {
                    bpManager.addLineBreakpoint(
                        breakpointType,
                        sourceVirtualFile.url,
                        breakpointLine - 1, // 0-based
                        null,
                    )
                }
            }
        }

        // Launch debug session
        val wasmFilePath = "$basePath/$wasmFileName"
        val wasmVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(wasmFilePath)
            ?: error("wasm file not found: $wasmFilePath")

        val runManager = RunManager.getInstance(project)
        val configurationType = ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
        val factory = configurationType.configurationFactories.first { it is WasmConfigurationFactory }

        val configName = "test-debug-bp-${wasmVirtualFile.nameWithoutExtension}"
        val settings = runManager.createConfiguration(configName, factory)
        val selectedEngine = WasmEngine.fromString(engine)

        (settings.configuration as WasmRunConfiguration).apply {
            this.wasmFilePath = wasmVirtualFile.path
            this.workingDirectory = wasmVirtualFile.parent?.path ?: ""
            this.engine = selectedEngine
            this.nodeOptions = nodeOptions
            this.exportName = exportName
            this.programArguments = programArgs
            if (nodePath.isNotBlank()) this.nodePath = nodePath
        }

        val wasmBytes = wasmVirtualFile.contentsToByteArray()
        val resolved = WasmDependencyResolver.resolveDependencies(wasmBytes, project, excludeFile = wasmVirtualFile)
        (settings.configuration as WasmRunConfiguration).dependencies =
            resolved.map { (name, path) -> "$name=$path" }

        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(settings, executor)
        }

        // Wait for the debug session to pause at the breakpoint
        val result = StringBuilder()
        val deadlineMs = System.currentTimeMillis() + (
             if (SystemInfo.isWindows) 200_000 else 60_000
        )
        var paused = false

        while (System.currentTimeMillis() < deadlineMs) {
            val session = XDebuggerManager.getInstance(project).currentSession
            if (session != null && session.isSuspended) {
                paused = true
                break
            }
            Thread.sleep(500)
        }

        if (!paused) {
            return "TIMEOUT"
        }

        result.appendLine("PAUSED")

        // Wait for the IDE's own Variables pane computeChildren to finish.
        Thread.sleep(if (SystemInfo.isWindows) 30_000 else 10_000)

        val session = XDebuggerManager.getInstance(project).currentSession!!
        val collectedVariables = collectVariablesViaXDebugger(session)

        for ((name, value) in collectedVariables) {
            result.appendLine("$name=$value")
        }

        // Resume execution
        ApplicationManager.getApplication().invokeLater {
            session.resume()
        }

        result.appendLine("RESUMED")
        return result.toString().trim()
    }

    @JvmStatic
    fun createAndDebugWasmtime(
        project: Project,
        wasmFileName: String,
        engine: String,
        wasmtimeOptions: String,
        exportName: String,
        programArgs: String,
        wasmtimePath: String = "",
    ) {
        removeAllBreakpoints(project)

        val basePath = project.basePath ?: error("project basePath is null")
        val wasmFilePath = "$basePath/$wasmFileName"
        val wasmVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(wasmFilePath)
            ?: error("wasm file not found: $wasmFilePath")

        val runManager = RunManager.getInstance(project)
        val configurationType = ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
        val factory = configurationType.configurationFactories.first { it is WasmConfigurationFactory }

        val configName = "test-debug-${wasmVirtualFile.nameWithoutExtension}"
        val settings = runManager.createConfiguration(configName, factory)
        val selectedEngine = WasmEngine.fromString(engine)

        (settings.configuration as WasmRunConfiguration).apply {
            this.wasmFilePath = wasmVirtualFile.path
            this.workingDirectory = wasmVirtualFile.parent?.path ?: ""
            this.engine = selectedEngine
            this.wasmtimeOptions = wasmtimeOptions
            this.exportName = exportName
            this.programArguments = programArgs
            if (wasmtimePath.isNotBlank()) this.wasmtimePath = wasmtimePath
        }

        val wasmBytes = wasmVirtualFile.contentsToByteArray()
        val resolved = WasmDependencyResolver.resolveDependencies(wasmBytes, project, excludeFile = wasmVirtualFile)
        (settings.configuration as WasmRunConfiguration).dependencies =
            resolved.map { (name, path) -> "$name=$path" }

        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        val executor = DefaultDebugExecutor.getDebugExecutorInstance()
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(settings, executor)
        }
    }

    private fun removeAllBreakpoints(project: Project) {
        ApplicationManager.getApplication().invokeAndWait {
            ApplicationManager.getApplication().runWriteAction {
                val bpManager = XDebuggerManager.getInstance(project).breakpointManager
                bpManager.allBreakpoints.forEach { bpManager.removeBreakpoint(it) }
            }
        }
    }

    /**
     * Collects variables by sending a single `frame variable` command
     * through the [LldbCommunicator]. This avoids calling
     * [com.intellij.xdebugger.frame.XStackFrame.computeChildren]
     * which would send multiple LLDB commands that can interleave
     * with the IDE's own Variables pane (the communicator is not
     * thread-safe for concurrent `sendCommandAndCollectOutput` calls).
     *
     * The caller must ensure that `__vmctx->set()` has already been
     * called (e.g. by waiting for the IDE's own `computeChildren`
     * to finish first).
     */

    /**
     * Collects variables from the current stack frame using the
     * XDebugger framework's [XStackFrame.computeChildren] API.
     * This works for any debug backend (CDP, LLDB, etc.) as it
     * goes through the standard IDE variable resolution path.
     */
    private fun collectVariablesViaXDebugger(
        session: com.intellij.xdebugger.XDebugSession,
    ): List<Pair<String, String>> {
        val frame = session.currentStackFrame ?: return emptyList()
        val variables = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val latch = java.util.concurrent.CountDownLatch(1)

        frame.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                for (i in 0 until children.size()) {
                    val name = children.getName(i)
                    val xValue = children.getValue(i)
                    val valueLatch = java.util.concurrent.CountDownLatch(1)
                    val valueHolder = arrayOfNulls<String>(1)

                    xValue.computePresentation(object : XValueNode {
                        override fun setPresentation(
                            icon: javax.swing.Icon?,
                            type: String?,
                            value: String,
                            hasChildren: Boolean,
                        ) {
                            valueHolder[0] = value
                            valueLatch.countDown()
                        }

                        override fun setPresentation(
                            icon: javax.swing.Icon?,
                            presentation: XValuePresentation,
                            hasChildren: Boolean,
                        ) {
                            valueHolder[0] = presentation.toString()
                            valueLatch.countDown()
                        }

                        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
                    }, XValuePlace.TREE)

                    valueLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    variables.add(name to (valueHolder[0] ?: ""))
                }
                if (last) latch.countDown()
            }

            override fun tooManyChildren(remaining: Int) { latch.countDown() }
            override fun tooManyChildren(remaining: Int, childrenCallback: Runnable) { latch.countDown() }
            override fun setAlreadySorted(value: Boolean) {}
            override fun setErrorMessage(errorMessage: String) { latch.countDown() }
            override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { latch.countDown() }
            override fun setMessage(message: String, icon: javax.swing.Icon?, attributes: com.intellij.ui.SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) { latch.countDown() }
            override fun isObsolete(): Boolean = false
        })

        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        return variables
    }

    private fun collectVariablesViaFrameVariable(
        session: com.intellij.xdebugger.XDebugSession,
    ): List<Pair<String, String>> {
        val debugProcess = session.debugProcess
        if (debugProcess !is WasmDebugProcess) return emptyList()
        val lldb = debugProcess.getLldb() ?: return emptyList()

        val lines = lldb.sendCommandAndCollectOutput("frame variable")
        val variables = WasmLocalVariable.parseFrameVariableOutput(
            lines, filterHidden = false,
        )

        return variables.map { it.name to it.presentationValue }
    }
}
