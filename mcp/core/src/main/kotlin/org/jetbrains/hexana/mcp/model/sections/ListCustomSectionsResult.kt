package org.jetbrains.hexana.mcp.model.sections

import kotlinx.serialization.Serializable

@Serializable
data class ListCustomSectionsResult(
    val customSections: List<CustomSectionResult>
)

@Serializable
data class CustomSectionResult(
    val name: String,
    val offset: Long,
    val size: Long,
    val encoding: String,
    val preview: String
)
