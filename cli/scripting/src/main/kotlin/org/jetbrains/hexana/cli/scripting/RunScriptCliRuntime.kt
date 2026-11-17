package org.jetbrains.hexana.cli.scripting

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.FormatAwareToolOutputRenderer
import org.jetbrains.hexana.cli.runtime.ToolExecutionRequest
import org.jetbrains.hexana.cli.runtime.ToolExecutionResult
import org.jetbrains.hexana.cli.runtime.ToolExecutor
import org.jetbrains.hexana.cli.runtime.ToolInputConversionRequest
import org.jetbrains.hexana.cli.runtime.ToolInputConverter
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.runtime.ToolInputSources
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.cli.runtime.ToolOutputRenderRequest
import org.jetbrains.hexana.cli.runtime.ToolOutputRenderer
import org.jetbrains.hexana.cli.runtime.ToolRenderOptions
import org.jetbrains.hexana.cli.runtime.ToolRuntimeContext
import org.jetbrains.hexana.cli.scripting.sarif.BaselineStore
import org.jetbrains.hexana.cli.scripting.sarif.SarifSourceResolver
import org.jetbrains.hexana.cli.scripting.sarif.renderSarif
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.BinaryInputLoader
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.LoadedBinary
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.model.scripting.RunBinaryScriptDiagnosticResult
import org.jetbrains.hexana.mcp.model.scripting.RunBinaryScriptOutputResult
import org.jetbrains.hexana.mcp.model.scripting.RunBinaryScriptResult
import org.jetbrains.hexana.mcp.scripting.HexanaScriptDiagnostic
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.jetbrains.hexana.mcp.scripting.HexanaScriptOutput
import org.jetbrains.hexana.mcp.scripting.HexanaScriptResult
import org.jetbrains.hexana.mcp.scripting.HexanaScriptRunner
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSource
import org.jetbrains.hexana.mcp.scripting.HexanaScriptStatus
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ConsoleSeverity
import org.jetbrains.hexana.shared.scripting.FlagType
import org.jetbrains.hexana.shared.scripting.ScriptConsoleEntry
import org.jetbrains.hexana.shared.scripting.ScriptInvocation
import org.jetbrains.hexana.shared.scripting.ScriptUsageError
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

public const val RUN_BINARY_SCRIPT_TOOL_NAME: String = "run_binary_script"
public const val RUN_SCRIPT_COMMAND_NAME: String = "run-script"

public data class RunScriptCliRequest(
    val sourceSelections: List<ToolInputSourceSelection>,
    val params: Map<String, JsonElement> = emptyMap(),
    val format: ToolOutputFormat = ToolOutputFormat.RAW,
    val limits: HexanaScriptLimits = HexanaScriptLimits(),
    val allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
    val toolName: String = RUN_BINARY_SCRIPT_TOOL_NAME,
    /** Script argv tokens (after the script path); passed verbatim into [ScriptInvocation.argv]. */
    val scriptArgs: List<String> = emptyList(),
    /** Extra input binary file paths; passed as [ScriptInvocation.extraInputPaths]. */
    val extraInputPaths: List<String> = emptyList(),
    /** Directory used by [BaselineStore] for classify/update. Missing dir = all NEW. */
    val baselinesDir: Path = Path.of(".hexana/baselines"),
    /** When true, update baseline files from the current run and exit 0 (unless script/usage error). */
    val updateBaselines: Boolean = false,
    /**
     * Path to write a SARIF 2.1.0 document alongside any [format].
     * Null means no SARIF side file is written.
     */
    val sarifOut: Path? = null,
    /** Version string embedded in the SARIF tool.driver.version field. */
    val toolVersion: String = "0.0.0",
)

public data class RunScriptCliOutput(
    val stdout: String,
    val stderr: String = "",
    val exitCode: Int = 0,
)

internal data class ScriptCliRuntimeBinding(
    val converter: ToolInputConverter,
    val executor: ToolExecutor,
    val renderer: ToolOutputRenderer,
) {
    /** Typed accessor to the side-channel fields; null when the executor is not a [RunScriptExecutor]. */
    val scriptExecutor: RunScriptExecutor? get() = executor as? RunScriptExecutor
}

