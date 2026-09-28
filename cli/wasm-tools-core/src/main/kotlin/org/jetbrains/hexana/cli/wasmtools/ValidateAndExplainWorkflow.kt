package org.jetbrains.hexana.cli.wasmtools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.model.features.EnabledFeatureSourcesResult
import org.jetbrains.hexana.mcp.model.features.EnabledFeaturesResult
import org.jetbrains.hexana.mcp.model.runtime.CheckRuntimeCompatibilityResult
import org.jetbrains.hexana.mcp.model.runtime.ProposalEvidenceResult
import org.jetbrains.hexana.mcp.model.runtime.RequiredProposalResult
import org.jetbrains.hexana.mcp.model.structural.IdentifiedStructuralParseResult
import org.jetbrains.hexana.mcp.model.structural.StructuralKind
import org.jetbrains.hexana.mcp.model.structural.StructuralParseStatus
import org.jetbrains.hexana.mcp.model.structural.UnidentifiedStructuralParseResult
import org.jetbrains.hexana.mcp.query.DuplicateRuntimeTargetsException
import org.jetbrains.hexana.mcp.query.readValidateAndExplainEvidence

/** Composes one already-selected Wasm byte source; source resolution and option parsing belong to the product. */
public fun validateAndExplain(
    bytes: ByteArray,
    input: WasmToolsReportInput,
    productVersion: String,
    runtimeTargets: List<String> = emptyList(),
    format: ToolOutputFormat = ToolOutputFormat.JSON,
    maxItems: Int = WASM_TOOLS_DEFAULT_MAX_ITEMS,
    maxOutputBytes: Long? = null,
): WasmToolsReportRenderResult {
    require(input.byteCount == bytes.size.toLong()) { "Input byte count must match the selected source." }
    if (format != ToolOutputFormat.JSON && format != ToolOutputFormat.PRETTY) {
        return WasmToolsReportRenderResult.UsageError("Only json and pretty formats are supported.")
    }
    if (maxItems <= 0 || (maxOutputBytes != null && maxOutputBytes <= 0)) {
        return WasmToolsReportRenderResult.UsageError("Report limits must be positive.")
    }
    val evidence = try {
        bytes.readValidateAndExplainEvidence(runtimeTargets)
    } catch (error: HexanaAnalysisException) {
        return if (error.code == HexanaAnalysisErrorCode.INVALID_INPUT) {
            WasmToolsReportRenderResult.UsageError(error.message.orEmpty())
        } else {
            WasmToolsReportRenderResult.InternalFailure
        }
    } catch (error: DuplicateRuntimeTargetsException) {
        return WasmToolsReportRenderResult.UsageError(error.message.orEmpty())
    } catch (_: Exception) {
        return WasmToolsReportRenderResult.InternalFailure
    }

    val structural = when (val result = evidence.structural) {
        is IdentifiedStructuralParseResult -> result
        is UnidentifiedStructuralParseResult -> return WasmToolsReportRenderResult.InputRefused(
            "Wasm header was not identified: ${result.failure.kind.name.lowercase()}.",
        )
    }
    val core = evidence.core
    val diagnostics = buildList {
        structural.sectionScanFailures.forEach { failure ->
            add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.SECTION_SCAN,
                "section_scan_failure", failure.message, failure.byteOffset.toLong()))
        }
        structural.parseProblems.forEach { problem ->
            add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.SECTION_DECODE,
                "parse_problem", problem.message, problem.byteOffset.toLong(), problem.sectionId))
        }
        structural.unresolvedSections.forEach { section ->
            val message = "Section ${section.sectionName} (${section.byteSize} bytes) could not be decoded."
            add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.SECTION_DECODE,
                "unresolved_section", message, section.byteOffset.toLong(), section.sectionId))
        }
        evidence.featureExtractionFailure?.let { message ->
            add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.SECTION_DECODE,
                "feature_extraction_failure", "Core feature extraction could not complete: $message"))
        }
    }
    val findings = buildList {
        if (structural.status == StructuralParseStatus.PARTIAL) {
            add(ReportFinding(FindingSeverity.WARNING, FindingCategory.STRUCTURAL_PARSE,
                "structural_parse_partial", "Structural parsing was incomplete."))
        }
        core?.runtimeCompatibility?.filterNot { it.compatible }?.sortedWith(compareBy(codePointOrder) { it.runtime })?.forEach { result ->
            add(ReportFinding(FindingSeverity.WARNING, FindingCategory.RUNTIME_COMPATIBILITY,
                "runtime_compatibility_unconfirmed", "Compatibility with ${result.runtime} was not established."))
        }
    }

    return WasmToolsReportRenderer.render(WasmToolsReportRequest(
        productVersion = productVersion,
        workflow = WasmToolsWorkflow.VALIDATE_AND_EXPLAIN,
        input = input,
        body = WorkflowReportBody(
            fields = buildJsonObject {
                put("summary", Json.encodeToJsonElement(structural.summary))
                put("semantic_validation", "not_performed")
            },
            collections = listOf(
                structuralCollection(structural),
                featureCollection(core?.enabledFeatures?.features, core?.enabledFeatures?.sources,
                    core?.requiredProposals?.proposals.orEmpty(), structural.summary.kind),
                runtimeCollection(structural.summary.kind, structural.status, runtimeTargets,
                    core?.runtimeCompatibility.orEmpty()),
            ),
        ),
        diagnostics = diagnostics,
        findings = findings,
        limitations = buildList {
            add("WebAssembly semantic validation was not performed.")
            if (structural.summary.kind == StructuralKind.CORE_MODULE &&
                structural.status == StructuralParseStatus.PARTIAL) {
                add(if (core == null) {
                    "Core feature and proposal extraction was unavailable because parsing was incomplete."
                } else {
                    "Feature and proposal evidence may be incomplete because structural parsing was partial."
                })
                if (runtimeTargets.isNotEmpty()) {
                    add("Runtime compatibility was not evaluated because structural parsing was partial.")
                }
            }
            if (core?.runtimeCompatibility?.isNotEmpty() == true) {
                add("Runtime compatibility uses a conservative proposal matrix; it does not guarantee execution or " +
                    "deployment for a specific runtime version, flags, or environment.")
            }
        },
        format = format,
        maxItems = maxItems,
        maxOutputBytes = maxOutputBytes,
    ))
}

