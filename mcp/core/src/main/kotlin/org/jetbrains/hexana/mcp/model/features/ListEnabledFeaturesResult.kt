package org.jetbrains.hexana.mcp.model.features

import kotlinx.serialization.Serializable

@Serializable
data class ListEnabledFeaturesResult(
    val features: EnabledFeaturesResult,
    val sources: EnabledFeatureSourcesResult
)

@Serializable
data class EnabledFeaturesResult(
    val referenceTypes: Boolean,
    val bulkMemory: Boolean,
    val multiValue: Boolean,
    val signExtension: Boolean,
    val simd: Boolean,
    val threads: Boolean,
    val memory64: Boolean,
    val gcTypes: Boolean,
    val subtyping: Boolean,
    val functionReferences: Boolean,
    val extendedConst: Boolean
)

@Serializable
data class EnabledFeatureSourcesResult(
    val sections: List<String>,
    val instructions: List<String>,
    val customSections: List<String>
)
