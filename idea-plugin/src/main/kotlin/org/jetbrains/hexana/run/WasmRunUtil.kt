package org.jetbrains.hexana.run

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider

fun runWasmConfiguration(
    project: Project,
    file: VirtualFile,
    exportName: String,
    programArgs: String,
    wasmTimeArgs: String
) {
    val runManager = RunManager.getInstance(project)
    val configurationType = ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
    val factory = configurationType.configurationFactories.first { it is WasmConfigurationFactory }

    val configName = "${file.nameWithoutExtension}::${exportName}"

    val existing = runManager.allSettings.firstOrNull { settings ->
        settings.type == configurationType &&
                (settings.configuration as? WasmRunConfiguration)?.let { config ->
                    config.wasmFilePath == file.path &&
                            config.wasmtimeOptions == wasmTimeArgs
                } == true
    }

    val settings = existing ?: runManager.createConfiguration(configName, factory).also { newSettings ->
        (newSettings.configuration as WasmRunConfiguration).apply {
            wasmFilePath = file.path
            workingDirectory = file.parent?.path ?: ""
            wasmtimeOptions = wasmTimeArgs
        }
        runManager.addConfiguration(newSettings)
    }

    (settings.configuration as? WasmRunConfiguration)?.apply {
        programArguments = programArgs
        this.exportName = exportName
    }

    doRun(file, project, settings, runManager)
}

fun runWasmFile(binaryProvider: BinaryProvider, state: HexViewStateHolder) {
    val project = state.project
    val file = (binaryProvider as? BinaryVirtualFileProvider)?.file ?: return
    val runManager = RunManager.getInstance(project)
    val configurationType = ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
    val factory = configurationType.configurationFactories.first { it is WasmConfigurationFactory }

    // Reuse existing configuration for this file if one exists
    val existing = runManager.allSettings.firstOrNull { settings ->
        settings.type == configurationType &&
                (settings.configuration as? WasmRunConfiguration)?.wasmFilePath == file.path
    }

    val settings = existing ?: runManager.createConfiguration(file.nameWithoutExtension, factory).also { newSettings ->
        (newSettings.configuration as WasmRunConfiguration).apply {
            wasmFilePath = file.path
            workingDirectory = file.parent?.path ?: ""
        }
        runManager.addConfiguration(newSettings)
    }

    doRun(file, project, settings, runManager)
}

private fun doRun(file: VirtualFile, project: Project, settings: RunnerAndConfigurationSettings, runManager: RunManager) {
    val wasmBytes = file.contentsToByteArray()
    val resolved = WasmDependencyResolver.resolveDependencies(wasmBytes, project, excludeFile = file)
    (settings.configuration as? WasmRunConfiguration)?.dependencies =
        resolved.map { (name, path) -> "$name=$path" }

    runManager.selectedConfiguration = settings
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}