private fun structuralCollection(structural: IdentifiedStructuralParseResult): WorkflowReportCollection {
    val scanItems = structural.sectionScanFailures.mapIndexed { index, failure ->
        WorkflowReportItem(orderKey(failure.byteOffset, 0, index), buildJsonObject {
            put("source", "scan"); put("record", Json.encodeToJsonElement(failure))
        })
    }
    val problemItems = structural.parseProblems.mapIndexed { index, problem ->
        WorkflowReportItem(orderKey(problem.byteOffset, 1, index), buildJsonObject {
            put("source", "problem"); put("record", Json.encodeToJsonElement(problem))
        })
    }
    return WorkflowReportCollection("structural_parse", scanItems + problemItems) { retained ->
        buildJsonObject {
            put("structural_parse", buildJsonObject {
                put("status", if (structural.status == StructuralParseStatus.CLEAN) "clean" else "partial")
                put("section_scan_failures", JsonArray(retained.filter { it.value["source"] == JsonPrimitive("scan") }
                    .map { it.value.getValue("record") }))
                put("parse_problems", JsonArray(retained.filter { it.value["source"] == JsonPrimitive("problem") }
                    .map { it.value.getValue("record") }))
            })
        }
    }
}

private fun featureCollection(
    features: EnabledFeaturesResult?,
    sources: EnabledFeatureSourcesResult?,
    proposals: List<RequiredProposalResult>,
    kind: StructuralKind,
): WorkflowReportCollection = WorkflowReportCollection(
    "required_proposals",
    proposals.map { proposal -> WorkflowReportItem(proposal.proposal, proposal.toReportJson()) },
) { retained ->
    buildJsonObject {
        put("features_and_proposals", buildJsonObject {
            put("stage", when {
                kind == StructuralKind.COMPONENT || features == null -> "not_applicable"
                else -> "completed"
            })
            put("enabled_features", features?.toReportJson() ?: JsonNull)
            put("enabled_feature_sources", sources?.toReportJson() ?: JsonNull)
            put("required_proposals", JsonArray(retained.map { it.value }))
        })
    }
}

