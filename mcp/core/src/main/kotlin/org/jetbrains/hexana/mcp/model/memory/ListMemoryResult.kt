package org.jetbrains.hexana.mcp.model.memory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ListMemoryResult(
    val memories: List<MemoryResult>
)

@Serializable
data class MemoryResult(
    val memoryIdx: Int,
    val limit: MemoryLimitsResult,
    @SerialName("import")
    val importInfo: MemoryImportResult? = null,
    val definedIn: String,
    val exportedAs: List<String> = emptyList()
)

@Serializable
data class MemoryImportResult(
    val module: String,
    val name: String
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
