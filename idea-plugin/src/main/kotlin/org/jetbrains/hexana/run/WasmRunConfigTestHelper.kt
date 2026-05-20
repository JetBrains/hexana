package org.jetbrains.hexana.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil

/**
 * Utility called from UI driver tests via `@Remote` JMX bridge.
 *
 * Creates a Wasmtime run configuration for the given wasm file,
 * resolves dependencies, and launches it. Output is read from the
 * Run tool window by the UI driver test.
 */
object WasmRunConfigTestHelper {

    @JvmStatic
    fun createAndRunWasmtime(
        project: Project,
        wasmFileName: String,
        engine: String,
        wasmtimeOptions: String,
        exportName: String,
        programArgs: String,
    ) {
        val basePath = project.basePath ?: error("project basePath is null")
        val wasmFilePath = "$basePath/$wasmFileName"
        val wasmVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(wasmFilePath)
            ?: error("wasm file not found: $wasmFilePath")

        val runManager = RunManager.getInstance(project)
        val configurationType = ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
        val factory = configurationType.configurationFactories.first { it is WasmConfigurationFactory }

        val configName = "test-${wasmVirtualFile.nameWithoutExtension}"
        val settings = runManager.createConfiguration(configName, factory)
        val selectedEngine = WasmEngine.fromString(engine)

        (settings.configuration as WasmRunConfiguration).apply {
            this.wasmFilePath = wasmVirtualFile.path
            this.workingDirectory = wasmVirtualFile.parent?.path ?: ""
            this.engine = selectedEngine
            this.wasmtimeOptions = wasmtimeOptions
            this.exportName = exportName
            this.programArguments = programArgs
        }

        // Resolve dependencies (mirrors WasmRunUtil.doRun)
        val wasmBytes = wasmVirtualFile.contentsToByteArray()
        val resolved = WasmDependencyResolver.resolveDependencies(wasmBytes, project, excludeFile = wasmVirtualFile)
        (settings.configuration as WasmRunConfiguration).dependencies =
            resolved.map { (name, path) -> "$name=$path" }

        runManager.addConfiguration(settings)
        runManager.selectedConfiguration = settings

        val executor = DefaultRunExecutor.getRunExecutorInstance()
        ApplicationManager.getApplication().invokeLater {
            ProgramRunnerUtil.executeConfiguration(settings, executor)
        }
    }

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
    ): String {
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
        val deadlineMs = System.currentTimeMillis() + 60_000
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

        // Collect variables by accessing the LLDB communicator directly
        // from the WasmDebugProcess, which avoids implementing complex
        // XCompositeNode/XValueNode interfaces.
        val session = XDebuggerManager.getInstance(project).currentSession!!
        val debugProcess = session.debugProcess
        if (debugProcess is WasmDebugProcess) {
            val lldb = debugProcess.getLldb()
            if (lldb != null) {
                // Initialize VM context for Wasmtime variable access
                try {
                    lldb.sendCommandAndCollectOutput("expression -- __vmctx->set()", timeoutMs = 2_000)
                } catch (_: Exception) {
                    // May fail in non-Wasm frames
                }

                val lines = lldb.sendCommandAndCollectOutput("frame variable")
                // Parse `(type) name = value` lines directly to avoid
                // accessing private fields on WasmLocalVariable.
                val pattern = """\((.+?)\)\s+(.+?)\s+=\s+(.+)""".toRegex(RegexOption.DOT_MATCHES_ALL)
                for (line in lines) {
                    val match = pattern.find(line.trim()) ?: continue
                    val name = match.groupValues[2].trim()
                    val value = match.groupValues[3].trim()
                    result.appendLine("$name=$value")
                }
            }
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
    ) {
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
}