private fun runtimeCollection(
    kind: StructuralKind,
    structuralStatus: StructuralParseStatus,
    requestedTargets: List<String>,
    results: List<CheckRuntimeCompatibilityResult>,
): WorkflowReportCollection = WorkflowReportCollection(
    "runtime_compatibility",
    when {
        kind == StructuralKind.COMPONENT -> emptyList()
        structuralStatus == StructuralParseStatus.PARTIAL -> requestedTargets.map { runtime ->
            val normalized = runtime.trim().lowercase()
            WorkflowReportItem(normalized, buildJsonObject { put("runtime", normalized) })
        }
        else -> results.map { result -> WorkflowReportItem(result.runtime, result.toReportJson()) }
    },
) { retained ->
    buildJsonObject {
        put("runtime_compatibility", buildJsonObject {
            put("stage", when {
                kind == StructuralKind.COMPONENT -> "not_applicable"
                requestedTargets.isEmpty() -> "not_requested"
                structuralStatus == StructuralParseStatus.PARTIAL -> "not_applicable"
                else -> "completed"
            })
            put("targets", JsonArray(retained.map { item -> buildJsonObject {
                put("runtime", item.value.getValue("runtime"))
            } }))
            put("results", JsonArray(if (structuralStatus == StructuralParseStatus.PARTIAL) emptyList() else retained.map { it.value }))
        })
    }
}

private fun EnabledFeaturesResult.toReportJson(): JsonObject = buildJsonObject {
    put("reference_types", referenceTypes); put("bulk_memory", bulkMemory); put("multi_value", multiValue)
    put("sign_extension", signExtension); put("simd", simd); put("threads", threads)
    put("memory64", memory64); put("gc_types", gcTypes); put("subtyping", subtyping)
    put("function_references", functionReferences); put("extended_const", extendedConst)
}

private fun EnabledFeatureSourcesResult.toReportJson(): JsonObject = buildJsonObject {
    put("sections", sortedStrings(sections)); put("instructions", sortedStrings(instructions))
    put("custom_sections", sortedStrings(customSections))
}

private fun RequiredProposalResult.toReportJson(): JsonObject = buildJsonObject {
    put("proposal", proposal); put("required", required); put("evidence", evidence.toReportJson())
}

private fun ProposalEvidenceResult.toReportJson(): JsonObject = buildJsonObject {
    put("feature_flag", featureFlag); put("sections", sortedStrings(sections))
    put("instructions", sortedStrings(instructions)); put("custom_sections", sortedStrings(customSections))
    put("notes", JsonArray(notes.map(::JsonPrimitive)))
}

private fun CheckRuntimeCompatibilityResult.toReportJson(): JsonObject = buildJsonObject {
    put("runtime", runtime); put("compatible", compatible)
    put("required_proposals", sortedStrings(requiredProposals))
    put("unsupported_proposals", sortedStrings(unsupportedProposals))
    put("unknown_proposals", sortedStrings(unknownProposals))
    put("proposal_statuses", JsonArray(proposalStatuses.sortedWith(compareBy(codePointOrder) { it.proposal }).map { status -> buildJsonObject {
        put("proposal", status.proposal); put("status", status.status); put("note", status.note)
        put("evidence", status.evidence.toReportJson())
    } }))
}

private fun sortedStrings(values: List<String>): JsonArray = JsonArray(values.sortedWith(codePointOrder).map(::JsonPrimitive))

private val codePointOrder = Comparator<String> { left, right ->
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

private fun orderKey(offset: Int, type: Int, encounter: Int): String =
    "%010d:%d:%010d".format(java.util.Locale.ROOT, offset, type, encounter)