internal class ScriptCliRuntimeContribution private constructor(
    val toolNames: Set<String>,
    private val bindings: Map<String, ScriptCliRuntimeBinding>,
) {
    val commandNames: Set<String> = setOf(RUN_SCRIPT_COMMAND_NAME)

    fun binding(toolName: String): ScriptCliRuntimeBinding =
        bindings[toolName] ?: error("Script CLI runtime binding is not registered for '$toolName'.")

    companion object {
        fun create(
            expectedTools: Set<String>,
            converters: Map<String, ToolInputConverter>,
            executors: Map<String, ToolExecutor>,
            renderers: Map<String, ToolOutputRenderer>,
        ): ScriptCliRuntimeContribution {
            require(expectedTools == setOf(RUN_BINARY_SCRIPT_TOOL_NAME)) {
                "Script CLI runtime must expose exactly $RUN_BINARY_SCRIPT_TOOL_NAME, got $expectedTools."
            }
            val registeredTools = converters.keys + executors.keys + renderers.keys
            require(registeredTools == expectedTools) {
                "Script CLI runtime registry must contain exactly $expectedTools, got $registeredTools."
            }
            val bindings = expectedTools.associateWith { toolName ->
                ScriptCliRuntimeBinding(
                    converter = requireNotNull(converters[toolName]) { "Missing converter for '$toolName'." },
                    executor = requireNotNull(executors[toolName]) { "Missing executor for '$toolName'." },
                    renderer = requireNotNull(renderers[toolName]) { "Missing renderer for '$toolName'." },
                )
            }
            return ScriptCliRuntimeContribution(expectedTools, bindings)
        }
    }
}

internal class RunScriptInputConverter : ToolInputConverter {
    override fun convert(request: ToolInputConversionRequest): JsonObject {
        require(request.context.toolName == RUN_BINARY_SCRIPT_TOOL_NAME)
        require(request.sources.pathArguments.isEmpty()) { "run-script requires a selected binary source." }
        require(request.sources.stdinText == null && request.sources.stdinBytesCopy() == null) {
            "run-script requires a selected binary source."
        }
        val source = request.sources.sourceSelections.singleOrNull()
            ?: throw IllegalArgumentException("run-script requires exactly one binary source selection.")
        require(source.slotName == "binary") {
            "run-script source must use slot 'binary', got '${source.slotName}'."
        }
        val unsupportedParams = request.rawArguments.keys - setOf("scriptPath", "script")
        require(unsupportedParams.isEmpty()) {
            "run-script does not accept semantic parameters: ${unsupportedParams.sorted()}."
        }
        return request.rawArguments
    }
}

/**
 * Carries the script invocation context (argv, extra input paths) through the execute path.
 * Stored on the executor rather than threaded through the standard
 * [ToolExecutionRequest] to avoid touching mcp/core wire models.
 */
internal data class RunScriptInvocationContext(
    val scriptArgs: List<String> = emptyList(),
    val extraInputPaths: List<String> = emptyList(),
)

