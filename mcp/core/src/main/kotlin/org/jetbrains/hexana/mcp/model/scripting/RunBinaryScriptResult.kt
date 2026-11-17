package org.jetbrains.hexana.mcp.model.scripting

import kotlinx.serialization.Serializable
import org.jetbrains.hexana.mcp.model.binaries.BinaryModuleIdentityResult

@Serializable
data class RunBinaryScriptResult(
    val status: String,
    val success: Boolean,
    val output: RunBinaryScriptOutputResult,
    val diagnostics: List<RunBinaryScriptDiagnosticResult> = emptyList(),
    val binary: BinaryModuleIdentityResult? = null,
    val sourceName: String,
    val sourcePath: String? = null,
    val durationMillis: Long,
)

@Serializable
data class RunBinaryScriptOutputResult(
    val lines: List<String>,
    val truncated: Boolean,
    val emittedBytes: Int,
    val maxLines: Int,
    val maxBytes: Int,
)

@Serializable
data class RunBinaryScriptDiagnosticResult(
    val code: String,
    val message: String,
    val severity: String,
    val exceptionClass: String? = null,
    val sourceName: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)
