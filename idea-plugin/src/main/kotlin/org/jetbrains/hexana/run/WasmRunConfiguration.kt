package org.jetbrains.hexana.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project

class WasmRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<WasmRunConfigurationOptions>(project, factory, name) {

    override fun getOptions(): WasmRunConfigurationOptions =
        super.getOptions() as WasmRunConfigurationOptions

    var engine: WasmEngine
        get() = options.getEngine()
        set(value) { options.setEngine(value) }

    var wasmFilePath: String
        get() = options.getWasmFilePath()
        set(value) { options.setWasmFilePath(value) }

    var programArguments: String
        get() = options.getProgramArguments()
        set(value) { options.setProgramArguments(value) }

    var workingDirectory: String
        get() = options.getWorkingDirectory()
        set(value) { options.setWorkingDirectory(value) }

    var wasmtimePath: String
        get() = options.getWasmtimePath()
        set(value) { options.setWasmtimePath(value) }

    var wasmtimeOptions: String
        get() = options.getWasmtimeOptions()
        set(value) { options.setWasmtimeOptions(value) }

    var exportName: String
        get() = options.getExportName()
        set(value) { options.setExportName(value) }

    var dependencies: List<String>
        get() = options.getDependencies()
        set(value) { options.setDependencies(value) }

    override fun getConfigurationEditor(): SettingsEditor<WasmRunConfiguration> =
        WasmRunSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return when (engine) {
            WasmEngine.WASMTIME -> WasmtimeCommandLineState(this, environment)
            WasmEngine.GRAALWASM -> GraalWasmCommandLineState(this, environment)
        }
    }
}