internal class RunScriptExecutor(
    private val limits: HexanaScriptLimits,
    private val allowedFilePolicy: AllowedFilePolicy,
    private val invocationContext: RunScriptInvocationContext = RunScriptInvocationContext(),
    private val runScript: (LoadedBinary, HexanaScriptSource, ScriptInvocation) -> HexanaScriptResult = { loaded, source, invocation ->
        HexanaScriptRunner(limits = limits, allowedFilePolicy = allowedFilePolicy)
            .run(source = source, binaryProvider = loaded.provider, binaryIdentity = loaded.identity, invocation = invocation)
    },
) : ToolExecutor {
    /**
     * Captures the last raw [HexanaScriptResult] produced by this executor.
     * Set immediately before returning from [execute]; the caller (runScriptCli)
     * reads it right after [execute] returns to access findings/console/usageErrors
     * that are not representable in the wire [RunBinaryScriptResult].
     */
    @Volatile
    var lastScriptResult: HexanaScriptResult? = null
        private set

    /**
     * Captures the primary [org.jetbrains.hexana.providers.BinaryProvider] loaded during
     * [execute]. Used by [runScriptCli] to build [SarifSourceResolver] for SARIF output
     * without reloading the binary a second time. Null when loading failed or the source
     * was invalid.
     */
    @Volatile
    var lastPrimaryProvider: org.jetbrains.hexana.providers.BinaryProvider? = null
        private set

    override fun execute(request: ToolExecutionRequest): ToolExecutionResult {
        require(request.context.toolName == RUN_BINARY_SCRIPT_TOOL_NAME)
        val rawResult: HexanaScriptResult
        val wireResult = when (val source = request.arguments.scriptSourceSelection()) {
            is ScriptSourceSelection.Valid -> {
                rawResult = executeValid(source, request.sources.singleBinaryInput())
                rawResult.toRunBinaryScriptResult()
            }
            is ScriptSourceSelection.Invalid -> {
                rawResult = HexanaScriptResult(
                    status = HexanaScriptStatus.INVALID_INPUT,
                    output = emptyOutput(),
                    diagnostics = emptyList(),
                    binary = null,
                    sourceName = "<script>",
                    sourcePath = null,
                    durationMillis = 0,
                )
                invalidRunBinaryScriptResult(source.message, limits.maxOutputBytes)
            }
        }
        lastScriptResult = rawResult
        return ToolExecutionResult(
            HexanaJson.compact.encodeToJsonElement(RunBinaryScriptResult.serializer(), wireResult)
        )
    }

    private fun executeValid(source: ScriptSourceSelection.Valid, input: BinaryInput): HexanaScriptResult {
        val scriptName = when (val s = source.source) {
            is HexanaScriptSource.FilePath -> s.path
            is HexanaScriptSource.Inline -> s.name
        }
        val invocation = ScriptInvocation(
            argv = listOf(scriptName) + invocationContext.scriptArgs,
            extraInputPaths = invocationContext.extraInputPaths,
        )
        val loaded = try {
            BinaryInputLoader(
                maxInlineInputBytes = limits.maxInlineBinaryBytes,
                maxFileInputBytes = limits.maxFileBinaryBytes,
                allowedFilePolicy = allowedFilePolicy,
            ).load(input)
        } catch (error: HexanaAnalysisException) {
            return HexanaScriptResult(
                status = HexanaScriptStatus.INVALID_INPUT,
                output = emptyOutput(),
                diagnostics = listOf(
                    HexanaScriptDiagnostic(
                        code = error.code.name,
                        message = error.message,
                        exceptionClass = error::class.java.name,
                    )
                ),
                binary = null,
                sourceName = scriptName,
                sourcePath = (source.source as? HexanaScriptSource.FilePath)?.path,
                durationMillis = 0,
            )
        }
        lastPrimaryProvider = loaded.provider
        return runScript(loaded, source.source, invocation)
    }

    private fun emptyOutput() = HexanaScriptOutput(
        lines = emptyList(), truncated = false, emittedBytes = 0,
        maxLines = limits.maxOutputLines, maxBytes = limits.maxOutputBytes,
    )
}

internal object RunScriptOutputRenderer : FormatAwareToolOutputRenderer {
    const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    const val RAW_CONTENT_TYPE = "text/plain; charset=utf-8"

    override val supportedFormats: Set<ToolOutputFormat> =
        setOf(ToolOutputFormat.JSON, ToolOutputFormat.RAW, ToolOutputFormat.SARIF)

    override fun render(request: ToolOutputRenderRequest): RenderedToolOutput {
        require(request.context.toolName == RUN_BINARY_SCRIPT_TOOL_NAME)
        val result = HexanaJson.compact.decodeFromJsonElement(RunBinaryScriptResult.serializer(), request.result)
        return when (request.options.format) {
            ToolOutputFormat.JSON -> RenderedToolOutput.Text(
                text = HexanaJson.compact.encodeToString(RunBinaryScriptResult.serializer(), result),
                contentType = JSON_CONTENT_TYPE,
            )
            ToolOutputFormat.RAW -> RenderedToolOutput.Text(
                text = result.output.lines.joinToString(separator = "\n")
                    .takeUtf8Prefix(result.rawStdoutBudget()),
                contentType = RAW_CONTENT_TYPE,
            )
            ToolOutputFormat.SARIF -> error("run-script sarif output is assembled in runScriptCli, not the renderer pipeline")
            ToolOutputFormat.PRETTY -> error("run-script does not support pretty output")
            ToolOutputFormat.TABLE -> error("run-script does not support table output")
        }
    }
}

