package org.jetbrains.hexana.mcp.model.binaries

import kotlinx.serialization.Serializable

@Serializable
data class BinaryModuleIdentityResult(
    val path: String?,
    val format: String,
    val displayFormat: String,
    val byteLength: Int,
    val architecture: String? = null,
    val endian: String? = null,
    val wordSize: String? = null,
)

@Serializable
data class BinarySummaryResult(
    val module: BinaryModuleIdentityResult,
    val counts: BinarySummaryCountsResult = BinarySummaryCountsResult(),
    val warnings: List<BinaryWarningResult> = emptyList(),
)

@Serializable
data class BinarySummaryCountsResult(
    val functions: Int? = null,
    val types: Int? = null,
    val memories: Int? = null,
    val globals: Int? = null,
    val tables: Int? = null,
    val symbols: Int? = null,
    val imports: Int? = null,
    val exports: Int? = null,
    val sections: Int? = null,
    val segments: Int? = null,
    val loadCommands: Int? = null,
    val architectures: Int? = null,
    val coreModules: Int? = null,
    val nestedComponents: Int? = null,
    val relocations: Int? = null,
    val strings: Int? = null,
    val archiveMembers: Int? = null,
)

@Serializable
data class QueryBinaryResult(
    val module: BinaryModuleIdentityResult,
    val operation: String,
    val total: Int,
    val offset: Int,
    val returned: Int,
    val nextOffset: Int?,
    val records: List<BinaryRecordResult>,
    val omitted: List<BinaryOmittedResult> = emptyList(),
    val warnings: List<BinaryWarningResult> = emptyList(),
)

@Serializable
data class BinaryRecordResult(
    val kind: String,
    val name: String? = null,
    val summary: String,
    val location: BinaryRecordLocationResult? = null,
    val attributes: List<BinaryRecordAttributeResult> = emptyList(),
    val evidence: List<String> = emptyList(),
)

@Serializable
data class BinaryRecordLocationResult(
    val index: Int? = null,
    val fileOffset: Long? = null,
    val virtualAddress: Long? = null,
    val size: Long? = null,
    val section: String? = null,
)

@Serializable
data class BinaryRecordAttributeResult(
    val name: String,
    val value: String,
)

@Serializable
data class BinaryOmittedResult(
    val kind: String,
    val count: Int,
    val reason: String,
)

@Serializable
data class BinaryWarningResult(
    val code: String,
    val message: String,
)
