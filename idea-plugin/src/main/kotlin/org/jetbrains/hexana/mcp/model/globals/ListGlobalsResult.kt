package org.jetbrains.hexana.mcp.model.globals

import kotlinx.serialization.Serializable

@Serializable
data class ListGlobalsResult(
    val globals: List<GlobalResult>
)

@Serializable
data class GlobalResult(
    val globalIdx: Int,
    val prefix: String? = null,
    val name: String? = null,
    val type: String,
    val mutability: Boolean,
    val expression: String? = null,
    val definedIn: String
)
