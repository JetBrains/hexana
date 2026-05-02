package org.jetbrains.hexana.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

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
}
