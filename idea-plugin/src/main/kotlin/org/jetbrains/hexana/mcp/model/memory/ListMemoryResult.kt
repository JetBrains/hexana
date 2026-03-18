package org.jetbrains.hexana.mcp.model.memory

import kotlinx.serialization.Serializable

@Serializable
data class ListMemoryResult(
    val memories: List<MemoryResult>
)

@Serializable
data class MemoryResult(
    val memoryIdx: Int,
    val limit: MemoryLimitsResult,
    val definedIn: String
)

@Serializable
data class MemoryLimitsResult(
    val addressType: String,
    val min: Long,
    val max: Long? = null,
    val hasMax: Boolean,
    val range: String,
    val flag: String
)
