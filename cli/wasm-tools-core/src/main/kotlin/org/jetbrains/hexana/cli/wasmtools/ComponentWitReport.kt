package org.jetbrains.hexana.cli.wasmtools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.mcp.DefaultHexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.model.components.CheckComponentCompatibilityExplicitResult
import org.jetbrains.hexana.mcp.model.components.ComponentSurfaceEntryResult
import org.jetbrains.hexana.mcp.model.components.ComponentTypeGraphResult
import org.jetbrains.hexana.mcp.model.components.ComponentTypeGraphStage
import org.jetbrains.hexana.mcp.model.components.ComponentTypeNodeKind
import org.jetbrains.hexana.mcp.model.components.ComponentTypeItemResult
import org.jetbrains.hexana.mcp.model.components.ComponentTypeNodeResult
import org.jetbrains.hexana.mcp.model.components.ListComponentSurfaceResult
import java.nio.file.Path

private const val WIT_REASON = "Binary component evidence cannot reconstruct authoritative WIT source, package, or world."
private val graphJson = Json { explicitNulls = false }

/** Composes the structural workflow after the caller has selected and bounded exactly one input. */
public object ComponentWitReport {
    public fun compose(
        input: WasmInput,
        reportInput: WasmToolsReportInput,
        productVersion: String,
        dependencyPaths: List<String> = emptyList(),
        service: HexanaAnalysisService = DefaultHexanaAnalysisService(),
        format: ToolOutputFormat = ToolOutputFormat.JSON,
        maxItems: Int = WASM_TOOLS_DEFAULT_MAX_ITEMS,
        maxOutputBytes: Long? = HexanaAnalysisLimits.MAX_OUTPUT_BYTES.toLong(),
    ): WasmToolsReportRequest {
        val paths = dependencyPaths.map { path ->
            runCatching { Path.of(path).normalize().toString() }.getOrDefault(path)
        }
        require(paths.distinct().size == paths.size) { "Dependency paths must be distinct." }

        val structure = service.componentWitStructure(input)
        val isComponent = structure.surface.isComponent
        val graph = structure.types
        check(isComponent == (graph.stage == ComponentTypeGraphStage.COMPLETED)) { "Component surface and type applicability disagree." }
        val compatibility = if (isComponent && paths.isNotEmpty()) {
            service.checkComponentCompatibilityExplicit(input, paths).also {
                check(it.directoryScanCount == 0) { "Explicit compatibility scanned a directory." }
                val classifiedPaths = it.acceptedProviders.map { provider -> provider.dependencyPath } +
                    it.rejectedDependencies.map { rejected -> rejected.dependencyPath }
                check(classifiedPaths.size == paths.size && classifiedPaths.toSet() == paths.toSet()) {
                    "Explicit compatibility did not classify every dependency."
                }
            }
        } else null

        val findings = mutableListOf<ReportFinding>()
        val limitations = mutableListOf<String>()
        if (structure.surface.imports.size < structure.surface.summary.importCount ||
            structure.surface.exports.size < structure.surface.summary.exportCount) {
            findings += ReportFinding(FindingSeverity.WARNING, FindingCategory.COMPONENT_SURFACE,
                "component_surface_limit", "Component surface entries exceed the analysis candidate limit.")
            limitations += "component_surface_limit"
        }
        graph.nodes.filter { it.kind == ComponentTypeNodeKind.OMITTED }.mapNotNull { it.attributes.reason }.distinct().forEach { reason ->
            findings += ReportFinding(FindingSeverity.WARNING, FindingCategory.COMPONENT_SURFACE, reason,
                "Component type graph contains omitted nodes: $reason.")
            limitations += reason
        }
        compatibility?.issues?.forEach { issue ->
            findings += ReportFinding(
                if (issue.severity == "error") FindingSeverity.ERROR else FindingSeverity.WARNING,
                FindingCategory.COMPONENT_DEPENDENCY,
                issue.reason,
                "Import ${issue.importName}: ${issue.reason}.",
            )
        }
        compatibility?.rejectedDependencies?.forEach { rejected ->
            findings += ReportFinding(
                FindingSeverity.ERROR, FindingCategory.COMPONENT_DEPENDENCY,
                rejected.reason, "Dependency ${rejected.dependencyPath}: ${rejected.message}",
            )
        }

        return WasmToolsReportRequest(
            productVersion = productVersion,
            workflow = WasmToolsWorkflow.COMPONENT_WIT_REPORT,
            input = reportInput,
            body = WorkflowReportBody(
                fields = buildJsonObject {
                    put("wit", buildJsonObject { put("status", "not_available"); put("reason", WIT_REASON) })
                },
                collections = listOf(
                    surfaceCollection(structure.surface, structure.layer),
                    typeCollection(graph),
                    dependencyCollection(isComponent, paths, compatibility),
                ),
            ),
            findings = findings,
            limitations = limitations,
            format = format,
            maxItems = maxItems,
            maxOutputBytes = maxOutputBytes,
        )
    }
}

