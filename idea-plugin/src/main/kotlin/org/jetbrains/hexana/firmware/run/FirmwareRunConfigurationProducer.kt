package org.jetbrains.hexana.firmware.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.isFile
import com.intellij.psi.PsiElement

/**
 * Produces [FirmwareRunConfiguration] instances from the project view context.
 *
 * Matches files whose extension is `.elf`, or whose name is `zephyr.elf`.
 * The producer is conservative: it does not eagerly invoke [FirmwareTargetService]
 * because that runs off-EDT. Extension-based matching is sufficient for the IDE context menu.
 */
class FirmwareRunConfigurationProducer : LazyRunConfigurationProducer<FirmwareRunConfiguration>() {

    override fun getConfigurationFactory() =
        ConfigurationTypeUtil.findConfigurationType(FirmwareRunConfigurationType::class.java)
            .configurationFactories
            .first { it is FirmwareConfigurationFactory }

    override fun isConfigurationFromContext(
        configuration: FirmwareRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        return isElfFile(file.name) && configuration.elfPath == file.path
    }

    override fun setupConfigurationFromContext(
        configuration: FirmwareRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (!isElfFile(file.name) || !file.isFile) return false
        configuration.elfPath = file.path
        configuration.name = file.nameWithoutExtension
        // Leave dtsPath/configPath blank — they are discovered at checkConfiguration time
        // via ElfSidecarDiscovery and FirmwareTargetService.
        return true
    }

    private fun isElfFile(name: String): Boolean =
        name.endsWith(".elf") || name == "zephyr.elf"
}
