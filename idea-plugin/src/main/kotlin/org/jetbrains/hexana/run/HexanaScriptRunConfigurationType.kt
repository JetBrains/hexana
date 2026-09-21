package org.jetbrains.hexana.run

import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.icons.AllIcons

class HexanaScriptRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Hexana Script",
    "Run a Hexana analysis script against a Wasm binary",
    AllIcons.FileTypes.JavaScript,
) {
    init {
        addFactory(HexanaScriptConfigurationFactory(this))
    }

    companion object {
        const val ID = "HexanaScriptRunConfiguration"
    }
}