public fun runScriptSupportedOutputFormats(): Set<ToolOutputFormat> = RunScriptOutputRenderer.supportedFormats

public fun runScriptCli(request: RunScriptCliRequest): RunScriptCliOutput {
    val context = ToolRuntimeContext(toolName = request.toolName)

    val contribution = runScriptRuntimeContribution(
        limits = request.limits,
        allowedFilePolicy = request.allowedFilePolicy,
        invocationContext = RunScriptInvocationContext(
            scriptArgs = request.scriptArgs,
            extraInputPaths = request.extraInputPaths,
        ),
    )
    val binding = contribution.binding(context.toolName)
    val sources = ToolInputSources(sourceSelections = request.sourceSelections)
    val arguments = binding.converter.convert(
        ToolInputConversionRequest(
            context = context,
            rawArguments = JsonObject(request.params),
            sources = sources,
        )
    )
    val execution = binding.executor.execute(ToolExecutionRequest(context, arguments, sources))

    // Obtain the rich HexanaScriptResult from the executor side-channel.
    val richResult: HexanaScriptResult? = binding.scriptExecutor?.lastScriptResult
    val wireResult = execution.semanticResult.toRunBinaryScriptResult()

    // Console entries always go to stderr, every mode, level-prefixed.
    val consoleStderr = richResult?.console?.toConsoleStderr() ?: ""

    // Usage errors from the script (undeclared flags, etc.) exit 2.
    val usageErrors = richResult?.usageErrors ?: emptyList()
    if (usageErrors.isNotEmpty()) {
        val usageStderr = usageErrors.joinToString(separator = "\n", postfix = "\n") { err ->
            err.toHumanReadableString().asSingleLine()
        }
        return RunScriptCliOutput(stdout = "", stderr = consoleStderr + usageStderr, exitCode = 2)
    }

    // Classify findings from the rich result against the baseline store.
    val store = BaselineStore(request.baselinesDir)
    val findings = richResult?.findings ?: emptyList()
    val classified = store.classify(findings)

    if (request.updateBaselines) {
        store.update(findings)
    }

    // Retrieve the primary binary provider captured by the executor during binary load.
    val primaryProvider = binding.scriptExecutor?.lastPrimaryProvider

    // Assemble SARIF when requested (--format sarif or --sarif-out).
    fun assembleSarif(): String {
        val resolver = primaryProvider?.let { SarifSourceResolver.from(it) }
        return renderSarif(
            checks = richResult?.checks ?: emptyList(),
            findings = classified,
            toolVersion = request.toolVersion,
            functionName = resolver?.let { r -> { funcidx: Long -> r.functionName(funcidx) } },
            sourceLocation = resolver?.let { r -> { funcidx: Long -> r.sourceLocation(funcidx) } },
        )
    }

    // Side-file SARIF output (written regardless of stdout format).
    if (request.sarifOut != null) {
        val sarifText = assembleSarif()
        val parent = request.sarifOut.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val sarifWithNewline = if (sarifText.endsWith("\n")) sarifText else sarifText + "\n"
        Files.writeString(request.sarifOut, sarifWithNewline)
    }

    val stdout: String
    val diagnosticsStderr: String
    if (request.format == ToolOutputFormat.SARIF) {
        stdout = assembleSarif()
        diagnosticsStderr = ""
    } else {
        val rendered = binding.renderer.render(
            ToolOutputRenderRequest(
                context = context,
                arguments = arguments,
                result = execution.semanticResult,
                options = ToolRenderOptions(format = request.format),
            )
        )
        stdout = when (rendered) {
            is RenderedToolOutput.Text -> rendered.text
            is RenderedToolOutput.Binary -> error("run-script does not support binary output")
        }
        // Diagnostics go to stderr in RAW mode only (existing behavior).
        diagnosticsStderr = if (request.format == ToolOutputFormat.RAW) {
            wireResult.diagnostics.toRunScriptDiagnosticsStderr()
        } else {
            ""
        }
    }
    val stderr = consoleStderr + diagnosticsStderr

    // Exit 1 iff --update-baselines=false AND any ERROR-severity finding is NEW,
    // OR the script itself failed (existing result.success=false behavior stays).
    val exitCode = when {
        request.updateBaselines -> if (wireResult.success) 0 else 1
        !wireResult.success -> 1
        classified.any {
            it.finding.severity == CheckSeverity.ERROR &&
                it.baselineState == org.jetbrains.hexana.cli.scripting.sarif.BaselineState.NEW
        } -> 1
        else -> 0
    }

    return RunScriptCliOutput(
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
    )
}


