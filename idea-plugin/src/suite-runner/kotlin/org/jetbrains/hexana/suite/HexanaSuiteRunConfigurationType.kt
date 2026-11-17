package org.jetbrains.hexana.suite

import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.icons.AllIcons

class HexanaSuiteRunConfigurationType : ConfigurationTypeBase(
    ID,
    "Hexana Suite",
    "Run a Hexana script test suite (*.test.js)",
    AllIcons.RunConfigurations.Junit,
) {
    init {
        addFactory(HexanaSuiteConfigurationFactory(this))
    }

    companion object {
        const val ID = "HexanaSuiteRunConfiguration"
    }
}
