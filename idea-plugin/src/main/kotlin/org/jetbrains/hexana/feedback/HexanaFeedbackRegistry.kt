package org.jetbrains.hexana.feedback

import com.intellij.openapi.util.registry.Registry

internal const val HexanaInIdeFeedbackRegistryKey = "hexana.feedback.in.ide.enabled"

internal fun isInIdeFeedbackEnabled(): Boolean =
    Registry.`is`(HexanaInIdeFeedbackRegistryKey)
