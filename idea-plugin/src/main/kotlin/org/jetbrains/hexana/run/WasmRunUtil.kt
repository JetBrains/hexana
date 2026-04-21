package org.jetbrains.hexana.run

import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.awt.RelativePoint
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import java.awt.MouseInfo

enum class WasmRunMode {
    RUN, DEBUG
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

data class EngineRunOption(val engine: WasmEngine, val mode: WasmRunMode) {
    override fun toString(): String = when (mode) {
        WasmRunMode.RUN -> HexanaBundle.message("wasm.run.with", engine.displayName)
        WasmRunMode.DEBUG -> HexanaBundle.message("wasm.debug.with", engine.displayName)
    }
}

fun showEnginePopup(isComponentModel: Boolean = false, onSelected: (WasmEngine, WasmRunMode) -> Unit) {
    val engines = WasmEngine.entries.filter { !isComponentModel || it.supportsComponentsModel }
    val options = engines.flatMap { engine ->
        buildList {
            add(EngineRunOption(engine, WasmRunMode.RUN))
            if (engine.supportsDebug) {
                add(EngineRunOption(engine, WasmRunMode.DEBUG))
            }
        }
    }
    if (options.size == 1) {
        val single = options.single()
        onSelected(single.engine, single.mode)
        return
    }
    val popup = JBPopupFactory.getInstance()
        .createPopupChooserBuilder(options)
        .setTitle(HexanaBundle.message("wasm.engine.popup.title"))
        .setRenderer { _, value, _, isSelected, _ ->
            val icon = when (value?.mode) {
                WasmRunMode.DEBUG -> AllIcons.Actions.StartDebugger
                else -> AllIcons.Actions.Execute
            }
            val label = value?.toString() ?: ""
            javax.swing.JLabel(label, icon, javax.swing.SwingConstants.LEFT).apply {
                isOpaque = true
                iconTextGap = 6
                border = javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8)
                if (isSelected) {
                    background = javax.swing.UIManager.getColor("List.selectionBackground")
                    foreground = javax.swing.UIManager.getColor("List.selectionForeground")
                }
            }
        }
        .setItemChosenCallback { onSelected(it.engine, it.mode) }
        .createPopup()
    val pointerInfo = MouseInfo.getPointerInfo()
    if (pointerInfo != null) {
        popup.show(RelativePoint(pointerInfo.location))
    } else {
        popup.showInFocusCenter()
    }
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