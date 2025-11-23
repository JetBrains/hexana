package org.jetbrains.hexana.graalvm;

import com.intellij.openapi.actionSystem.impl.ActionConfigurationCustomizer;

@Suppress("UnstableApiUsage")
class GraalInstallationCustomizer: ActionConfigurationCustomizer {
    override fun customize(): ActionConfigurationCustomizer.CustomizeStrategy {
        System.setProperty("polyglotimpl.DisableMultiReleaseCheck", "true")
        return super.customize()
    }
}
