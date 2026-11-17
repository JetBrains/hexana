package org.jetbrains.hexana.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.isFile
import com.intellij.psi.PsiElement

class HexanaScriptRunConfigurationProducer : LazyRunConfigurationProducer<HexanaScriptRunConfiguration>() {

    override fun getConfigurationFactory() =
        ConfigurationTypeUtil.findConfigurationType(HexanaScriptRunConfigurationType::class.java)
            .configurationFactories
            .first { it is HexanaScriptConfigurationFactory }

    override fun isConfigurationFromContext(
        configuration: HexanaScriptRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        return file.extension == "js" && !file.name.endsWith(".test.js") && configuration.scriptPath == file.path
    }

    override fun setupConfigurationFromContext(
        configuration: HexanaScriptRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (file.extension != "js" || file.name.endsWith(".test.js") || !file.isFile) return false
        configuration.scriptPath = file.path
        configuration.name = file.nameWithoutExtension
        return true
    }
}