private fun surfaceCollection(surface: ListComponentSurfaceResult, layer: Int?): WorkflowReportCollection {
    val imports = surface.imports.map { entry -> WorkflowReportItem("0\u0000${entry.name}\u0000${entry.version}\u0000${entry.sort.orEmpty()}", entry.toJson()) }
    val exports = surface.exports.map { entry -> WorkflowReportItem("1\u0000${entry.name}\u0000${entry.version}\u0000${entry.sort.orEmpty()}", entry.toJson()) }
    return WorkflowReportCollection("component_surface", imports + exports) { retained ->
        buildJsonObject {
            put("component", buildJsonObject {
                put("is_component", surface.isComponent)
                put("layer", layer?.let(::JsonPrimitive) ?: JsonNull)
                put("imports", JsonArray(retained.filter { it.orderKey.startsWith("0\u0000") }.map { it.value }))
                put("exports", JsonArray(retained.filter { it.orderKey.startsWith("1\u0000") }.map { it.value }))
                put("summary", buildJsonObject {
                    put("import_count", surface.summary.importCount)
                    put("export_count", surface.summary.exportCount)
                    put("non_runtime_import_count", surface.summary.nonRuntimeImportCount)
                    put("runtime_import_count", surface.summary.runtimeImportCount)
                })
            })
        }
    }
}

private fun ComponentSurfaceEntryResult.toJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("base_name", baseName)
    put("version", version)
    put("sort", sort?.let(::JsonPrimitive) ?: JsonNull)
    put("extern_description", externDescription?.let(::JsonPrimitive) ?: JsonNull)
    put("runtime_provided", runtimeProvided)
}

private fun typeCollection(graph: ComponentTypeGraphResult): WorkflowReportCollection {
    val nodesByRoot = graph.nodes.groupBy { node ->
        val childSeparator = node.nodeId.indexOf('/', "type/".length)
        if (childSeparator < 0) node.nodeId else node.nodeId.substring(0, childSeparator)
    }
    val items = graph.items.map { item ->
        val nodes = nodesByRoot[item.rootNodeId].orEmpty()
        WorkflowReportItem(item.index.toString().padStart(20, '0'), buildJsonObject {
            put("item", item.toJson())
            put("nodes", JsonArray(nodes.map { it.toJson() }))
        })
    }
    return WorkflowReportCollection("component_types", items) { retained ->
        buildJsonObject {
            put("types", buildJsonObject {
                put("stage", graph.stage.name.lowercase())
                put("limits", graph.limits?.let { limits -> buildJsonObject {
                    put("max_depth", limits.maxDepth)
                    put("max_nodes", limits.maxNodes)
                    put("visited_nodes", limits.visitedNodes)
                    put("omitted_nodes", limits.omittedNodes)
                } } ?: JsonNull)
                put("items", JsonArray(retained.map { it.value.getValue("item") }))
                put("nodes", JsonArray(retained.flatMap { (it.value.getValue("nodes") as JsonArray).toList() }
                    .sortedBy { (it as JsonObject).getValue("node_id").jsonPrimitive.content }))
            })
        }
    }
}

private fun ComponentTypeItemResult.toJson(): JsonObject = buildJsonObject {
    put("index", index)
    put("family", family.name.lowercase())
    put("root_node_id", rootNodeId)
}

