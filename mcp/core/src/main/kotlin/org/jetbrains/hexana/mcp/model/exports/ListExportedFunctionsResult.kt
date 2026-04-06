package org.jetbrains.hexana.mcp.model.exports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ListExportedFunctionsResult(
    val from: Int,
    val to: Int,
    val returned: List<ExportedFunctionResult>,
    val total: Int,
    val truncate: Boolean,
    val nextOffset: Int?
)

@Serializable
data class ExportedFunctionResult(
    val name: String,
    val entry: ExportedFunctionEntryResult
)

@Serializable
data class ExportedFunctionEntryResult(
    val funcidx: Int,
    val module: String? = null,
    val name: String? = null,
    val signature: ExportedFunctionSignatureResult? = null,
    val exportedAs: List<String> = emptyList(),
    val definedIn: String? = null,
    val codeOffset: Long? = null,
    val codeSize: Long? = null,
    val instructionCount: Long? = null
)

@Serializable
data class ExportedFunctionSignatureResult(
    val params: List<String>,
    @SerialName("return")
    val ret: List<String>
)