internal fun runScriptRuntimeContribution(
    limits: HexanaScriptLimits = HexanaScriptLimits(),
    allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
    invocationContext: RunScriptInvocationContext = RunScriptInvocationContext(),
): ScriptCliRuntimeContribution =
    ScriptCliRuntimeContribution.create(
        expectedTools = setOf(RUN_BINARY_SCRIPT_TOOL_NAME),
        converters = mapOf(RUN_BINARY_SCRIPT_TOOL_NAME to RunScriptInputConverter()),
        executors = mapOf(RUN_BINARY_SCRIPT_TOOL_NAME to RunScriptExecutor(limits, allowedFilePolicy, invocationContext)),
        renderers = mapOf(RUN_BINARY_SCRIPT_TOOL_NAME to RunScriptOutputRenderer),
    )

private fun JsonElement.toRunBinaryScriptResult(): RunBinaryScriptResult =
    HexanaJson.compact.decodeFromJsonElement(RunBinaryScriptResult.serializer(), this)

// ---------------------------------------------------------------------------
// Console / usage-error helpers operating on domain types from plugins-shared
// ---------------------------------------------------------------------------

private fun List<ScriptConsoleEntry>.toConsoleStderr(): String {
    if (isEmpty()) return ""
    return joinToString(separator = "") { entry ->
        val prefix = when (entry.severity) {
            ConsoleSeverity.LOG -> "[log] "
            ConsoleSeverity.ERROR -> "[error] "
            ConsoleSeverity.DEBUG -> "[debug] "
        }
        "$prefix${entry.text}\n"
    }
}

private fun ScriptUsageError.toHumanReadableString(): String = when (this) {
    is ScriptUsageError.UndeclaredFlag -> "Undeclared flag: $key"
    is ScriptUsageError.InvalidFlagValue -> "Invalid value '$value' for flag $key. Allowed: ${allowed.joinToString()}"
    is ScriptUsageError.FlagTypeMismatch -> "Flag $key expects type $type, got '$value'"
}

private sealed interface ScriptSourceSelection {
    data class Valid(val source: HexanaScriptSource) : ScriptSourceSelection
    data class Invalid(val message: String) : ScriptSourceSelection
}

private fun JsonObject.scriptSourceSelection(): ScriptSourceSelection {
    val scriptPath = optionalStringParam("scriptPath")
    val script = optionalStringParam("script")
    val providedInputs = listOf(scriptPath, script).count { it != null }
    if (providedInputs == 0) {
        return ScriptSourceSelection.Invalid("Exactly one of --script or --script-text is required.")
    }
    if (providedInputs > 1) {
        return ScriptSourceSelection.Invalid("Provide exactly one of --script or --script-text.")
    }
    return when {
        scriptPath != null -> ScriptSourceSelection.Valid(HexanaScriptSource.FilePath(scriptPath))
        script != null -> ScriptSourceSelection.Valid(HexanaScriptSource.Inline(script))
        else -> ScriptSourceSelection.Invalid("Invalid script source selection.")
    }
}

private fun JsonObject.optionalStringParam(name: String): String? {
    val value = (this[name] as? JsonPrimitive)?.contentOrNull ?: return null
    if (value.isBlank()) {
        return null
    }
    return value
}

private fun invalidRunBinaryScriptResult(message: String, maxOutputBytes: Int): RunBinaryScriptResult =
    RunBinaryScriptResult(
        status = "INVALID_INPUT",
        success = false,
        output = RunBinaryScriptOutputResult(
            lines = emptyList(),
            truncated = false,
            emittedBytes = 0,
            maxLines = 0,
            maxBytes = maxOutputBytes,
        ),
        diagnostics = listOf(
            RunBinaryScriptDiagnosticResult(
                code = "INVALID_INPUT",
                message = message,
                severity = "ERROR",
            )
        ),
        binary = null,
        sourceName = "<script>",
        sourcePath = null,
        durationMillis = 0,
    )

