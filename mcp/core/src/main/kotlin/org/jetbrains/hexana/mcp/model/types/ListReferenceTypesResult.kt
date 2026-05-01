package org.jetbrains.hexana.mcp.model.types

import kotlinx.serialization.Serializable

@Serializable
data class ListReferenceTypesResult(
    val referenceTypes: ReferenceTypesResult,
    val counts: List<ReferenceValueTypeCountResult>,
    val summary: ReferenceTypesSummaryResult
)

@Serializable
data class ReferenceTypesResult(
    val functionReferences: List<ReferenceValueTypeResult>,
    val externalReferences: List<ReferenceValueTypeResult>,
    val gcReferences: List<ReferenceValueTypeResult>,
    val primitiveReferences: List<ReferenceValueTypeResult>,
    val anyReferences: List<ReferenceValueTypeResult>,
    val noneReferences: List<ReferenceValueTypeResult>
)

@Serializable
data class ReferenceTypesSummaryResult(
    val hasGC: Boolean,
    val hasI31: Boolean,
    val hasStruct: Boolean,
    val hasArray: Boolean,
    val hasRefEq: Boolean,
    val hasAnyRef: Boolean,
    val hasExternRef: Boolean
)
