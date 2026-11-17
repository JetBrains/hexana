package org.jetbrains.hexana.mcp.model.functions

import kotlinx.serialization.Serializable

@Serializable
data class ResolveFunctionSignatureResult(
    val funcidx: Int,
    val typeidx: Int?,
    val params: List<String>,
    val results: List<String>,
    val kind: String,
    val module: String? = null,
    val importName: String? = null,
    val exportNames: List<String> = emptyList()
)
