package org.jetbrains.hexana.cli.wasmtools

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import java.nio.charset.StandardCharsets
import java.util.Collections

public const val WASM_TOOLS_PRODUCT_ID: String = "hexana-wasm-tools"
public const val WASM_TOOLS_DEFAULT_MAX_ITEMS: Int = 500
private const val OUTPUT_TRUNCATED_CODE: String = "output_truncated"

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

private fun freezeJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(Collections.unmodifiableMap(LinkedHashMap(value.mapValues { (_, nested) -> freezeJson(nested) })))
    is JsonArray -> JsonArray(immutableList(value.map(::freezeJson)))
    else -> value
}

public enum class WasmToolsWorkflow(internal val wireName: String) {
    VALIDATE_AND_EXPLAIN("validate-and-explain"),
    METADATA_AUDIT("metadata-audit"),
    COMPONENT_WIT_REPORT("component-wit-report"),
}

public enum class ReportInputKind(internal val wireName: String) { PATH("path"), STDIN("stdin") }
public enum class DiagnosticSeverity(internal val wireName: String) { WARNING("warning"), ERROR("error") }
public enum class DiagnosticCategory(internal val wireName: String) {
    SECTION_SCAN("section_scan"), SECTION_DECODE("section_decode"), INPUT("input"), RESOURCE_LIMIT("resource_limit"),
    METADATA_DECODE("metadata_decode"), COMPONENT("component"),
}

public enum class FindingSeverity(internal val wireName: String) { INFO("info"), WARNING("warning"), ERROR("error") }
public enum class FindingCategory(internal val wireName: String) {
    STRUCTURAL_PARSE("structural_parse"), METADATA("metadata"), RUNTIME_COMPATIBILITY("runtime_compatibility"),
    COMPONENT_SURFACE("component_surface"), COMPONENT_DEPENDENCY("component_dependency"), RESOURCE_LIMIT("resource_limit"), WIT("wit"),
}

public data class WasmToolsReportInput(
    val kind: ReportInputKind,
    val path: String?,
    val byteCount: Long,
) {
    init {
        require(byteCount >= 0) { "byteCount must be non-negative." }
        require((kind == ReportInputKind.PATH) == (path != null)) { "path is required only for path input." }
    }
}

public data class ReportDiagnostic(
    val severity: DiagnosticSeverity,
    val category: DiagnosticCategory,
    val code: String,
    val message: String,
    val byteOffset: Long? = null,
    val sectionId: Int? = null,
) {
    init {
        require(code.isNotBlank() && message.isNotBlank()) { "Diagnostic code and message must not be blank." }
        require(byteOffset == null || byteOffset >= 0) { "byteOffset must be non-negative." }
    }
}

public data class ReportFinding(
    val severity: FindingSeverity,
    val category: FindingCategory,
    val code: String,
    val message: String,
    val byteOffset: Long? = null,
) {
    init {
        require(code.isNotBlank() && message.isNotBlank()) { "Finding code and message must not be blank." }
        require(byteOffset == null || byteOffset >= 0) { "byteOffset must be non-negative." }
    }
}

/** One atomically removable workflow unit. [orderKey] establishes the workflow's documented deterministic order. */
public data class WorkflowReportItem(val orderKey: String, val value: JsonObject) {
    internal val frozenValue: JsonObject = freezeJson(value) as JsonObject
}

/**
 * A workflow collection is declared in byte-trimming priority order. Its projection may expand one atomic item into
 * several related report records, such as a component type together with its complete node subtree. Byte-limit
 * search evaluates [project] repeatedly with retained prefixes, so it must be deterministic and side-effect-free;
 * the encoded report byte size for those prefixes must not decrease as retained prefixes grow.
 */
public data class WorkflowReportCollection(
    val id: String,
    val items: List<WorkflowReportItem>,
    val project: (List<WorkflowReportItem>) -> JsonObject,
) {
    internal val frozenItems: List<WorkflowReportItem> = immutableList(items.map { item ->
        WorkflowReportItem(item.orderKey, item.frozenValue)
    })

    init { require(id.isNotBlank()) { "Workflow collection id must not be blank." } }
}

