package org.jetbrains.hexana.mcp.model.functions

import kotlinx.serialization.Serializable

@Serializable
data class SearchFunctionsByNameResult(
    val functions: List<FunctionNameSearchResult>
)

@Serializable
data class FunctionNameSearchResult(
    val funcidx: Int,
    val name: String,
    val kind: String,
    val module: String? = null,
    val importName: String? = null,
    val exportName: String? = null,
    val signature: FunctionNameSearchSignatureResult? = null,
    val locals: List<String> = emptyList(),
    val repr: String,
    val offset: Long? = null
)

@Serializable
data class FunctionNameSearchSignatureResult(
    val params: List<String>,
    val results: List<String>
)
