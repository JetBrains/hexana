package org.jetbrains.hexana.firmware.ui

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.firmware.FirmwareTarget
import org.jetbrains.hexana.firmware.run.FirmwareRunConfiguration
import org.jetbrains.hexana.firmware.run.FirmwareRunConfigurationType
import org.jetbrains.hexana.run.WasmRunMode

/**
 * Launches ELF firmware in an emulator via a [FirmwareRunConfiguration].
 *
 * Mirrors [org.jetbrains.hexana.run.runWasmFile]: finds or creates a run configuration
 * for [file], sets the ELF/DTS/config paths from [target]'s sidecars, and executes it
 * under the appropriate executor for [mode].
 *
 * Must be called on the EDT (RunManager mutations require EDT).
 */
internal fun runFirmwareElf(
    project: Project,
    file: VirtualFile,
    target: FirmwareTarget,
    mode: WasmRunMode,
) {
    val runManager = RunManager.getInstance(project)
    val configurationType = ConfigurationTypeUtil.findConfigurationType(FirmwareRunConfigurationType::class.java)
    val factory = configurationType.configurationFactories.first()

    val existing = runManager.allSettings.firstOrNull { settings ->
        settings.type == configurationType &&
            (settings.configuration as? FirmwareRunConfiguration)?.elfPath == file.path
    }

    val settings = existing ?: runManager.createConfiguration(file.nameWithoutExtension, factory).also { newSettings ->
        (newSettings.configuration as FirmwareRunConfiguration).apply {
            elfPath = file.path
            target.sidecars.dtsFile?.path?.let { dtsPath = it }
            target.sidecars.configFile?.path?.let { configPath = it }
        }
        runManager.addConfiguration(newSettings)
    }

    // Refresh sidecars on the existing config in case discovery improved since creation.
    (settings.configuration as? FirmwareRunConfiguration)?.apply {
        if (dtsPath.isBlank()) {
            target.sidecars.dtsFile?.path?.let { dtsPath = it }
        }
        if (configPath.isBlank()) {
            target.sidecars.configFile?.path?.let { configPath = it }
        }
    }

    runManager.selectedConfiguration = settings
    val executor = when (mode) {
        WasmRunMode.RUN -> DefaultRunExecutor.getRunExecutorInstance()
        WasmRunMode.DEBUG -> DefaultDebugExecutor.getDebugExecutorInstance()
    }
    ProgramRunnerUtil.executeConfiguration(settings, executor)
}

/**
 * Entry point callable from Compose onClick (any thread).
 *
 * Marshals [runFirmwareElf] to the EDT via [invokeLater], matching the pattern used
 * by [org.jetbrains.hexana.run.WasmRunUtil]'s doRun helper.
 */
internal fun runFirmwareElfLater(
    project: Project,
    file: VirtualFile,
    target: FirmwareTarget,
    mode: WasmRunMode,
) {
    invokeLater { runFirmwareElf(project, file, target, mode) }
}
