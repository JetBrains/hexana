package org.jetbrains.hexana.run

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.shared.WasmCommandLine
import org.jetbrains.hexana.shared.WasmRunInput

enum class WasmRunMode {
    RUN, DEBUG
}

enum class PopupMode {
    RUN_AND_DEBUG, RUN_ONLY, DEBUG_ONLY
}

fun runWasmConfiguration(
    project: Project,
    file: VirtualFile,
    exportName: String,
    programArgs: String,
    wasmTimeArgs: String,
    engine: WasmEngine = WasmEngine.DEFAULT,
    mode: WasmRunMode = WasmRunMode.RUN,
) {
    val runManager = RunManager.getInstance(project)
    val configurationType = ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
    val factory = configurationType.configurationFactories.first { it is WasmConfigurationFactory }

    val configName = "${file.nameWithoutExtension}::${exportName}"

    val existing = runManager.allSettings.firstOrNull { settings ->
        settings.type == configurationType &&
                (settings.configuration as? WasmRunConfiguration)?.let { config ->
                    config.wasmFilePath == file.path &&
                            config.wasmtimeOptions == wasmTimeArgs &&
                            config.engine == engine
                } == true
    }

    val settings = existing ?: runManager.createConfiguration(configName, factory).also { newSettings ->
        (newSettings.configuration as WasmRunConfiguration).apply {
            wasmFilePath = file.path
            workingDirectory = file.parent?.path ?: ""
            wasmtimeOptions = wasmTimeArgs
            this.engine = engine
        }
        runManager.addConfiguration(newSettings)
    }

    (settings.configuration as? WasmRunConfiguration)?.apply {
        programArguments = programArgs
        this.exportName = exportName
        this.engine = engine
    }

    doRun(file, project, settings, runManager, mode)
}

fun runWasmFile(binaryProvider: BinaryProvider, state: HexViewStateHolder, engine: WasmEngine = WasmEngine.DEFAULT, mode: WasmRunMode = WasmRunMode.RUN) {
    val project = state.project
    val file = (binaryProvider as? BinaryVirtualFileProvider)?.file ?: return
    val runManager = RunManager.getInstance(project)
    val configurationType = ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
    val factory = configurationType.configurationFactories.first { it is WasmConfigurationFactory }

    // Reuse existing configuration for this file if one exists
    val existing = runManager.allSettings.firstOrNull { settings ->
        settings.type == configurationType &&
                (settings.configuration as? WasmRunConfiguration)?.let { config ->
                    config.wasmFilePath == file.path &&
                            config.engine == engine
                } == true
    }

    val settings = existing ?: runManager.createConfiguration(file.nameWithoutExtension, factory).also { newSettings ->
        (newSettings.configuration as WasmRunConfiguration).apply {
            wasmFilePath = file.path
            workingDirectory = file.parent?.path ?: ""
            this.engine = engine
        }
        runManager.addConfiguration(newSettings)
    }

    (settings.configuration as? WasmRunConfiguration)?.apply {
        this.engine = engine
    }

    doRun(file, project, settings, runManager, mode)
}


private fun executorForMode(mode: WasmRunMode): Executor = when (mode) {
    WasmRunMode.RUN -> DefaultRunExecutor.getRunExecutorInstance()
    WasmRunMode.DEBUG -> DefaultDebugExecutor.getDebugExecutorInstance()
}

private fun doRun(file: VirtualFile, project: Project, settings: RunnerAndConfigurationSettings, runManager: RunManager, mode: WasmRunMode = WasmRunMode.RUN) {
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, HexanaBundle.message("wasm.resolving.dependencies"), false) {
        private var resolved: Map<String, String> = emptyMap()

        override fun run(indicator: ProgressIndicator) {
            val wasmBytes = file.contentsToByteArray()
            resolved = WasmDependencyResolver.resolveDependencies(wasmBytes, project, excludeFile = file)
        }

        override fun onSuccess() {
            (settings.configuration as? WasmRunConfiguration)?.dependencies =
                resolved.map { (name, path) -> "$name=$path" }

            runManager.selectedConfiguration = settings
            ProgramRunnerUtil.executeConfiguration(settings, executorForMode(mode))
        }
    })
}

fun runCommandLine(runtimeExecutable: String, cmdLine: WasmCommandLine): OSProcessHandler {
    val commandLine = GeneralCommandLine().apply {
        exePath = runtimeExecutable
        addParameters(cmdLine.arguments)

        if (cmdLine.workingDirectory != null) {
            withWorkDirectory(cmdLine.workingDirectory)
        }

        withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
    }

    val handler = OSProcessHandler(commandLine)
    ProcessTerminatedListener.attach(handler)
    return handler
}

/**
 * Resolves dependencies from the configuration, falling back to
 * [WasmDependencyResolver] when the stored list is empty so that
 * debug runs triggered from the IDE toolbar still compose
 * component dependencies via WAC.
 */
fun resolveDeps(
    configuration: WasmRunConfiguration,
    wasmBytes: ByteArray?,
    project: Project,
): Map<String, String> {
    val stored = configuration.dependencies
        .mapNotNull { entry ->
            val parts = entry.split("=", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        .toMap()
    if (stored.isNotEmpty() || wasmBytes == null) return stored

    val vFile = LocalFileSystem.getInstance().findFileByPath(configuration.wasmFilePath)
    return WasmDependencyResolver.resolveDependencies(wasmBytes, project, excludeFile = vFile)
}