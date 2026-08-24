package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.model.binaries.BinaryModuleIdentityResult

sealed interface HexanaScriptSource {
    data class Inline(
        val text: String,
        val name: String = "<inline>",
    ) : HexanaScriptSource

    data class FilePath(val path: String) : HexanaScriptSource
}

data class HexanaScriptLimits(
    val timeoutMillis: Long = 3_000,
    val maxOutputLines: Int = 1_000,
    val maxOutputBytes: Int = HexanaAnalysisLimits.MAX_OUTPUT_BYTES,
    val maxInlineScriptBytes: Int = HexanaAnalysisLimits.MAX_TEXT_FILE_BYTES,
    val maxScriptFileBytes: Int = HexanaAnalysisLimits.MAX_TEXT_FILE_BYTES,
    val maxInlineBinaryBytes: Int = HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES,
    val maxFileBinaryBytes: Int = HexanaAnalysisLimits.MAX_FILE_BINARY_BYTES,
) {
    init {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than 0" }
        require(maxOutputLines > 0) { "maxOutputLines must be greater than 0" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be greater than 0" }
        require(maxInlineScriptBytes > 0) { "maxInlineScriptBytes must be greater than 0" }
        require(maxScriptFileBytes > 0) { "maxScriptFileBytes must be greater than 0" }
        require(maxInlineBinaryBytes > 0) { "maxInlineBinaryBytes must be greater than 0" }
        require(maxFileBinaryBytes > 0) { "maxFileBinaryBytes must be greater than 0" }
    }

    companion object {
        /**
         * Limits for a script tab the user is watching.
         *
         * The 3 s default suits an MCP or CLI call that must answer promptly, but
         * it is far too short for an interactive analysis script over a large
         * binary. The output caps are generous rather than absent: the editor
         * renders every line into a table, so an unbounded script could otherwise
         * exhaust the IDE's heap.
         */
        fun interactive(): HexanaScriptLimits = HexanaScriptLimits(
            timeoutMillis = 60_000,
            maxOutputLines = 200_000,
            maxOutputBytes = 32 * 1024 * 1024,
        )
    }
}

enum class HexanaScriptStatus {
    SUCCESS,
    INVALID_INPUT,
    SCRIPT_ERROR,
    TIMEOUT,
    INTERNAL_ERROR,
}

enum class HexanaScriptDiagnosticSeverity {
    ERROR,
    WARNING,
}

data class HexanaScriptDiagnostic(
    val code: String,
    val message: String,
    val severity: HexanaScriptDiagnosticSeverity = HexanaScriptDiagnosticSeverity.ERROR,
    val exceptionClass: String? = null,
    val sourceName: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

data class HexanaScriptOutput(
    val lines: List<String>,
    val truncated: Boolean,
    val emittedBytes: Int,
    val maxLines: Int,
    val maxBytes: Int,
)

data class HexanaScriptResult(
    val status: HexanaScriptStatus,
    val output: HexanaScriptOutput,
    val diagnostics: List<HexanaScriptDiagnostic>,
    val binary: BinaryModuleIdentityResult?,
    val sourceName: String,
    val sourcePath: String?,
    val durationMillis: Long,
) {
    val success: Boolean
        get() = status == HexanaScriptStatus.SUCCESS
}
