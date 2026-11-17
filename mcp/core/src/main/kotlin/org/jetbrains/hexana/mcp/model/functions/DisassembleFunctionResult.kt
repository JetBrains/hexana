package org.jetbrains.hexana.mcp.model.functions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// TODO(HX-T-37): Add the remaining ticket fields once Hexana has reliable higher-level analysis:
// controlFlow, pseudocode, summary, and effects.
@Serializable
data class DisassembleFunctionResult(
    val funcidx: Int,
    val typeidx: Int?,
    val signature: DisassembleFunctionSignatureResult?,
    val locals: List<String>,
    val localGroups: List<DisassembledLocalGroupResult> = emptyList(),
    val isImported: Boolean,
    val isExported: Boolean,
    val exportNames: List<String> = emptyList(),
    @SerialName("import")
    val importInfo: FunctionImportResult? = null,
    val codeOffset: Long? = null,
    val codeSize: Long? = null,
    val instructions: List<DisassembledInstructionResult>,
    val wat: String? = null,
    val hex: String? = null
)

@Serializable
data class DisassembleFunctionSignatureResult(
    val params: List<String>,
    val results: List<String>
)

@Serializable
data class DisassembledLocalGroupResult(
    val count: Int,
    val type: String
)

@Serializable
data class DisassembledInstructionResult(
    val op: String,
    val args: List<String> = emptyList(),
    val text: String,
    val offset: Long? = null
)
