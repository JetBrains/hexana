package org.jetbrains.hexana.mcp.model.types

import kotlinx.serialization.Serializable

@Serializable
data class ListValueTypesResult(
    val valueTypes: ValueTypesResult,
    val counts: ValueTypeCountsResult,
    val summary: ValueTypeSummaryResult
)

@Serializable
data class ValueTypesResult(
    val scalar: List<String>,
    val vector: List<String>,
    val packed: List<String>,
    val reference: List<ReferenceValueTypeResult>
)

@Serializable
data class ReferenceValueTypeResult(
    val type: String,
    val refKind: String,
    val nullable: Boolean
)

@Serializable
data class ValueTypeCountsResult(
    val valueTypes: ValueTypeCategoryCountsResult
)

@Serializable
data class ValueTypeCategoryCountsResult(
    val scalar: List<ValueTypeCountResult>,
    val vector: List<ValueTypeCountResult>,
    val packed: List<ValueTypeCountResult>,
    val reference: List<ReferenceValueTypeCountResult>
)

@Serializable
data class ValueTypeCountResult(
    val type: String,
    val count: Int
)

@Serializable
data class ReferenceValueTypeCountResult(
    val type: String,
    val refKind: String,
    val nullable: Boolean,
    val count: Int
)

@Serializable
data class ValueTypeSummaryResult(
    val hasGC: Boolean,
    val hasPacked: Boolean,
    val hasSIMD: Boolean,
    val hasReferenceTypes: Boolean,
    val pureMVP: Boolean
)
