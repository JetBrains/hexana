package org.jetbrains.hexana.cli.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.jetbrains.hexana.cli.runtime.CliTable
import org.jetbrains.hexana.cli.runtime.FormatAwareToolOutputRenderer
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.ToolExecutionRequest
import org.jetbrains.hexana.cli.runtime.ToolExecutionResult
import org.jetbrains.hexana.cli.runtime.ToolExecutor
import org.jetbrains.hexana.cli.runtime.ToolInputConversionRequest
import org.jetbrains.hexana.cli.runtime.ToolInputConverter
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.cli.runtime.ToolOutputRenderRequest
import org.jetbrains.hexana.cli.runtime.TypedToolOutput
import org.jetbrains.hexana.cli.runtime.TypedToolOutputPlan
import org.jetbrains.hexana.cli.runtime.TypedToolOutputProjection
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.TextInput
import org.jetbrains.hexana.mcp.model.inspections.BinaryProblemResult
import org.jetbrains.hexana.mcp.model.inspections.InspectBinaryResult
import java.security.MessageDigest

public const val INSPECT_BINARY_TOOL_NAME: String = "inspect_binary"

private const val RUNTIME_TARGETS_PATH_PARAM = "runtimeTargetsPath"
private const val SARIF_CONTENT_TYPE = "application/sarif+json; charset=utf-8"
private const val SARIF_SCHEMA =
    "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/Documents/CommitteeSpecifications/2.1.0/sarif-schema-2.1.0.json"

/** Exit status when the binary has at least one error, matching `run-script` failing on an error finding. */
public const val INSPECT_BINARY_ERRORS_EXIT_CODE: Int = 1

internal class InspectBinaryInputConverter : ToolInputConverter {
    override fun convert(request: ToolInputConversionRequest): JsonObject {
        require(request.context.toolName == INSPECT_BINARY_TOOL_NAME)
        val unknown = request.rawArguments.keys - RUNTIME_TARGETS_PATH_PARAM
        require(unknown.isEmpty()) { "inspect-binary does not accept parameters $unknown." }
        require(request.sources.pathArguments.isEmpty()) { "inspect-binary requires a selected binary source." }
        require(request.sources.stdinText == null && request.sources.stdinBytesCopy() == null) {
            "inspect-binary requires a selected binary source."
        }
        val source = request.sources.sourceSelections.singleOrNull()
            ?: throw IllegalArgumentException("inspect-binary requires exactly one binary source selection.")
        require(source.slotName == "binary") { "inspect-binary source must use slot 'binary', got '${source.slotName}'." }
        val runtimeTargetsPath = request.rawArguments[RUNTIME_TARGETS_PATH_PARAM]
        if (runtimeTargetsPath != null) {
            val path = (runtimeTargetsPath as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(!path.isNullOrBlank()) { "inspect-binary --runtimes requires a file path." }
        }
        return JsonObject(listOfNotNull(runtimeTargetsPath?.let { RUNTIME_TARGETS_PATH_PARAM to it }).toMap())
    }
}

internal class InspectBinaryExecutor(
    private val inspect: (BinaryInput, TextInput?) -> InspectBinaryResult,
) : ToolExecutor {
    constructor(analysisService: HexanaAnalysisService) : this(analysisService::inspectBinary)

    override fun execute(request: ToolExecutionRequest): ToolExecutionResult {
        require(request.context.toolName == INSPECT_BINARY_TOOL_NAME)
        val runtimeTargets = (request.arguments[RUNTIME_TARGETS_PATH_PARAM] as? JsonPrimitive)?.contentOrNull
        val result = inspect(request.sources.singleBinaryInput("inspect-binary"), runtimeTargets?.let(TextInput::FilePath))
        return ToolExecutionResult(HexanaJson.compact.encodeToJsonElement(InspectBinaryResult.serializer(), result))
    }
}

/** `0` for a binary without errors, [INSPECT_BINARY_ERRORS_EXIT_CODE] otherwise; warnings never fail. */
internal fun inspectBinaryExitCode(semanticResult: JsonElement): Int {
    val result = HexanaJson.compact.decodeFromJsonElement(InspectBinaryResult.serializer(), semanticResult)
    return if (result.counts.errors > 0) INSPECT_BINARY_ERRORS_EXIT_CODE else 0
}

internal object InspectBinaryRenderer : FormatAwareToolOutputRenderer {
    private val delegate = JsonBackedTypedToolOutputRenderer(
        toolName = INSPECT_BINARY_TOOL_NAME,
        plan = TypedToolOutputPlan(
            serializer = InspectBinaryResult.serializer(),
            projections = listOf(
                TypedToolOutputProjection(ToolOutputFormat.PRETTY) { result, _ ->
                    TypedToolOutput.Text(renderInspectBinaryPretty(result))
                },
                TypedToolOutputProjection(ToolOutputFormat.TABLE) { result, _ ->
                    TypedToolOutput.Table(inspectBinaryTable(result))
                },
                TypedToolOutputProjection(ToolOutputFormat.SARIF) { result, _ ->
                    TypedToolOutput.Text(renderInspectBinarySarif(result), SARIF_CONTENT_TYPE)
                },
            ),
        ),
        renderJson = { result ->
            RenderedToolOutput.Text(
                text = HexanaJson.compact.encodeToString(JsonElement.serializer(), result),
                contentType = SummarizeBinaryJsonRenderer.CONTENT_TYPE,
            )
        },
        renderTable = CliTableRenderer::render,
    )

    override val supportedFormats: Set<ToolOutputFormat> = delegate.supportedFormats

