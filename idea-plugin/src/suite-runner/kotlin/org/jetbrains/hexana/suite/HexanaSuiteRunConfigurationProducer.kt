package org.jetbrains.hexana.suite

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.isFile
import com.intellij.psi.PsiElement

/**
 * Claims files named `*.test.js` for the Hexana Suite run configuration.
 * Plain `*.js` files stay with [HexanaScriptRunConfigurationProducer], which
 * rejects the `.test.js` suffix.
 */
class HexanaSuiteRunConfigurationProducer : LazyRunConfigurationProducer<HexanaSuiteRunConfiguration>() {

    override fun getConfigurationFactory() =
        ConfigurationTypeUtil.findConfigurationType(HexanaSuiteRunConfigurationType::class.java)
            .configurationFactories
            .first { it is HexanaSuiteConfigurationFactory }

    override fun isConfigurationFromContext(
        configuration: HexanaSuiteRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        return file.name.endsWith(".test.js") && configuration.suitePath == file.path
    }

    override fun setupConfigurationFromContext(
        configuration: HexanaSuiteRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (!file.name.endsWith(".test.js") || !file.isFile) return false
        configuration.suitePath = file.path
        configuration.name = file.name.removeSuffix(".test.js")
        return true
    }
}
