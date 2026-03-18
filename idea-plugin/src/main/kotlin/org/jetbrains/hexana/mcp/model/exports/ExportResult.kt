package org.jetbrains.hexana.mcp.model.exports

import kotlinx.serialization.Serializable

@Serializable
data class ListExportsResult(
    val exports: List<ExportResult>
)

@Serializable
data class ExportResult(
    val name: String,
    val externIndex: Long,
    val kind: Byte
)