public data class WorkflowReportBody(
    val fields: JsonObject = JsonObject(emptyMap()),
    val collections: List<WorkflowReportCollection> = emptyList(),
) {
    internal val frozenFields: JsonObject = freezeJson(fields) as JsonObject
    internal val frozenCollections: List<WorkflowReportCollection> = immutableList(collections.toList())

    init {
        require(frozenCollections.map { it.id }.distinct().size == frozenCollections.size) {
            "Workflow collection ids must be unique."
        }
    }
}

public data class WasmToolsReportRequest(
    val productVersion: String,
    val workflow: WasmToolsWorkflow,
    val input: WasmToolsReportInput,
    val body: WorkflowReportBody,
    val diagnostics: List<ReportDiagnostic> = emptyList(),
    val findings: List<ReportFinding> = emptyList(),
    val limitations: List<String> = emptyList(),
    val format: ToolOutputFormat = ToolOutputFormat.JSON,
    val maxItems: Int = WASM_TOOLS_DEFAULT_MAX_ITEMS,
    val maxOutputBytes: Long? = null,
    val preOmittedWorkflowItems: Int = 0,
    val preOmittedDiagnostics: Int = 0,
) {
    internal val frozenBody: WorkflowReportBody = WorkflowReportBody(body.frozenFields, body.frozenCollections)
    internal val frozenDiagnostics: List<ReportDiagnostic> = immutableList(diagnostics.toList())
    internal val frozenFindings: List<ReportFinding> = immutableList(findings.toList())
    internal val frozenLimitations: List<String> = immutableList(limitations.toList())

    init {
        require(productVersion.isNotBlank()) { "productVersion must not be blank." }
        require(maxItems > 0) { "maxItems must be positive." }
        require(maxOutputBytes == null || maxOutputBytes >= 0) { "maxOutputBytes must be non-negative." }
        require(preOmittedWorkflowItems >= 0 && preOmittedDiagnostics >= 0) { "Pre-omitted counts must be non-negative." }
        require(frozenLimitations.all { it.isNotBlank() }) { "limitations must not contain blank values." }
        require(frozenFindings.none { it.code == OUTPUT_TRUNCATED_CODE }) {
            "output_truncated is reserved for renderer-generated truncation findings."
        }
    }
}

public sealed interface WasmToolsReportRenderResult {
    public val exitCode: Int

    public data class Rendered(
        val output: RenderedToolOutput.Text,
        val semanticReport: JsonObject,
        override val exitCode: Int,
    ) : WasmToolsReportRenderResult

    public data object OutputLimitRefused : WasmToolsReportRenderResult { override val exitCode: Int = 3 }
    public data class InputRefused(val message: String) : WasmToolsReportRenderResult { override val exitCode: Int = 3 }
    public data class UsageError(val message: String) : WasmToolsReportRenderResult { override val exitCode: Int = 2 }
    public data object InternalFailure : WasmToolsReportRenderResult { override val exitCode: Int = 70 }
}

public object WasmToolsReportRenderer {
    private val compactJson = Json { prettyPrint = false }
    private val prettyJson = Json { prettyPrint = true }
    private val commonFields = setOf("product", "report", "input", "completeness", "diagnostics", "findings", "truncation")

    public fun render(request: WasmToolsReportRequest): WasmToolsReportRenderResult {
        require(request.format == ToolOutputFormat.JSON || request.format == ToolOutputFormat.PRETTY) {
            "hexana-wasm-tools supports only json and pretty output."
        }
        return try {
            renderChecked(request)
        }
        catch (_: Exception) {
            WasmToolsReportRenderResult.InternalFailure
        }
    }

    private fun renderChecked(request: WasmToolsReportRequest): WasmToolsReportRenderResult {
        validateWorkflowBody(request.frozenBody)
        val state = NormalizedState(request)
        var cachedStateVersion = -1L
        var cachedRendered: WasmToolsReportRenderResult.Rendered? = null
        fun rendered(): WasmToolsReportRenderResult.Rendered {
            if (cachedStateVersion == state.version) return checkNotNull(cachedRendered)
            val semantic = state.buildSemanticReport()
            return WasmToolsReportRenderResult.Rendered(
                output = RenderedToolOutput.Text(encode(semantic, request.format), JSON_CONTENT_TYPE),
                semanticReport = semantic,
                exitCode = if (state.isPartial()) 1 else 0,
            ).also {
                cachedStateVersion = state.version
                cachedRendered = it
            }
        }
        fun fitsByteLimit(report: WasmToolsReportRenderResult.Rendered): Boolean {
            return report.output.text.toByteArray(StandardCharsets.UTF_8).size <= request.maxOutputBytes!!
        }
        val initial = rendered()
        if (request.maxOutputBytes == null || fitsByteLimit(initial)) return initial
        if (!state.beginByteLimiting()) return WasmToolsReportRenderResult.OutputLimitRefused
        val limited = rendered()
        if (fitsByteLimit(limited)) return limited
        if (!state.trimForByteLimit { fitsByteLimit(rendered()) }) return WasmToolsReportRenderResult.OutputLimitRefused
        return rendered()
    }

