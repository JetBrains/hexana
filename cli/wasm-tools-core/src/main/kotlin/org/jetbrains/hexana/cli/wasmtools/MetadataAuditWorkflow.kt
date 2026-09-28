package org.jetbrains.hexana.cli.wasmtools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.model.features.ListEnabledFeaturesResult
import org.jetbrains.hexana.mcp.model.metadata.MetadataSectionStatus
import org.jetbrains.hexana.mcp.model.metadata.ProducerSectionReport
import org.jetbrains.hexana.mcp.model.metadata.TargetFeaturesSectionReport
import org.jetbrains.hexana.mcp.query.AuditCustomSection
import org.jetbrains.hexana.mcp.query.MetadataAuditQueryResult
import org.jetbrains.hexana.mcp.query.readMetadataAudit

/** Builds the accepted metadata-audit report from one already selected byte source. */
public object MetadataAuditWorkflow {
    public fun run(
        bytes: ByteArray,
        input: WasmToolsReportInput,
        productVersion: String,
        format: ToolOutputFormat = ToolOutputFormat.JSON,
        maxItems: Int = WASM_TOOLS_DEFAULT_MAX_ITEMS,
        maxOutputBytes: Long? = HexanaAnalysisLimits.MAX_OUTPUT_BYTES.toLong(),
    ): WasmToolsReportRenderResult {
        require(input.byteCount == bytes.size.toLong()) { "Input byte count must match the selected source." }
        require(maxItems > 0) { "maxItems must be positive." }
        val evidence = try {
            readMetadataAudit(bytes, maxItems)
        } catch (_: Exception) {
            return WasmToolsReportRenderResult.InternalFailure
        }
        return when (evidence) {
            is MetadataAuditQueryResult.Unidentified -> WasmToolsReportRenderResult.InputRefused(
                "Wasm header was not identified: ${evidence.reason.lowercase()}.",
            )
            is MetadataAuditQueryResult.Identified -> WasmToolsReportRenderer.render(
                WasmToolsReportRequest(
                    productVersion = productVersion,
                    workflow = WasmToolsWorkflow.METADATA_AUDIT,
                    input = input,
                    body = evidence.toReportBody(),
                    diagnostics = evidence.diagnostics(),
                    findings = evidence.findings(),
                    limitations = if (evidence.providerParseProblemsSaturated) {
                        listOf("parse_problem_limit_reached")
                    } else emptyList(),
                    format = format,
                    maxItems = maxItems,
                    maxOutputBytes = maxOutputBytes,
                    preOmittedWorkflowItems = evidence.omittedCustomSections,
                    preOmittedDiagnostics = evidence.omittedUnresolvedSections + evidence.omittedSectionScanFailures,
                )
            )
        }
    }
}

private fun MetadataAuditQueryResult.Identified.toReportBody(): WorkflowReportBody {
    val producers = typedMetadata.producers.sections.associateBy { it.offsetBytes }
    val targetFeatures = typedMetadata.targetFeatures.sections.associateBy { it.offsetBytes }
    val items = customSections.map { section ->
        WorkflowReportItem(
            orderKey = section.offsetBytes.toString().padStart(20, '0') + ":" + section.name,
            value = buildJsonObject {
                put("custom", section.toJson())
                producers[section.offsetBytes]?.let { put("producers", Json.encodeToJsonElement(ProducerSectionReport.serializer(), it)) }
                targetFeatures[section.offsetBytes]?.let {
                    put("target_features", Json.encodeToJsonElement(TargetFeaturesSectionReport.serializer(), it))
                }
            },
        )
    }
    return WorkflowReportBody(
        fields = buildJsonObject { put("enabled_features", enabledFeatures.toJson(enabledFeaturesIncomplete)) },
        collections = listOf(WorkflowReportCollection("custom_sections", items) { retained ->
            val retainedProducers = retained.mapNotNull { it.value["producers"] }
            val retainedTargetFeatures = retained.mapNotNull { it.value["target_features"] }
            buildJsonObject {
                put("custom_sections", JsonArray(retained.map { it.value.getValue("custom") }))
                put("typed_metadata", buildJsonObject {
                    put("producers", buildJsonObject {
                        put("status", if (retainedProducers.size < typedMetadata.producers.sections.size) {
                            "partial"
                        } else typedMetadata.producers.status.name.lowercase())
                        put("sections", JsonArray(retainedProducers))
                    })
                    put("target_features", buildJsonObject {
                        put("status", if (retainedTargetFeatures.size < typedMetadata.targetFeatures.sections.size) {
                            "partial"
                        } else typedMetadata.targetFeatures.status.name.lowercase())
                        put("sections", JsonArray(retainedTargetFeatures))
                    })
                })
            }
        }),
    )
}

