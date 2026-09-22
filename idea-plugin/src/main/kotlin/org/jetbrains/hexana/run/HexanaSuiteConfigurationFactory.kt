package org.jetbrains.hexana.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project

class HexanaSuiteConfigurationFactory(type: HexanaSuiteRunConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = FACTORY_ID

    override fun getName(): String = "Hexana Suite"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        HexanaSuiteRunConfiguration(project, this, CONFIG_NAME)

    override fun getOptionsClass(): Class<out BaseState> = HexanaSuiteRunConfigurationOptions::class.java

    companion object {
        const val FACTORY_ID = "HexanaSuiteConfigurationFactory"
        const val CONFIG_NAME = "Hexana Suite"
    }
}
