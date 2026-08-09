package org.jetbrains.hexana.webapp.worker

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.componentExternDescriptionLabel
import org.jetbrains.hexana.shared.componentSortIndexLabel
import org.jetbrains.hexana.shared.exportKindLabel
import org.jetbrains.hexana.shared.hasWasiCliRunExport
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.components.ComponentInstanceExpression
import org.jetbrains.hexana.wasm.components.InlineInstanceCoreExpression
import org.jetbrains.hexana.wasm.components.InlineInstanceExpression
import org.jetbrains.hexana.wasm.components.ModuleInstanceCoreExpression
import org.jetbrains.hexana.webapp.ParserWorkerComponentGraph
import org.jetbrains.hexana.webapp.ParserWorkerComponentGraphEdge
import org.jetbrains.hexana.webapp.ParserWorkerComponentGraphNode
import org.jetbrains.hexana.webapp.ParserWorkerDiagnostic
import org.jetbrains.hexana.webapp.ParserWorkerPresentation
import org.jetbrains.hexana.webapp.ParserWorkerRange
import org.jetbrains.hexana.webapp.ParserWorkerRecord
import org.jetbrains.hexana.webapp.ParserWorkerSummary
import org.jetbrains.hexana.webapp.ParserWorkerTable
import org.jetbrains.hexana.webapp.ParserWorkerToolbar
import org.jetbrains.hexana.webapp.componentGraphSourceId
import org.jetbrains.hexana.webapp.encodeBoundedParserWorkerSuccess
import org.jetbrains.hexana.webapp.isValidParserWorkerRequestId
import org.jetbrains.hexana.webapp.PARSER_WORKER_PROTOCOL_VERSION
import org.jetbrains.hexana.webapp.sanitizeParserWorkerPresentation
import org.khronos.webgl.ArrayBuffer

private const val SCOPE_MARKER = "DedicatedWorkerGlobalScope"

fun main() {
    val workerScope = js("self")
    val isDedicatedWorker = js(
        "typeof DedicatedWorkerGlobalScope !== 'undefined' && self instanceof DedicatedWorkerGlobalScope"
    ).unsafeCast<Boolean>()
    val documentAbsent = js("typeof document === 'undefined'").unsafeCast<Boolean>()

    workerScope.onmessage = { event: dynamic ->
        val request = event.data
        val plainRequest = isPlainObject(request)
        val rawRequestId = if (plainRequest) request.requestId as? String else null
        val requestId = rawRequestId?.takeIf(::isValidParserWorkerRequestId).orEmpty()
        if (!isDedicatedWorker || !documentAbsent) {
            postError(workerScope, requestId, "INVALID_SCOPE", "Parser worker scope is unavailable.")
        } else if (
            !plainRequest ||
            request.protocolVersion != PARSER_WORKER_PROTOCOL_VERSION ||
            request.kind != "parse" ||
            !isValidParserWorkerRequestId(rawRequestId ?: "") ||
            !js("request.buffer instanceof ArrayBuffer").unsafeCast<Boolean>()
        ) {
            postError(workerScope, requestId, "INVALID_REQUEST", "Parser worker request is invalid.")
        } else {
            parse(workerScope, requestId, request.buffer.unsafeCast<ArrayBuffer>())
        }
    }
}

private fun isPlainObject(value: dynamic): Boolean =
    value != null && js("Object.prototype.toString.call(value) === '[object Object]'").unsafeCast<Boolean>()

private fun parse(workerScope: dynamic, requestId: String, input: ArrayBuffer) {
    val startedAt = now()
    try {
        val bytes = input.toCommonByteBuffer()
        val presentation = when (WasmBinaryProviderSelector.select(bytes.duplicate())) {
            BinaryProviderType.Wasm -> corePresentation(WasmBinaryProvider(bytes), input.byteLength, startedAt)
            BinaryProviderType.WasmComponent -> componentPresentation(WasmComponentBinaryProvider(bytes), input.byteLength, startedAt)
            BinaryProviderType.Generic -> null
        } ?: return postError(workerScope, requestId, "PARSE_FAILED", "The input is not a supported WebAssembly binary.")
        val sanitized = sanitizeParserWorkerPresentation(presentation)
        workerScope.postMessage(
            encodeBoundedParserWorkerSuccess(requestId, sanitized.presentation, sanitized.omittedRecordCount),
        )
    } catch (_: Throwable) {
        postError(workerScope, requestId, "PARSE_FAILED", "The input could not be parsed safely.")
    }
}

