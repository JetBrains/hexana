package org.jetbrains.hexana.run

import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.icons.AllIcons
import org.jetbrains.hexana.HexanaBundle

class WasmRunConfigurationType : ConfigurationTypeBase(
    ID,
    HexanaBundle.message("wasm.run.configuration.type.name"),
    HexanaBundle.message("wasm.run.configuration.type.description"),
    AllIcons.FileTypes.BinaryData,
) {
    init {
        addFactory(WasmConfigurationFactory(this))
    }

    companion object {
        const val ID = "WasmRunConfiguration"
    }
}