private fun ComponentTypeNodeResult.toJson(): JsonObject = buildJsonObject {
    put("node_id", nodeId)
    put("parent_node_id", parentNodeId?.let(::JsonPrimitive) ?: JsonNull)
    put("child_role", childRole?.let(::JsonPrimitive) ?: JsonNull)
    put("child_index", childIndex?.let(::JsonPrimitive) ?: JsonNull)
    put("kind", kind.name.lowercase())
    put("attributes", graphJson.encodeToJsonElement(org.jetbrains.hexana.mcp.model.components.ComponentTypeNodeAttributes.serializer(), attributes))
}

private fun dependencyCollection(
    isComponent: Boolean,
    paths: List<String>,
    result: CheckComponentCompatibilityExplicitResult?,
): WorkflowReportCollection {
    val acceptedByPath = result?.acceptedProviders?.associateBy { it.dependencyPath }.orEmpty()
    val rejectedByPath = result?.rejectedDependencies?.associateBy { it.dependencyPath }.orEmpty()
    val items = buildList {
        paths.forEach { path -> add(dependencyItem("0", path, buildJsonObject {
            put("path", path)
            acceptedByPath[path]?.let { put("accepted_provider", buildJsonObject { put("dependency_path", it.dependencyPath) }) }
            rejectedByPath[path]?.let { put("rejected_dependency", buildJsonObject {
                put("dependency_path", it.dependencyPath); put("reason", it.reason); put("message", it.message)
            }) }
        })) }
        if (result == null) return@buildList
        result.matches.forEach { match -> add(dependencyItem("1", match.importName + "\u0000" + match.dependencyPath, buildJsonObject {
            put("import_name", match.importName); put("dependency_path", match.dependencyPath)
            put("export_name", match.exportName); put("exact_version_match", match.exactVersionMatch)
        })) }
        result.missingImports.forEach { missing -> add(dependencyItem("2", missing.importName, buildJsonObject {
            put("name", missing.importName); put("base_name", missing.baseName); put("version", missing.version)
            put("runtime_provided", missing.runtimeProvided)
        })) }
        result.issues.forEachIndexed { index, issue -> add(dependencyItem("3", issue.importName + "\u0000" + index.toString().padStart(10, '0'), buildJsonObject {
            put("import_name", issue.importName); put("dependency_path", issue.dependencyPath?.let(::JsonPrimitive) ?: JsonNull)
            put("severity", issue.severity); put("reason", issue.reason)
            put("expected", issue.expected?.let(::JsonPrimitive) ?: JsonNull)
            put("actual", issue.actual?.let(::JsonPrimitive) ?: JsonNull)
        })) }
        result.candidateProviders.forEachIndexed { index, candidate -> add(dependencyItem("4", candidate.dependencyPath + "\u0000" + index.toString().padStart(10, '0'), buildJsonObject {
            put("dependency_path", candidate.dependencyPath); put("export_name", candidate.exportName)
            put("base_name", candidate.baseName); put("version", candidate.version); put("sort", candidate.sort)
            put("extern_description", candidate.externDescription?.let(::JsonPrimitive) ?: JsonNull)
            put("verdict", candidate.verdict)
        })) }
    }
    return WorkflowReportCollection("explicit_dependencies", items) { retained ->
        fun group(prefix: String): JsonArray = JsonArray(retained.filter { it.orderKey.startsWith("$prefix\u0000") }.map { it.value })
        val retainedPaths = group("0").map { it as JsonObject }
        buildJsonObject {
            put("dependencies", buildJsonObject {
                put("stage", if (!isComponent) "not_applicable" else if (result == null) "not_requested" else "completed")
                put("paths", JsonArray(retainedPaths.map { it.getValue("path") }))
                put("compatibility", result?.let { buildJsonObject {
                    put("compatible", it.compatible && it.rejectedDependencies.isEmpty())
                    put("matches", group("1")); put("missing_imports", group("2")); put("issues", group("3"))
                    put("candidate_providers", group("4"))
                    put("accepted_providers", JsonArray(retainedPaths.mapNotNull { path -> path["accepted_provider"] }))
                    put("rejected_dependencies", JsonArray(retainedPaths.mapNotNull { path -> path["rejected_dependency"] }))
                    put("directory_scan_count", it.directoryScanCount)
                } } ?: JsonNull)
            })
        }
    }
}

private fun dependencyItem(group: String, key: String, value: JsonObject) = WorkflowReportItem("$group\u0000$key", value)
