package org.jetbrains.hexana.mcp.model.elements

import kotlinx.serialization.Serializable

@Serializable
data class ListElementSegmentsResult(
    val segments: List<ElementSegmentResult>
)

@Serializable
data class ElementSegmentResult(
    val elementidx: Int,
    val kind: Int,
    val mode: String,
    val tableIdx: Int? = null,
    val offsetExpr: List<String>? = null,
    val elementType: String,
    val elements: List<String>? = null
)
