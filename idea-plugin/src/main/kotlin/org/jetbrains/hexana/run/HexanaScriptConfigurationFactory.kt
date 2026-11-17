package org.jetbrains.hexana.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project

class HexanaScriptConfigurationFactory(type: HexanaScriptRunConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = FACTORY_ID

    override fun getName(): String = "Hexana Script"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        HexanaScriptRunConfiguration(project, this, CONFIG_NAME)

    override fun getOptionsClass(): Class<out BaseState> = HexanaScriptRunConfigurationOptions::class.java

    companion object {
        const val FACTORY_ID = "HexanaScriptConfigurationFactory"
        const val CONFIG_NAME = "Hexana Script"
    }
}
