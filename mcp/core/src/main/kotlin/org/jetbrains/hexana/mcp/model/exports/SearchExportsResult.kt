package org.jetbrains.hexana.mcp.model.exports

import kotlinx.serialization.Serializable
import org.jetbrains.hexana.mcp.model.memory.MemoryLimitsResult

@Serializable
data class SearchExportsResult(
    val results: List<SearchExportResult>
)

@Serializable
data class SearchExportResult(
    val name: String,
    val kind: String,
    val externIndex: Int,
    val funcidx: Int? = null,
    val tableidx: Int? = null,
    val memidx: Int? = null,
    val globalidx: Int? = null,
    val tagidx: Int? = null,
    val signature: SearchExportFunctionSignatureResult? = null,
    val limits: MemoryLimitsResult? = null,
    val repr: String,
    val score: Double
)

@Serializable
data class SearchExportFunctionSignatureResult(
    val params: List<String>,
    val results: List<String>
)
