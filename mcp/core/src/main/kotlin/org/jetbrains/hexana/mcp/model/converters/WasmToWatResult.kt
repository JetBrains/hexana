package org.jetbrains.hexana.mcp.model.converters

import kotlinx.serialization.Serializable

@Serializable
data class WasmToWatResult(
    val wat: String,
    val warnings: List<String> = emptyList(),
)
