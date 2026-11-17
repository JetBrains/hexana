package org.jetbrains.hexana.mcp.model.types

import kotlinx.serialization.Serializable

@Serializable
data class TypesResult(
    val types: List<TypeResult>
)

@Serializable
data class TypeResult(
    val kind: String,
    val typeidx: Int,
    val importLocations: List<TypeImportLocationResult> = emptyList(),
    val elementType: TypeFieldResult? = null,
    val superTypes: List<UInt>? = null,
    val fields: List<TypeFieldResult>? = null,
    val entries: List<String>? = null,
    val params: List<String>? = null,
    val retTypes: List<String>? = null
)

@Serializable
data class TypeImportLocationResult(
    val locationKind: String,
    val module: String,
    val name: String,
    val locationDisplay: String,
    val funcidx: Int,
    val typeidx: Int
)

@Serializable
data class TypeFieldResult(
    val type: String,
    val mutable: Boolean
)
