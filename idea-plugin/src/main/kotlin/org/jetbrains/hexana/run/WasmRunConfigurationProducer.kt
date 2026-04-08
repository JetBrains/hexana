package org.jetbrains.hexana.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement

class WasmRunConfigurationProducer : LazyRunConfigurationProducer<WasmRunConfiguration>() {

    override fun getConfigurationFactory() =
        ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
            .configurationFactories
            .first { it is WasmConfigurationFactory }

    override fun isConfigurationFromContext(
        configuration: WasmRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        return file.extension == "wasm" && configuration.wasmFilePath == file.path
    }

    override fun setupConfigurationFromContext(
        configuration: WasmRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (file.extension != "wasm") return false
        configuration.wasmFilePath = file.path
        configuration.name = file.nameWithoutExtension
        configuration.workingDirectory = file.parent?.path ?: ""
        return true
    }
}