    override fun render(request: ToolOutputRenderRequest): RenderedToolOutput = delegate.render(request)
}

/**
 * ```
 * module.wasm (WebAssembly): 1 error, 1 warning
 *   error    func 3  0x1a2+0x10  … [wasm.code_entry_corrupt]
 *   warning  Garbage collection: Chrome 90 — not supported, needs 119 [wasm.proposal_unsupported]
 * Runtime targets: Chrome 90
 * ```
 */
public fun renderInspectBinaryPretty(result: InspectBinaryResult): String = buildString {
    val subject = result.module.path ?: result.module.displayFormat
    val summary = result.counts.label().ifEmpty { "no problems" }
    append(subject)
    if (result.module.path != null) append(" (").append(result.module.displayFormat).append(')')
    append(": ").append(summary)
    for (problem in result.problems) {
        append("\n  ").append(problem.severity.padEnd(9))
        problem.location?.let { append(it).append("  ") }
        problem.byteRangeLabel()?.let { append(it).append("  ") }
        // The code names the kind; parser messages already restate it in prose.
        append(problem.message).append(" [").append(problem.code).append(']')
    }
    append("\nRuntime targets: ")
    append(if (result.runtimeTargets.isEmpty()) "none declared (pass --runtimes .hexana/runtimes.json)" else result.runtimeTargets.joinToString(", "))
}

public fun inspectBinaryTable(result: InspectBinaryResult): CliTable = CliTable(
    headers = listOf("Severity", "Code", "Location", "Offset", "Size", "Message"),
    rows = result.problems.map { problem ->
        listOf(
            problem.severity,
            problem.code,
            problem.location.orEmpty(),
            problem.byteOffset?.let(::hexLabel).orEmpty(),
            problem.byteLength?.let(::hexLabel).orEmpty(),
            problem.message,
        )
    },
)

/**
 * SARIF 2.1.0: one rule per inspection that ran (so a clean run still documents what was
 * checked), one result per problem. Byte positions use SARIF's `byteOffset`/`byteLength`
 * region; a problem inside a Wasm function adds a function logical location, the same
 * shape `run-script --format sarif` emits for a function-anchored finding.
 */
public fun renderInspectBinarySarif(result: InspectBinaryResult, toolVersion: String = "0.0.0"): String {
    val knownRules = result.inspections.map { it.id }.toSet()
    val rules = result.inspections.map { inspection ->
        buildJsonObject {
            put("id", inspection.id)
            put("name", inspection.name)
            put("shortDescription", buildJsonObject { put("text", inspection.description) })
        }
    } + result.problems.map { it.inspectionId }.distinct().filterNot { it in knownRules }.map { id ->
        buildJsonObject { put("id", id) }
    }
    val results = result.problems.map { problem ->
        buildJsonObject {
            put("ruleId", problem.inspectionId)
            put("level", problem.severity.toSarifLevel())
            put("message", buildJsonObject { put("text", problem.message) })
            put("partialFingerprints", buildJsonObject { put("hexanaProblem/v1", problem.fingerprint()) })
            put("properties", buildJsonObject { put("code", problem.code) })
            put("locations", buildJsonArray {
                add(buildJsonObject {
                    put("physicalLocation", buildJsonObject {
                        put("artifactLocation", buildJsonObject { result.module.path?.let { put("uri", it) } })
                        if (problem.byteOffset != null) {
                            put("region", buildJsonObject {
                                put("byteOffset", problem.byteOffset)
                                problem.byteLength?.let { put("byteLength", it) }
                            })
                        }
                    })
                    val funcidx = problem.funcidx
                    if (funcidx != null) {
                        put("logicalLocations", buildJsonArray {
                            add(buildJsonObject {
                                put("name", "func[$funcidx]")
                                put("kind", "function")
                            })
                        })
                    }
                })
            })
        }
    }
    val sarif = buildJsonObject {
        put("\$schema", SARIF_SCHEMA)
        put("version", "2.1.0")
        put("runs", buildJsonArray {
            add(buildJsonObject {
                put("tool", buildJsonObject {
                    put("driver", buildJsonObject {
                        put("name", "hexana")
                        put("version", toolVersion)
                        put("rules", JsonArray(rules))
                    })
                })
                put("results", JsonArray(results))
            })
        })
    }
    return HexanaJson.compact.encodeToString(JsonObject.serializer(), sarif)
}

private fun org.jetbrains.hexana.mcp.model.inspections.BinaryProblemCountsResult.label(): String {
    fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
    return listOfNotNull(
        plural(errors, "error").takeIf { errors > 0 },
        plural(warnings, "warning").takeIf { warnings > 0 },
        plural(infos, "note").takeIf { infos > 0 },
    ).joinToString(", ")
}

private fun BinaryProblemResult.byteRangeLabel(): String? {
    val offset = byteOffset ?: return null
    return byteLength?.let { "${hexLabel(offset)}+${hexLabel(it)}" } ?: hexLabel(offset)
}

private fun hexLabel(value: Long): String = "0x${value.toString(16)}"

private fun String.toSarifLevel(): String = when (this) {
    "error" -> "error"
    "warning" -> "warning"
    else -> "note"
}

/**
 * Stable across runs of an unchanged binary, and independent of the message wording, so a
 * SARIF consumer tracks the same problem across Hexana versions that rephrase it.
 */
private fun BinaryProblemResult.fingerprint(): String {
    val input = "$code|${byteOffset ?: ""}|${byteLength ?: ""}|${funcidx ?: ""}|${location.orEmpty()}"
    return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
