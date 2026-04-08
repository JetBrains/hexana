package org.jetbrains.hexana.run

import com.intellij.execution.configurations.RunConfigurationOptions

class WasmRunConfigurationOptions : RunConfigurationOptions() {
    private val engine = string(WasmEngine.DEFAULT.name).provideDelegate(this, "engine")
    private val wasmFilePath = string("").provideDelegate(this, "wasmFilePath")
    private val programArguments = string("").provideDelegate(this, "programArguments")
    private val workingDirectory = string("").provideDelegate(this, "workingDirectory")
    private val wasmtimePath = string("").provideDelegate(this, "wasmtimePath")
    private val wasmtimeOptions = string("").provideDelegate(this, "wasmtimeOptions")

    fun getEngine(): WasmEngine = WasmEngine.fromString(engine.getValue(this))
    fun setEngine(value: WasmEngine) { engine.setValue(this, value.name) }

    fun getWasmFilePath(): String = wasmFilePath.getValue(this) ?: ""
    fun setWasmFilePath(value: String) { wasmFilePath.setValue(this, value) }

    fun getProgramArguments(): String = programArguments.getValue(this) ?: ""
    fun setProgramArguments(value: String) { programArguments.setValue(this, value) }

    fun getWorkingDirectory(): String = workingDirectory.getValue(this) ?: ""
    fun setWorkingDirectory(value: String) { workingDirectory.setValue(this, value) }

    fun getWasmtimePath(): String = wasmtimePath.getValue(this) ?: ""
    fun setWasmtimePath(value: String) { wasmtimePath.setValue(this, value) }

    fun getWasmtimeOptions(): String = wasmtimeOptions.getValue(this) ?: ""
    fun setWasmtimeOptions(value: String) { wasmtimeOptions.setValue(this, value) }
}
