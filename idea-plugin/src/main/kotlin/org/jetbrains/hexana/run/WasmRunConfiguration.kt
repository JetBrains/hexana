package org.jetbrains.hexana.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import java.io.File

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

    var wamrPath: String
        get() = options.getWamrPath()
        set(value) { options.setWamrPath(value) }

    var wamrOptions: String
        get() = options.getWamrOptions()
        set(value) { options.setWamrOptions(value) }

    var graalVmPath: String
        get() = options.getGraalVmPath()
        set(value) { options.setGraalVmPath(value) }

    var lldbPath: String
        get() = options.getLldbPath()
        set(value) { options.setLldbPath(value) }

    var exportName: String
        get() = options.getExportName()
        set(value) { options.setExportName(value) }

    var dependencies: List<String>
        get() = options.getDependencies()
        set(value) { options.setDependencies(value) }

    override fun checkConfiguration() {
        val path = wasmFilePath
        if (path.isBlank()) {
            throw RuntimeConfigurationError("Wasm file path is not specified")
        }
        val file = File(path)
        if (!file.exists()) {
            throw RuntimeConfigurationError("Wasm file not found: $path")
        }
        if (engine == WasmEngine.GRAALWASM || engine == WasmEngine.WAMR || engine == WasmEngine.BROWSER) {
            val binaryType = WasmBinaryProviderSelector.select(file.readBytes().toCommonByteBuffer())
            if (binaryType is BinaryProviderType.WasmComponent) {
                throw RuntimeConfigurationError(
                    "${engine.displayName} does not support the WebAssembly Component Model. " +
                            "Please use the Wasmtime engine to run component modules."
                )
            }
        }
    }

    override fun getConfigurationEditor(): SettingsEditor<WasmRunConfiguration> =
        WasmRunSettingsEditor(project)

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return when (engine) {
            WasmEngine.WASMTIME -> WasmtimeCommandLineState(this, environment)
            WasmEngine.WAMR -> WamrCommandLineState(this, environment)
            WasmEngine.GRAALWASM -> GraalWasmCommandLineState(this, environment)
            WasmEngine.BROWSER -> BrowserCommandLineState(this, environment)
        }
    }
}
