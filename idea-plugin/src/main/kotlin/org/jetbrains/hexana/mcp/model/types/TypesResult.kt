package org.jetbrains.hexana.mcp.model.types

import kotlinx.serialization.Serializable

@Serializable
data class TypesResult(
    val types: List<TypeResult>
)

@Serializable
data class TypeResult(
    val kind: String,
    val typeidx: Int? = null,
    val elementType: TypeFieldResult? = null,
    val superTypes: List<UInt>? = null,
    val fields: List<TypeFieldResult>? = null,
    val entries: List<TypeResult>? = null,
    val params: List<TypeResult>? = null,
    val retTypes: List<TypeResult>? = null,
    val type: String? = null,
    val packType: String? = null,
    val refKind: String? = null,
    val nullable: Boolean? = null
)

@Serializable
data class TypeFieldResult(
    val type: TypeResult,
    val mutable: Boolean
)