private fun corePresentation(provider: WasmBinaryProvider, inputBytes: Int, startedAt: Double): ParserWorkerPresentation? {
    val module = provider.wasmModule ?: return null
    val imports = provider.imports
    val exports = provider.exports
    val codeOffsets = provider.codeOffsets
    val problems = provider.parseProblems
    val proposals = provider.usedProposals.sortedBy { it.toString() }
    val graalWebImage = provider.graalWebImageInfo
    return ParserWorkerPresentation(
        format = "core_wasm",
        capabilities = listOf("summary", "structural_ranges", "tables", "diagnostics"),
        summary = ParserWorkerSummary(
            SCOPE_MARKER,
            true,
            inputBytes,
            module.sections.size,
            imports.size,
            provider.functions.size,
            codeOffsets.size,
            now() - startedAt,
            exports.size,
            0,
            proposals.size,
            graalWebImage.isWebImage,
            graalWebImage.matchedImportModules.sorted(),
        ),
        ranges = provider.structureRanges.map { range ->
            ParserWorkerRange(range.offset.toInt(), range.size.toInt(), range.label)
        },
        tables = buildList {
            addTable("Imports", listOf("Data"), imports.map { item ->
                row(listOf(if (item.prefix.isEmpty()) item.name else "${item.prefix}.${item.name}"), item.offset, item.size)
            })
            addTable("Exports", listOf("Kind", "Name"), exports.map { item ->
                row(listOf(exportKindLabel(item), item.name), item.offset, item.size)
            })
            addTable("Functions", listOf("Function", "Size"), codeOffsets.mapIndexed { index, item ->
                row(listOf("func ${provider.importFuncCount + index}", "${item.size} bytes"), item.offset, item.size)
            })
            addTable("Problems", listOf("Kind", "Location", "Message"), problems.map { item ->
                val location = item.funcIdx?.let { "func $it" } ?: item.typeIdx?.let { "type $it" } ?: item.sectionName
                row(listOf(item.kind.toString(), location, item.message), item.byteOffset, item.byteSize)
            })
            addTable("Proposals", listOf("Proposal"), proposals.map { item -> row(listOf(item.toString())) })
        },
        diagnostics = problems.map { item -> ParserWorkerDiagnostic(item.kind.toString(), item.message) },
        legacyTabs = coreLegacyTabs(module),
        toolbar = ParserWorkerToolbar(
            runnableExportName = exports.filterIsInstance<WasmFunctionExportRecordEntry>()
                .firstOrNull { it.name == "_start" || it.name == "_initialize" }
                ?.name,
            proposalIds = proposals.map { it.name },
        ),
    )
}

private fun componentPresentation(
    provider: WasmComponentBinaryProvider,
    inputBytes: Int,
    startedAt: Double,
): ParserWorkerPresentation? {
    val component = provider.wasmModule ?: return null
    val imports = provider.imports
    val exports = provider.exports
    val modules = provider.modules
    val graph = componentGraph(provider)
    return ParserWorkerPresentation(
        format = "wasm_component",
        capabilities = listOf("summary", "structural_ranges", "tables", "component_dependency_graph"),
        summary = ParserWorkerSummary(
            SCOPE_MARKER,
            true,
            inputBytes,
            component.sections.size,
            imports.size,
            0,
            0,
            now() - startedAt,
            exports.size,
            modules.size,
            0,
        ),
        ranges = provider.structureRanges.map { range ->
            ParserWorkerRange(range.offset.toInt(), range.size.toInt(), range.label)
        },
        tables = buildList {
            addTable("Imports", listOf("Name", "Extern Description"), imports.map { item ->
                row(listOf(componentName(item.importName, item.version), componentExternDescriptionLabel(item.externDescription)), item.offset, item.size)
            })
            addTable("Exports", listOf("Name", "Sort Index", "Extern Description"), exports.map { item ->
                row(
                    listOf(
                        componentName(item.exportName, item.version),
                        componentSortIndexLabel(item.sortIndex) ?: "",
                        item.externDescription?.let(::componentExternDescriptionLabel) ?: "",
                    ),
                    item.offset,
                    item.size,
                )
            })
            addTable("Modules", listOf("Data"), modules.mapIndexed { index, item ->
                row(listOf("module $index @0x${item.contentOffset.toString(16)}"), item.contentOffset, item.contentSize)
            })
        },
        componentGraph = graph,
        legacyTabs = buildList {
            if (graph.nodes.any { it.kind == "nested_component" }) add("Components")
            add("Wat")
            add("WAT (virtualized)")
        },
        toolbar = ParserWorkerToolbar(
            runnableExportName = if (hasWasiCliRunExport(provider)) "wasi:cli/run" else null,
        ),
    )
}