private fun ToolInputSources.singleBinaryInput(): BinaryInput {
    require(pathArguments.isEmpty() && stdinText == null && stdinBytesCopy() == null)
    return when (val source = sourceSelections.singleOrNull()) {
        is ToolInputSourceSelection.Path -> BinaryInput.FilePath(source.path)
        is ToolInputSourceSelection.Base64 -> BinaryInput.InlineBytes(source.bytesBase64)
        is ToolInputSourceSelection.StdinBytes -> BinaryInput.RawBytes(source.bytes())
        is ToolInputSourceSelection.ByteList -> BinaryInput.RawBytes(source.bytes.map(Int::toByte).toByteArray())
        null -> throw IllegalArgumentException("run-script requires exactly one binary source selection.")
    }
}

private fun HexanaScriptResult.toRunBinaryScriptResult(): RunBinaryScriptResult =
    RunBinaryScriptResult(
        status = status.name,
        success = success,
        output = output.toRunBinaryScriptOutputResult(),
        diagnostics = diagnostics.map { it.toRunBinaryScriptDiagnosticResult() },
        binary = binary,
        sourceName = sourceName,
        sourcePath = sourcePath,
        durationMillis = durationMillis,
    )

private fun HexanaScriptOutput.toRunBinaryScriptOutputResult(): RunBinaryScriptOutputResult =
    RunBinaryScriptOutputResult(
        lines = lines,
        truncated = truncated,
        emittedBytes = emittedBytes,
        maxLines = maxLines,
        maxBytes = maxBytes,
    )

private fun HexanaScriptDiagnostic.toRunBinaryScriptDiagnosticResult(): RunBinaryScriptDiagnosticResult =
    RunBinaryScriptDiagnosticResult(
        code = code,
        message = message,
        severity = severity.name,
        exceptionClass = exceptionClass,
        sourceName = sourceName,
        line = line,
        column = column,
    )

private fun RunBinaryScriptResult.rawStdoutBudget(): Int =
    output.maxBytes - diagnostics.toRunScriptDiagnosticsStderr().byteSize()

private fun List<RunBinaryScriptDiagnosticResult>.toRunScriptDiagnosticsStderr(): String {
    if (isEmpty()) {
        return ""
    }
    return joinToString(separator = "\n", postfix = "\n") { diagnostic ->
        "${diagnostic.code}: ${diagnostic.message.asSingleLine()}"
    }
}

private fun String.asSingleLine(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

private fun String.byteSize(): Int = toByteArray(StandardCharsets.UTF_8).size

private fun String.takeUtf8Prefix(maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    var used = 0
    var index = 0
    var builder: StringBuilder? = null
    while (index < length) {
        val char = this[index]
        val codePoint: Int
        val charCount: Int
        val replacedSurrogate: Boolean
        when {
            Character.isHighSurrogate(char) && index + 1 < length && Character.isLowSurrogate(this[index + 1]) -> {
                codePoint = Character.toCodePoint(char, this[index + 1])
                charCount = 2
                replacedSurrogate = false
            }
            Character.isSurrogate(char) -> {
                codePoint = REPLACEMENT_CHARACTER_CODE_POINT
                charCount = 1
                replacedSurrogate = true
            }
            else -> {
                codePoint = char.code
                charCount = 1
                replacedSurrogate = false
            }
        }

        val codePointBytes = codePoint.utf8ByteCount()
        if (used + codePointBytes > maxBytes) break

        if (builder != null) {
            builder.appendCodePoint(codePoint)
        } else if (replacedSurrogate) {
            builder = StringBuilder(length)
            builder.append(this, 0, index)
            builder.appendCodePoint(codePoint)
        }
        used += codePointBytes
        index += charCount
    }
    return builder?.toString() ?: substring(0, index)
}

private fun Int.utf8ByteCount(): Int = when {
    this <= 0x7F -> 1
    this <= 0x7FF -> 2
    this <= 0xFFFF -> 3
    else -> 4
}

private const val REPLACEMENT_CHARACTER_CODE_POINT = 0xFFFD