    private fun encode(value: JsonObject, format: ToolOutputFormat): String {
        val json = if (format == ToolOutputFormat.JSON) compactJson else prettyJson
        return json.encodeToString(JsonElement.serializer(), value).trimEnd('\n') + "\n"
    }

    private fun validateWorkflowBody(body: WorkflowReportBody) {
        val names = body.fields.keys
        require("payload" !in names) { "Workflow fields must be merged at the report top level, not placed in payload." }
        require(names.none { it in commonFields }) { "Workflow fields must not replace common envelope fields." }
    }

    private class NormalizedState(private val request: WasmToolsReportRequest) {
        private val diagnostics = request.frozenDiagnostics.sortedWith(diagnosticComparator).toMutableList()
        private val findings = request.frozenFindings.toMutableList()
        private val collections = request.frozenBody.frozenCollections.map { collection ->
            collection to collection.frozenItems.sortedWith(workflowItemComparator).toMutableList()
        }
        private var omittedDiagnostics = request.preOmittedDiagnostics
        private var omittedFindings = 0
        private var omittedWorkflowItems = request.preOmittedWorkflowItems
        private var itemLimited = omittedWorkflowItems > 0 || omittedDiagnostics > 0
        private var byteLimited = false
        var version = 0L
            private set

        init { applyItemLimit() }

        private fun applyItemLimit() {
            collections.forEach { (_, items) -> trim(items, request.maxItems) { omittedWorkflowItems += it } }
            trim(diagnostics, request.maxItems) { omittedDiagnostics += it }
            val otherCollectionsTrimmed = omittedWorkflowItems > 0 || omittedDiagnostics > 0
            val findingsExceeded = findings.size > request.maxItems
            if (otherCollectionsTrimmed || findingsExceeded) {
                trim(findings, request.maxItems - 1) { omittedFindings += it }
                itemLimited = true
            }
        }

        fun beginByteLimiting(): Boolean {
            if (byteLimited) return false
            byteLimited = true
            if (!itemLimited) {
                trim(findings, request.maxItems - 1) { omittedFindings += it }
            }
            version++
            return true
        }

        private fun <T> trim(items: MutableList<T>, retained: Int, record: (Int) -> Unit) {
            if (items.size <= retained) return
            record(items.size - retained)
            items.subList(retained, items.size).clear()
            version++
        }

        fun trimForByteLimit(fits: () -> Boolean): Boolean {
            collections.forEach { (_, items) ->
                if (trimToFittingPrefix(items, { omittedWorkflowItems }, { omittedWorkflowItems = it }, fits)) return true
            }
            if (trimToFittingPrefix(findings, { omittedFindings }, { omittedFindings = it }, fits)) return true
            if (trimToFittingPrefix(diagnostics, { omittedDiagnostics }, { omittedDiagnostics = it }, fits)) return true
            return false
        }

        private fun <T> trimToFittingPrefix(
            items: MutableList<T>,
            omitted: () -> Int,
            setOmitted: (Int) -> Unit,
            fits: () -> Boolean,
        ): Boolean {
            if (items.isEmpty()) return false
            val originalItems = items.toList()
            val initialOmitted = omitted()
            fun retain(count: Int) {
                if (items.size == count) return
                items.clear()
                items.addAll(originalItems.subList(0, count))
                setOmitted(initialOmitted + originalItems.size - count)
                version++
            }

            retain(0)
            if (!fits()) return false
            var fitting = 0
            var notFitting = originalItems.size
            while (notFitting - fitting > 1) {
                val candidate = (fitting + notFitting) / 2
                retain(candidate)
                if (fits()) fitting = candidate else notFitting = candidate
            }
            retain(fitting)
            return true
        }

        fun isPartial(): Boolean = itemLimited || byteLimited || diagnostics.isNotEmpty() || findings.any { it.severity != FindingSeverity.INFO }