private fun coreLegacyTabs(module: org.jetbrains.hexana.wasm.WasmModule): List<String> = buildList {
    if (module.dataSection != null) add("Data")
    if (module.exceptionSection != null) {
        add("Tags")
        add("Exceptions")
    }
    if (module.customSections.isNotEmpty()) add("Custom")
    add("Top")
    if (module.codeSection != null && module.getCustomSectionByName("name") != null) add("Monos")
    if (module.codeSection != null) add("Garbage")
    add("Wat")
    add("WAT (virtualized)")
}

private fun MutableList<ParserWorkerTable>.addTable(
    id: String,
    columns: List<String>,
    records: List<ParserWorkerRecord>,
) {
    if (records.isNotEmpty()) add(ParserWorkerTable(id, records, columns))
}

private fun row(values: List<String>, offset: Int? = null, size: Int? = null): ParserWorkerRecord =
    ParserWorkerRecord(values.firstOrNull().orEmpty(), values.getOrElse(1) { values.firstOrNull().orEmpty() }, values, offset, size)

private fun componentGraph(provider: WasmComponentBinaryProvider): ParserWorkerComponentGraph {
    val nodes = mutableListOf<ParserWorkerComponentGraphNode>()
    val edges = mutableListOf<ParserWorkerComponentGraphEdge>()
    provider.imports.forEachIndexed { index, item ->
        nodes += ParserWorkerComponentGraphNode(
            "import:$index",
            "import",
            componentName(item.importName, item.version),
            componentExternDescriptionLabel(item.externDescription),
        )
    }
    provider.modules.forEachIndexed { index, item ->
        nodes += ParserWorkerComponentGraphNode("module:$index", "module", "core module $index", "${item.contentSize} bytes")
    }
    provider.nestedComponents.forEachIndexed { index, _ ->
        nodes += ParserWorkerComponentGraphNode(
            "component:$index",
            "nested_component",
            "component $index",
            "${provider.nestedComponentExports(index).size} exports",
        )
    }
    provider.coreInstances.forEachIndexed { index, instance ->
        val id = "core-instance:$index"
        when (instance) {
            is ModuleInstanceCoreExpression -> {
                nodes += ParserWorkerComponentGraphNode(id, "core_instance", "core instance $index", "instantiates module ${instance.moduleIndex}")
                edges += ParserWorkerComponentGraphEdge("module:${instance.moduleIndex}", id)
                instance.instantiateArgs.forEach { arg ->
                    edges += ParserWorkerComponentGraphEdge("core-instance:${arg.instanceIndex}", id, arg.name)
                }
            }
            is InlineInstanceCoreExpression -> nodes += ParserWorkerComponentGraphNode(id, "core_instance", "core instance $index", "inline exports")
        }
    }
    provider.instances.forEachIndexed { index, instance ->
        val id = "instance:$index"
        when (instance) {
            is ComponentInstanceExpression -> {
                nodes += ParserWorkerComponentGraphNode(id, "instance", "instance $index", "instantiates component ${instance.index}")
                edges += ParserWorkerComponentGraphEdge("component:${instance.index}", id)
                instance.args.forEach { arg -> componentGraphSourceId(arg.sortIndex.sort, arg.sortIndex.index.toInt())?.let { source ->
                    edges += ParserWorkerComponentGraphEdge(source, id, arg.name)
                } }
            }
            is InlineInstanceExpression -> nodes += ParserWorkerComponentGraphNode(id, "instance", "instance $index", "inline exports")
        }
    }
    provider.exports.forEachIndexed { index, export ->
        val id = "export:$index"
        nodes += ParserWorkerComponentGraphNode(
            id,
            "export",
            componentName(export.exportName, export.version),
            componentSortIndexLabel(export.sortIndex) ?: "",
        )
        componentGraphSourceId(export.sortIndex.sort, export.sortIndex.index.toInt())?.let { source ->
            edges += ParserWorkerComponentGraphEdge(source, id)
        }
    }
    val nodeIds = nodes.mapTo(mutableSetOf()) { it.id }
    return ParserWorkerComponentGraph(nodes, edges.filter { it.fromId in nodeIds && it.toId in nodeIds })
}

private fun componentName(name: String, version: String?): String =
    if (version == null) name else "$name@$version"

private fun postError(
    workerScope: dynamic,
    requestId: String,
    code: String,
    message: String,
) {
    val safeRequestId = requestId.takeIf(::isValidParserWorkerRequestId).orEmpty()
    val response = js("({})")
    response.protocolVersion = PARSER_WORKER_PROTOCOL_VERSION
    response.kind = "error"
    response.requestId = safeRequestId
    response.code = code
    response.message = message
    workerScope.postMessage(response)
}

private fun now(): Double = js("performance.now()").unsafeCast<Double>()
