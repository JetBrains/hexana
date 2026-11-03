package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.model.binaries.BinaryModuleIdentityResult
import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckRegistrationInfo
import org.jetbrains.hexana.shared.scripting.ProviderRegistrationInfo
import org.jetbrains.hexana.shared.scripting.FlagSpec
import org.jetbrains.hexana.shared.scripting.ScriptConsoleEntry
import org.jetbrains.hexana.shared.scripting.ScriptUsageError
import org.jetbrains.hexana.shared.scripting.SliceRegistrationInfo
import org.jetbrains.hexana.shared.scripting.SuiteArtifactDeclaration
import org.jetbrains.hexana.shared.scripting.SuiteNodeResult

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
    /**
     * How many distinct binaries one run may load through `open(path)`.
     *
     * Each one is held for the rest of the run, so this together with
     * [maxFileBinaryBytes] is what bounds the memory a script can claim. Opening
     * the same path again is free and does not count.
     */
    val maxOpenedBinaries: Int = 8,
) {
    init {
        require(timeoutMillis > 0) { "timeoutMillis must be greater than 0" }
        require(maxOutputLines > 0) { "maxOutputLines must be greater than 0" }
        require(maxOutputBytes > 0) { "maxOutputBytes must be greater than 0" }
        require(maxInlineScriptBytes > 0) { "maxInlineScriptBytes must be greater than 0" }
        require(maxScriptFileBytes > 0) { "maxScriptFileBytes must be greater than 0" }
        require(maxInlineBinaryBytes > 0) { "maxInlineBinaryBytes must be greater than 0" }
        require(maxFileBinaryBytes > 0) { "maxFileBinaryBytes must be greater than 0" }
        require(maxOpenedBinaries > 0) { "maxOpenedBinaries must be greater than 0" }
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
    /** Static metadata for slices registered via `hexana.defineSlice(...)`. */
    val slices: List<SliceRegistrationInfo> = emptyList(),
    /** Console entries emitted via `console.log/error/debug` during the run. */
    val console: List<ScriptConsoleEntry> = emptyList(),
    /** Static metadata for checks registered via `hexana.defineCheck(...)`. */
    val checks: List<CheckRegistrationInfo> = emptyList(),
    /** Static metadata for binary providers registered via `hexana.defineProvider(...)`. */
    val providers: List<ProviderRegistrationInfo> = emptyList(),
    /** Findings produced by running all registered checks against the primary binary. */
    val findings: List<CheckFinding> = emptyList(),
    /**
     * Invocation usage errors detected while resolving argv against `hexana.defineFlags`
     * declarations (for example an undeclared flag). The CLI maps a non-empty list to
     * exit code 2; the tab shape never produces them.
     */
    val usageErrors: List<ScriptUsageError> = emptyList(),
    /** Flag declarations registered via hexana.defineFlags, keyed by flag name. */
    val flagDeclarations: Map<String, FlagSpec> = emptyMap(),
    /**
     * Result tree of a test-suite run started via
     * [org.jetbrains.hexana.mcp.scripting.HexanaSuiteRunner.runSuite].
     * Null for every non-suite run.
     */
    val suite: SuiteNodeResult? = null,
    /**
     * Every `hexana.artifact(role, options)` declaration encountered while the
     * suite module evaluated, in call order, recorded before any resolution or
     * failure. Populated by [HexanaSuiteRunner.runSuite] on success, script
     * error, and binding failure alike (declarations seen before the throw),
     * and by [HexanaSuiteRunner.collectSuiteDeclarations] with no mappings.
     * Empty for every non-suite run.
     */
    val artifactDeclarations: List<SuiteArtifactDeclaration> = emptyList(),
) {
    val success: Boolean
        get() = status == HexanaScriptStatus.SUCCESS
}
