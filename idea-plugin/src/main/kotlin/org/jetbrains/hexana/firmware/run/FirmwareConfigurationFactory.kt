package org.jetbrains.hexana.firmware.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project

/** Factory for [FirmwareRunConfiguration] instances. */
class FirmwareConfigurationFactory(type: FirmwareRunConfigurationType) : ConfigurationFactory(type) {

    override fun getId(): String = FACTORY_ID

    override fun getName(): String = "Firmware"

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        FirmwareRunConfiguration(project, this, CONFIG_NAME)

    override fun getOptionsClass(): Class<out BaseState> = FirmwareRunConfigurationOptions::class.java

    companion object {
        const val FACTORY_ID = "FirmwareConfigurationFactory"
        const val CONFIG_NAME = "Firmware"
    }
}