        fun buildSemanticReport(): JsonObject {
            val truncated = itemLimited || byteLimited
            val workflowFields = linkedMapOf<String, JsonElement>()
            request.frozenBody.frozenFields.forEach { (key, value) -> workflowFields[key] = canonical(value) }
            collections.forEach { (collection, items) ->
                collection.project(immutableList(items)).forEach { (key, value) ->
                    require(key !in commonFields && key != "payload" && key !in workflowFields) { "Workflow projections must create unique top-level fields." }
                    workflowFields[key] = canonical(value)
                }
            }
            return buildJsonObject {
                put("product", buildJsonObject { put("id", WASM_TOOLS_PRODUCT_ID); put("version", request.productVersion) })
                put("report", buildJsonObject { put("workflow", request.workflow.wireName); put("schema_version", 1) })
                put("input", buildJsonObject {
                    put("kind", request.input.kind.wireName)
                    put("path", request.input.path?.let(::JsonPrimitive) ?: JsonNull)
                    put("byte_count", request.input.byteCount)
                })
                put("completeness", buildJsonObject {
                    put("status", if (isPartial()) "partial" else "complete")
                    put("limitations", JsonArray(request.frozenLimitations.distinct().sortedWith(codePointLexicalComparator).map(::JsonPrimitive)))
                })
                put("diagnostics", JsonArray(diagnostics.map { diagnostic -> diagnostic.toJson() }))
                put("findings", JsonArray((findings + if (truncated) listOf(truncationFinding) else emptyList()).map { finding -> finding.toJson() }))
                put("truncation", buildJsonObject {
                    put("truncated", truncated)
                    put("causes", JsonArray(buildList {
                        if (itemLimited) add(JsonPrimitive("max_items"))
                        if (byteLimited) add(JsonPrimitive("max_output_bytes"))
                    }))
                    put("omitted", buildJsonObject {
                        put("diagnostics", omittedDiagnostics)
                        put("findings", omittedFindings)
                        put("workflow_items", omittedWorkflowItems)
                    })
                })
                workflowFields.entries.sortedWith { left, right -> codePointLexicalComparator.compare(left.key, right.key) }
                    .forEach { (key, value) -> put(key, value) }
            }
        }
    }

    private fun ReportDiagnostic.toJson(): JsonObject = buildJsonObject {
        put("severity", severity.wireName); put("category", category.wireName); put("code", code); put("message", message)
        put("byte_offset", byteOffset?.let(::JsonPrimitive) ?: JsonNull); put("section_id", sectionId?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun ReportFinding.toJson(): JsonObject = buildJsonObject {
        put("severity", severity.wireName); put("category", category.wireName); put("code", code); put("message", message)
        put("byte_offset", byteOffset?.let(::JsonPrimitive) ?: JsonNull)
    }

    private val truncationFinding = ReportFinding(
        severity = FindingSeverity.WARNING,
        category = FindingCategory.RESOURCE_LIMIT,
        code = OUTPUT_TRUNCATED_CODE,
        message = "Output was truncated by report limits.",
    )

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.entries.sortedWith { left, right -> codePointLexicalComparator.compare(left.key, right.key) }
            .associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }

    public const val JSON_CONTENT_TYPE: String = "application/json; charset=utf-8"

    private val diagnosticComparator = Comparator<ReportDiagnostic> { left, right ->
        compareValues(left.byteOffset == null, right.byteOffset == null)
            .takeUnless { it == 0 }
            ?: compareValues(left.byteOffset, right.byteOffset)
    }

    private val workflowItemComparator = Comparator<WorkflowReportItem> { left, right ->
        codePointLexicalComparator.compare(left.orderKey, right.orderKey)
    }

    private val codePointLexicalComparator = Comparator<String> { left, right ->
        var leftIndex = 0
        var rightIndex = 0
        while (leftIndex < left.length && rightIndex < right.length) {
            val leftCodePoint = left.codePointAt(leftIndex)
            val rightCodePoint = right.codePointAt(rightIndex)
            if (leftCodePoint != rightCodePoint) return@Comparator leftCodePoint.compareTo(rightCodePoint)
            leftIndex += Character.charCount(leftCodePoint)
            rightIndex += Character.charCount(rightCodePoint)
        }
        left.length.compareTo(right.length)
    }
}