private fun AuditCustomSection.toJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("offset_bytes", offsetBytes)
    put("size_bytes", sizeBytes)
    put("preview", buildJsonObject {
        put("encoding", encoding)
        put("value", preview)
        put("captured_bytes", capturedBytes)
        put("truncated", truncated)
    })
}

private fun ListEnabledFeaturesResult?.toJson(incomplete: Boolean): JsonObject = buildJsonObject {
    if (this@toJson == null) {
        put("stage", if (incomplete) "partial" else "not_applicable")
        put("features", JsonNull)
        put("sources", JsonNull)
    } else {
        put("stage", "completed")
        put("features", buildJsonObject {
            put("reference_types", features.referenceTypes)
            put("bulk_memory", features.bulkMemory)
            put("multi_value", features.multiValue)
            put("sign_extension", features.signExtension)
            put("simd", features.simd)
            put("threads", features.threads)
            put("memory64", features.memory64)
            put("gc_types", features.gcTypes)
            put("subtyping", features.subtyping)
            put("function_references", features.functionReferences)
            put("extended_const", features.extendedConst)
        })
        put("sources", buildJsonObject {
            put("sections", JsonArray(sources.sections.map(::JsonPrimitive)))
            put("instructions", JsonArray(sources.instructions.map(::JsonPrimitive)))
            put("custom_sections", JsonArray(sources.customSections.map(::JsonPrimitive)))
        })
    }
}

private fun MetadataAuditQueryResult.Identified.diagnostics(): List<ReportDiagnostic> = buildList {
    if (providerParseProblemsSaturated) {
        add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.RESOURCE_LIMIT,
            "parse_problem_limit_reached", "The provider reached its 1,000-problem cap; more problems may be omitted.",
            firstProviderParseProblemOffset))
    }
    unresolvedSections.forEach { section ->
        add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.SECTION_DECODE,
            "unresolved_section", section.message, section.byteOffset, section.sectionId))
    }
    typedMetadataBudget?.let { budget ->
        add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.RESOURCE_LIMIT,
            "typed_metadata_budget_exceeded", "Typed metadata budget omitted ${budget.omittedSections} sections.",
            budget.firstOmittedOffset))
    }
    if (enabledFeaturesIncomplete) {
        add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.SECTION_DECODE,
            "enabled_features_incomplete", "Enabled-feature extraction failed; feature evidence is incomplete."))
    }
    sectionScanFailures.forEach { failure ->
        add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.SECTION_SCAN,
            "section_scan_failed", failure.message, failure.byteOffset))
    }
    parseProblems.forEach { problem ->
        add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.SECTION_DECODE,
            problem.kind, problem.message, problem.byteOffset, problem.sectionId))
    }
    typedMetadata.producers.sections.forEach { section ->
        if (section.status == MetadataSectionStatus.MALFORMED) {
            section.diagnostics.forEach { diagnostic ->
                add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.METADATA_DECODE,
                    "malformed_producers", diagnostic.message, diagnostic.byteOffset ?: section.offsetBytes, 0))
            }
        }
    }
    typedMetadata.targetFeatures.sections.forEach { section ->
        if (section.status == MetadataSectionStatus.MALFORMED) {
            section.diagnostics.forEach { diagnostic ->
                add(ReportDiagnostic(DiagnosticSeverity.ERROR, DiagnosticCategory.METADATA_DECODE,
                    "malformed_target_features", diagnostic.message, diagnostic.byteOffset ?: section.offsetBytes, 0))
            }
        }
    }
}

private fun MetadataAuditQueryResult.Identified.findings(): List<ReportFinding> = buildList {
    typedMetadataBudget?.let { budget ->
        add(ReportFinding(FindingSeverity.WARNING, FindingCategory.RESOURCE_LIMIT,
            "typed_metadata_budget_exceeded", "Typed metadata decoding was limited.", budget.firstOmittedOffset))
    }
    typedMetadata.producers.sections.filter { it.status == MetadataSectionStatus.MALFORMED }.forEach { section ->
        add(ReportFinding(FindingSeverity.WARNING, FindingCategory.METADATA,
            "malformed_producers", "A producers occurrence could not be decoded.", section.offsetBytes))
    }
    typedMetadata.targetFeatures.sections.filter { it.status == MetadataSectionStatus.MALFORMED }.forEach { section ->
        add(ReportFinding(FindingSeverity.WARNING, FindingCategory.METADATA,
            "malformed_target_features", "A target_features occurrence could not be decoded.", section.offsetBytes))
    }
}
