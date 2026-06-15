package org.jetbrains.hexana.mcp.model.functions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FunctionsResult(
    val functions: List<FunctionSummaryResult>
)

@Serializable
data class FunctionSummaryResult(
    val funcidx: Int,
    val typeidx: Int? = null,
    val signature: FunctionSignatureResult? = null,
    @SerialName("import")
    val importInfo: FunctionImportResult? = null,
    val exportedAs: List<String> = emptyList(),
    val definedIn: String,
    val locationKind: String? = null,
    val module: String? = null,
    val name: String? = null,
    val locationDisplay: String? = null,
    val codeOffset: Long? = null,
    val codeSize: Long? = null,
    val instructionCount: Long? = null
)

@Serializable
data class FunctionImportResult(
    val module: String,
    val name: String
)

@Serializable
data class FunctionSignatureResult(
    val params: List<String>,
    val results: List<String>,
    @SerialName("return")
    val ret: List<String> = results
)
