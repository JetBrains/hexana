package org.jetbrains.hexana.run

import com.intellij.execution.configurations.RunConfigurationOptions

class WasmRunConfigurationOptions : RunConfigurationOptions() {
    private val engine = string(WasmEngine.DEFAULT.name).provideDelegate(this, "engine")
    private val wasmFilePath = string("").provideDelegate(this, "wasmFilePath")
    private val programArguments = string("").provideDelegate(this, "programArguments")
    private val workingDirectory = string("").provideDelegate(this, "workingDirectory")
    private val wasmtimePath = string("").provideDelegate(this, "wasmtimePath")
    private val wasmtimeOptions = string("").provideDelegate(this, "wasmtimeOptions")
    private val wamrPath = string("").provideDelegate(this, "wamrPath")
    private val wamrOptions = string("").provideDelegate(this, "wamrOptions")
    private val graalVmPath = string("").provideDelegate(this, "graalVmPath")
    private val nodePath = string("").provideDelegate(this, "nodePath")
    private val nodeOptions = string("").provideDelegate(this, "nodeOptions")
    private val lldbPath = string("").provideDelegate(this, "lldbPath")
    private val chromePath = string("").provideDelegate(this, "chromePath")
    private val exportName = string("").provideDelegate(this, "exportName")
    private val dependencies = list<String>().provideDelegate(this, "dependencies")

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

    fun getWamrPath(): String = wamrPath.getValue(this) ?: ""
    fun setWamrPath(value: String) { wamrPath.setValue(this, value) }

    fun getWamrOptions(): String = wamrOptions.getValue(this) ?: ""
    fun setWamrOptions(value: String) { wamrOptions.setValue(this, value) }

    fun getGraalVmPath(): String = graalVmPath.getValue(this) ?: ""
    fun setGraalVmPath(value: String) { graalVmPath.setValue(this, value) }

    fun getNodePath(): String = nodePath.getValue(this) ?: ""
    fun setNodePath(value: String) { nodePath.setValue(this, value) }

    fun getNodeOptions(): String = nodeOptions.getValue(this) ?: ""
    fun setNodeOptions(value: String) { nodeOptions.setValue(this, value) }

    fun getLldbPath(): String = lldbPath.getValue(this) ?: ""
    fun setLldbPath(value: String) { lldbPath.setValue(this, value) }

    fun getChromePath(): String = chromePath.getValue(this) ?: ""
    fun setChromePath(value: String) { chromePath.setValue(this, value) }

    fun getExportName(): String = exportName.getValue(this) ?: ""
    fun setExportName(value: String) { exportName.setValue(this, value) }

    fun getDependencies(): List<String> = dependencies.getValue(this)
    fun setDependencies(value: List<String>) {
        dependencies.getValue(this).clear()
        dependencies.getValue(this).addAll(value)
    }
}
