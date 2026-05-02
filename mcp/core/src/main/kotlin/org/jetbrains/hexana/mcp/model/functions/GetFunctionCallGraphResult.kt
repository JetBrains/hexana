package org.jetbrains.hexana.mcp.model.functions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GetFunctionCallGraphResult(
    val functions: List<FunctionCallGraphResult>
)

@Serializable
data class FunctionCallGraphResult(
    val funcidx: Int,
    val kind: String,
    val typeidx: Int?,
    val signature: FunctionSignatureResult? = null,
    @SerialName("import")
    val importInfo: FunctionImportResult? = null,
    val exportedAs: List<String> = emptyList(),
    val codeOffset: Long? = null,
    val codeSize: Long? = null,
    val instructionCount: Long? = null,
    val locals: List<FunctionCallGraphLocalResult> = emptyList(),
    val directCalls: List<FunctionDirectCallResult> = emptyList(),
    val directImportedCalls: List<FunctionDirectCallResult> = emptyList(),
    val indirectCalls: List<FunctionIndirectCallResult> = emptyList(),
    val directMemoryWrites: List<FunctionMemoryWriteResult> = emptyList()
)

@Serializable
data class FunctionCallGraphLocalResult(
    val count: Int,
    val type: String
)

@Serializable
data class FunctionDirectCallResult(
    val op: String,
    val text: String,
    val offset: Long? = null,
    val targetFuncidx: Int,
    val target: FunctionCallTargetResult? = null
)

@Serializable
data class FunctionCallTargetResult(
    val funcidx: Int,
    val kind: String,
    val typeidx: Int?,
    val signature: FunctionSignatureResult? = null,
    @SerialName("import")
    val importInfo: FunctionImportResult? = null,
    val exportedAs: List<String> = emptyList()
)

@Serializable
data class FunctionIndirectCallResult(
    val op: String,
    val text: String,
    val offset: Long? = null,
    val typeidx: Int,
    val tableidx: Int
)

@Serializable
data class FunctionMemoryWriteResult(
    val op: String,
    val text: String,
    val offset: Long? = null,
    val memoryOffset: Int? = null,
    val alignment: Int? = null
)
