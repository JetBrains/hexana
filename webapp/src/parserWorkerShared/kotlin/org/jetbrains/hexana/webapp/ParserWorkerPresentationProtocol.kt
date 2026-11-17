package org.jetbrains.hexana.webapp

import org.jetbrains.hexana.wasm.analysis.WasmProposal
import org.jetbrains.hexana.wasm.components.ComponentCoreSort
import org.jetbrains.hexana.wasm.components.ComponentSort
import org.jetbrains.hexana.wasm.components.ComponentSortWithCoreSort
import org.jetbrains.hexana.shared.TableTabDescriptor

/** The serialised JSON representation of a worker success is never larger than this value. */
internal const val MAX_PARSER_WORKER_WIRE_BYTES = 10 * 1024 * 1024
internal const val MAX_PARSER_WORKER_RECORDS_PER_COLLECTION = 10_000
internal const val MAX_PARSER_WORKER_REQUEST_ID_CHARS = 128
internal const val MAX_PARSER_WORKER_FILE_NAME_CHARS = 1_024
/** Fixed schema collections are rejected on the producer side instead of silently changing their identity. */
internal const val MAX_PARSER_WORKER_CAPABILITIES = 32
internal const val MAX_PARSER_WORKER_TABLES = 32
internal const val MAX_PARSER_WORKER_LEGACY_TABS = 16
internal const val PARSER_WORKER_PROTOCOL_VERSION = 10

private const val MAX_FORMAT_CHARS = 64
private const val MAX_DISPLAY_LABEL_CHARS = 1_024
private const val MAX_CAPABILITY_CHARS = 64
/** Producers bound their labels to this before building a range; see `toWorkerRange`. */
internal const val MAX_RANGE_LABEL_CHARS = 256
private const val MAX_TABLE_ID_CHARS = 64
private const val MAX_TABLE_COLUMNS = 16
private const val MAX_TABLE_COLUMN_CHARS = 64
private const val MAX_TABLE_TEXT_CHARS = 1_024
private const val MAX_TABLE_FILTERS = 16
private const val MAX_RECORD_KEY_CHARS = 1_024
private const val MAX_RECORD_VALUE_CHARS = 16_384
private const val MAX_RECORD_VALUES = MAX_TABLE_COLUMNS
private const val MAX_RECORD_ACTIONS = 1
private const val MAX_RECORD_CHILDREN = MAX_PARSER_WORKER_RECORDS_PER_COLLECTION
private const val MAX_RECORD_TREE_DEPTH = 32
private const val MAX_ACTION_KIND_CHARS = 32
private const val MAX_ACTION_NAME_CHARS = 1_024
private const val MAX_ACTION_CONTENT_TYPE_CHARS = 256
private const val MAX_DIAGNOSTIC_CODE_CHARS = 64
private const val MAX_DIAGNOSTIC_MESSAGE_CHARS = 1_024
private const val MAX_PARTIAL_REASONS = 3
private const val MAX_PARTIAL_REASON_CHARS = 32
private const val MAX_GRAPH_NODE_ID_CHARS = 128
private const val MAX_GRAPH_NODE_KIND_CHARS = 32
private const val MAX_GRAPH_NODE_LABEL_CHARS = 1_024
private const val MAX_GRAPH_EDGE_LABEL_CHARS = 1_024
private const val MAX_LEGACY_TAB_LABEL_CHARS = 64
private const val MAX_TOOLCHAIN_IMPORT_MODULES = 8
private const val MAX_TOOLCHAIN_IMPORT_MODULE_CHARS = 64
private const val MAX_TOOLBAR_PROPOSAL_ID_CHARS = 64
private const val MAX_TOOLBAR_RUNNABLE_EXPORT_CHARS = 64
private const val MAX_NATIVE_IMAGE_TEXT_CHARS = 1_024

/** Every id the worker's `nonWasmFormat` can emit; one missing here turns that format into PARSE_FAILED. */
private val supportedFormats = setOf(
    "core_wasm", "wasm_component", "zip", "class", "dex", "arsc", "dotnet-resources", "gguf", "safetensors", "pdb", "portable-pdb", "pcap", "protobuf",
    "elf", "macho", "fat_macho", "pe", "coff", "archive", "parquet", "arrow", "generic",
)
private val supportedCapabilities = setOf("summary", "structural_ranges", "tables", "diagnostics", "component_dependency_graph")
private val supportedGraphNodeKinds = setOf("import", "module", "nested_component", "core_instance", "instance", "export")
/**
 * Derived from [WasmProposal] rather than transcribed, so adding a proposal to the parser
 * cannot silently make the worker emit an id the validator rejects - which would discard the
 * whole presentation, not just the badge. The enum is fixed at compile time, so this is as
 * bounded as a literal set.
 */
private val supportedToolbarProposalIds: Set<String> = WasmProposal.entries.map { it.name }.toSet()

private val MAX_TOOLBAR_PROPOSALS = supportedToolbarProposalIds.size
private val supportedLegacyTabsByFormat = mapOf(
    "core_wasm" to setOf("Data", "Tags", "Exceptions", "Custom", "Top", "Monos", "Garbage", "Wat", "WAT (virtualized)"),
    "wasm_component" to setOf("Components", "Wat", "WAT (virtualized)"),
    "zip" to emptySet(),
    "class" to emptySet(),
    "dex" to emptySet(),
    "arsc" to emptySet(),
    "dotnet-resources" to emptySet(),
    "gguf" to emptySet(),
    "safetensors" to emptySet(),
    "pdb" to emptySet(),
    "portable-pdb" to emptySet(),
    "pcap" to emptySet(),
    "protobuf" to emptySet(),
    "elf" to emptySet(),
    "macho" to emptySet(),
    "fat_macho" to emptySet(),
    "pe" to emptySet(),
    "coff" to emptySet(),
    "archive" to emptySet(),
    "parquet" to emptySet(),
    "arrow" to emptySet(),
    "generic" to emptySet(),
)
private const val RECORD_LIMIT_REASON = "record_limit"
private const val BYTE_LIMIT_REASON = "byte_limit"
private const val CONTENT_LIMIT_REASON = "content_limit"

internal data class ParserWorkerSummary(
    val scopeMarker: String,
    val documentAbsent: Boolean,
    val inputBytes: Int,
    val sectionCount: Int,
    val importCount: Int,
    val definedFunctionCount: Int,
    val codeBodyCount: Int,
    val elapsedMs: Double,
    val exportCount: Int = 0,
    val moduleCount: Int = 0,
    val proposalCount: Int = 0,
    val graalWebImage: Boolean = false,
    val graalWebImageMatchedImportModules: List<String> = emptyList(),
)

internal data class ParserWorkerRange(val offset: Int, val size: Int, val label: String)
internal data class ParserWorkerRecord(
    val key: String,
    val value: String,
    val values: List<String> = listOf(key, value),
    val offset: Int? = null,
    val size: Int? = null,
    val actions: List<ParserWorkerAction> = emptyList(),
    val children: List<ParserWorkerRecord> = emptyList(),
    /** Numeric sort keys preserve descriptor ordering while the display values remain text. */
    val numericValues: List<Double?> = List(values.size) { null },
)
internal data class ParserWorkerTableFilter(
    val label: String,
    val matchingRootRows: List<Int>,
)
internal data class ParserWorkerAction(
    val kind: String,
    val index: Int? = null,
    val name: String? = null,
    val offset: Int? = null,
    val size: Int? = null,
    val dataOffset: Int? = null,
    val compressedSize: Int? = null,
    val uncompressedSize: Long? = null,
    val compressionMethod: Int? = null,
    val contentType: String? = null,
    val openable: Boolean = false,
)
internal data class ParserWorkerTable(
    val id: String,
    val records: List<ParserWorkerRecord>,
    val columns: List<String> = listOf("Key", "Value"),
    val prologueText: String? = null,
    val emptyMessage: String? = null,
    val defaultSortColumn: Int? = null,
    val defaultSortAscending: Boolean = true,
    val alwaysShowSearch: Boolean = false,
    val searchPlaceholder: String? = null,
    val showIdColumn: Boolean = true,
    val filters: List<ParserWorkerTableFilter> = emptyList(),
    val defaultFilterShowAll: Boolean = false,
)
internal data class ParserWorkerDiagnostic(val code: String, val message: String)
internal data class ParserWorkerComponentGraphNode(
    val id: String,
    val kind: String,
    val label: String,
    val sublabel: String? = null,
    /**
     * Byte range of the entity the node stands for, so the Window can navigate
     * to it without reparsing. Both null when the Worker has none: core and
     * component instances carry no per-entry offset.
     */
    val offset: Int? = null,
    val size: Int? = null,
)
internal data class ParserWorkerComponentGraphEdge(
    val fromId: String,
    val toId: String,
    val label: String? = null,
)
internal data class ParserWorkerComponentGraph(
    val nodes: List<ParserWorkerComponentGraphNode>,
    val edges: List<ParserWorkerComponentGraphEdge>,
)
internal data class ParserWorkerToolbar(
    val runnableExportName: String? = null,
    val proposalIds: List<String> = emptyList(),
    val nativeImage: ParserWorkerNativeImage? = null,
)

/** Read-only native-image metadata produced by the Worker; it deliberately carries no parser provider or callbacks. */
internal data class ParserWorkerNativeImage(
    val version: String? = null,
    val edition: String = "unknown",
    val platform: String? = null,
    val hasSbom: Boolean = false,
)

internal data class ParserWorkerPresentation(
    val format: String,
    val capabilities: List<String>,
    val summary: ParserWorkerSummary,
    val ranges: List<ParserWorkerRange> = emptyList(),
    val tables: List<ParserWorkerTable> = emptyList(),
    val diagnostics: List<ParserWorkerDiagnostic> = emptyList(),
    val componentGraph: ParserWorkerComponentGraph? = null,
    val legacyTabs: List<String> = emptyList(),
    val toolbar: ParserWorkerToolbar = ParserWorkerToolbar(),
    /** Bounded Worker-derived label; the Window must not redetect the binary format. */
    val displayLabel: String = "Binary",
)

internal data class ParserWorkerPartial(
    val partial: Boolean,
    val reasons: List<String>,
    val emittedCount: Int,
    val totalCount: Int?,
    val totalCountLowerBound: Int?,
)

internal data class BoundedParserWorkerSuccess(
    val presentation: ParserWorkerPresentation,
    val partial: ParserWorkerPartial,
    val wireBytes: Int,
)

internal data class SanitizedParserWorkerPresentation(
    val presentation: ParserWorkerPresentation,
    val omittedRecordCount: Int,
)

/**
 * Produces a deterministic response-record prefix. JSON is measured exactly as UTF-8 before it is posted.
 * The binary search probes a monotonic prefix length, avoiding repeated quadratic full-payload serialisation.
 */
internal fun boundParserWorkerSuccess(
    requestId: String,
    presentation: ParserWorkerPresentation,
    maxWireBytes: Int = MAX_PARSER_WORKER_WIRE_BYTES,
    omittedRecordCount: Int = 0,
    producerRecordLimited: Boolean = false,
): BoundedParserWorkerSuccess {
    require(maxWireBytes > 0)
    require(omittedRecordCount >= 0) { "Parser-worker omitted record count is invalid." }
    require(isValidParserWorkerRequestId(requestId)) { "Invalid parser-worker request ID." }
    requireValidProducerPresentation(presentation)

    val recordLimited = producerRecordLimited || presentation.ranges.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION ||
        presentation.tables.any { it.records.recordCount() > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION } ||
        presentation.diagnostics.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION ||
        (presentation.componentGraph?.nodes?.size ?: 0) > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION ||
        (presentation.componentGraph?.edges?.size ?: 0) > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION
    val capped = presentation.copy(
        ranges = presentation.ranges.take(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION),
        tables = presentation.tables.map { table ->
            table.withBoundedRecords(table.records.takeDeterministicRecordPrefix(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION))
        },
        diagnostics = presentation.diagnostics.take(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION),
        componentGraph = presentation.componentGraph?.let { graph ->
            val nodes = graph.nodes.take(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION)
            val nodeIds = nodes.mapTo(mutableSetOf()) { it.id }
            graph.copy(
                nodes = nodes,
                edges = graph.edges
                    .asSequence()
                    .filter { it.fromId in nodeIds && it.toId in nodeIds }
                    .take(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION)
                    .toList(),
            )
        },
    )
    val total = presentation.responseRecordCount().toLong() + omittedRecordCount + if (producerRecordLimited) 1 else 0
    require(total <= Int.MAX_VALUE) { "Parser-worker record count exceeds the wire representation." }
    val totalCount = total.toInt()
    val cappedTotal = capped.responseRecordCount()

    fun candidate(keep: Int, byteLimited: Boolean): Pair<ParserWorkerPresentation, ParserWorkerPartial> {
        val bounded = capped.takeDeterministicPrefix(keep)
        return Pair(
            bounded,
            partialMetadata(recordLimited, omittedRecordCount > 0, byteLimited, bounded.responseRecordCount(), totalCount),
        )
    }

    fun candidateWireBytes(candidate: Pair<ParserWorkerPresentation, ParserWorkerPartial>): Int =
        utf8WireBytes(encodeSuccessWire(requestId, candidate.first, candidate.second))

    val initial = candidate(cappedTotal, false)
    val initialWireBytes = candidateWireBytes(initial)
    if (initialWireBytes <= maxWireBytes) {
        return BoundedParserWorkerSuccess(initial.first, initial.second, initialWireBytes)
    }

    val zero = candidate(0, true)
    require(candidateWireBytes(zero) <= maxWireBytes) {
        "The requested wire limit cannot represent a valid zero-record parser-worker response."
    }

    var low = 0
    var high = cappedTotal
    while (low < high) {
        val middle = (low + high + 1) ushr 1
        if (candidateWireBytes(candidate(middle, true)) <= maxWireBytes) low = middle else high = middle - 1
    }
    check(low < cappedTotal)
    val bounded = candidate(low, true)
    return BoundedParserWorkerSuccess(bounded.first, bounded.second, candidateWireBytes(bounded))
}

internal fun encodeBoundedParserWorkerSuccess(
    requestId: String,
    presentation: ParserWorkerPresentation,
    omittedRecordCount: Int = 0,
    producerRecordLimited: Boolean = false,
): dynamic {
    require(isValidParserWorkerRequestId(requestId)) { "Invalid parser-worker request ID." }
    val bounded = boundParserWorkerSuccess(
        requestId,
        presentation,
        omittedRecordCount = omittedRecordCount,
        producerRecordLimited = producerRecordLimited,
    )
    return encodeSuccessWire(requestId, bounded.presentation, bounded.partial)
}

private fun partialMetadata(
    recordLimited: Boolean,
    contentLimited: Boolean,
    byteLimited: Boolean,
    emittedCount: Int,
    totalCount: Int,
): ParserWorkerPartial = when {
    recordLimited -> ParserWorkerPartial(
        partial = true,
        reasons = buildList {
            if (contentLimited) add(CONTENT_LIMIT_REASON)
            add(RECORD_LIMIT_REASON)
            if (byteLimited) add(BYTE_LIMIT_REASON)
        },
        emittedCount = emittedCount,
        totalCount = null,
        totalCountLowerBound = totalCount,
    )
    contentLimited || byteLimited -> ParserWorkerPartial(
        partial = true,
        reasons = buildList {
            if (contentLimited) add(CONTENT_LIMIT_REASON)
            if (byteLimited) add(BYTE_LIMIT_REASON)
        },
        emittedCount = emittedCount,
        totalCount = totalCount,
        totalCountLowerBound = null,
    )
    else -> ParserWorkerPartial(
        partial = false,
        reasons = emptyList(),
        emittedCount = emittedCount,
        totalCount = emittedCount,
        totalCountLowerBound = null,
    )
}

private fun ParserWorkerPresentation.responseRecordCount(): Int {
    val count = ranges.size.toLong() + tables.sumOf { it.records.recordCount().toLong() } + diagnostics.size.toLong() +
        (componentGraph?.nodes?.size ?: 0) + (componentGraph?.edges?.size ?: 0)
    require(count <= Int.MAX_VALUE) { "Parser-worker record count exceeds the wire representation." }
    return count.toInt()
}

private fun ParserWorkerPresentation.takeDeterministicPrefix(count: Int): ParserWorkerPresentation {
    var remaining = count
    val ranges = ranges.take(minOf(remaining, ranges.size))
    remaining -= ranges.size
    val tables = tables.map { table ->
        val records = table.records.takeDeterministicRecordPrefix(remaining)
        remaining -= records.recordCount()
        table.withBoundedRecords(records)
    }
    val diagnostics = diagnostics.take(minOf(remaining, diagnostics.size))
    remaining -= diagnostics.size
    val componentGraph = componentGraph?.let { graph ->
        val nodes = graph.nodes.take(minOf(remaining, graph.nodes.size))
        remaining -= nodes.size
        val edges = graph.edges.take(minOf(remaining, graph.edges.size))
        graph.copy(nodes = nodes, edges = edges)
    }
    return copy(ranges = ranges, tables = tables, diagnostics = diagnostics, componentGraph = componentGraph)
}

private fun ParserWorkerTable.withBoundedRecords(records: List<ParserWorkerRecord>): ParserWorkerTable = copy(
    records = records,
    filters = filters.map { filter ->
        filter.copy(matchingRootRows = filter.matchingRootRows.filter { it in records.indices })
    },
)

private fun List<ParserWorkerRecord>.recordCount(): Int {
    val count = fold(0L) { total, record -> total + record.recordCount() }
    require(count <= Int.MAX_VALUE) { "Parser-worker record count exceeds the wire representation." }
    return count.toInt()
}

private fun ParserWorkerRecord.recordCount(): Long =
    1L + children.fold(0L) { total, child -> total + child.recordCount() }

private fun List<ParserWorkerRecord>.takeDeterministicRecordPrefix(limit: Int): List<ParserWorkerRecord> {
    var remaining = limit
    fun take(records: List<ParserWorkerRecord>): List<ParserWorkerRecord> = buildList {
        for (record in records) {
            if (remaining == 0) break
            remaining--
            add(record.copy(children = take(record.children)))
        }
    }
    return take(this)
}

internal fun decodeParserWorkerSuccess(value: dynamic, requestId: String): BoundedParserWorkerSuccess? {
    val wireBytes = parserWorkerResponseEnvelopeBytesOrNull(value) ?: return null
    return decodeParserWorkerSuccess(value, requestId, wireBytes)
}

internal fun decodeParserWorkerSuccess(
    value: dynamic,
    requestId: String,
    wireBytes: Int,
): BoundedParserWorkerSuccess? {
    return try {
        if (
            wireBytes !in 0..MAX_PARSER_WORKER_WIRE_BYTES ||
            !isValidParserWorkerRequestId(requestId) ||
            value.protocolVersion != PARSER_WORKER_PROTOCOL_VERSION ||
            value.kind != "success"
        ) {
            return null
        }
        val wireRequestId = value.requestId as? String ?: return null
        if (!isValidParserWorkerRequestId(wireRequestId) || wireRequestId != requestId) return null
        val presentation = value.presentation
        val partial = value.partial
        if (!isPlainWireObject(presentation) || !isPlainWireObject(partial)) return null

        val format = presentation.format as? String ?: return null
        if (!isValidFormat(format)) return null
        val capabilities = decodeStrings(presentation.capabilities, MAX_PARSER_WORKER_CAPABILITIES, MAX_CAPABILITY_CHARS) ?: return null
        if (!isValidCapabilities(capabilities)) return null
        val summary = decodeSummary(presentation.summary) ?: return null
        val ranges = decodeRanges(presentation.ranges, summary.inputBytes) ?: return null
        val tables = decodeTables(presentation.tables, summary.inputBytes) ?: return null
        val diagnostics = decodeDiagnostics(presentation.diagnostics) ?: return null
        val componentGraph = decodeComponentGraph(presentation.componentGraph, summary.inputBytes) ?: return null
        val legacyTabs = decodeLegacyTabs(presentation.legacyTabs, format) ?: return null
        val toolbar = decodeToolbar(presentation.toolbar, format) ?: return null
        val displayLabel = safeString(presentation.displayLabel, MAX_DISPLAY_LABEL_CHARS)?.takeIf { it.isNotBlank() } ?: return null
        if (!hasCoherentContent(format, capabilities, ranges, tables, diagnostics, componentGraph, toolbar)) return null

        val reasons = decodeStrings(partial.reasons, MAX_PARTIAL_REASONS, MAX_PARTIAL_REASON_CHARS) ?: return null
        val isPartial = partial.partial as? Boolean ?: return null
        val emitted = safeWireInt(partial.emittedCount) ?: return null
        val total = if (partial.totalCount == null) null else safeWireInt(partial.totalCount) ?: return null
        val lowerBound = if (partial.totalCountLowerBound == null) null else safeWireInt(partial.totalCountLowerBound) ?: return null
        val actual = ranges.size + tables.sumOf { it.records.recordCount() } + diagnostics.size +
            componentGraph.nodes.size + componentGraph.edges.size
        if (emitted != actual || !isValidPartialMetadata(isPartial, reasons, emitted, total, lowerBound)) return null

        BoundedParserWorkerSuccess(
            ParserWorkerPresentation(
                format,
                capabilities,
                summary,
                ranges,
                tables,
                diagnostics,
                componentGraph.takeIf { it.nodes.isNotEmpty() || it.edges.isNotEmpty() },
                legacyTabs,
                toolbar,
                displayLabel,
            ),
            ParserWorkerPartial(isPartial, reasons, emitted, total, lowerBound),
            wireBytes,
        )
    } catch (_: Throwable) {
        null
    }
}

internal fun encodeSuccessWire(requestId: String, presentation: ParserWorkerPresentation, partial: ParserWorkerPartial): dynamic {
    val value = js("({})")
    value.protocolVersion = PARSER_WORKER_PROTOCOL_VERSION
    value.kind = "success"
    value.requestId = requestId
    value.presentation = js("({})")
    value.presentation.format = presentation.format
    value.presentation.displayLabel = presentation.displayLabel
    value.presentation.capabilities = presentation.capabilities.toTypedArray()
    value.presentation.summary = js("({})")
    with(value.presentation.summary) {
        scopeMarker = presentation.summary.scopeMarker; documentAbsent = presentation.summary.documentAbsent
        inputBytes = presentation.summary.inputBytes; sectionCount = presentation.summary.sectionCount
        importCount = presentation.summary.importCount; definedFunctionCount = presentation.summary.definedFunctionCount
        codeBodyCount = presentation.summary.codeBodyCount; elapsedMs = presentation.summary.elapsedMs
        exportCount = presentation.summary.exportCount; moduleCount = presentation.summary.moduleCount
        proposalCount = presentation.summary.proposalCount
        graalWebImage = presentation.summary.graalWebImage
        graalWebImageMatchedImportModules = presentation.summary.graalWebImageMatchedImportModules.toTypedArray()
    }
    value.presentation.ranges = presentation.ranges.map { range ->
        val wire = wireObject()
        wire.offset = range.offset; wire.size = range.size; wire.label = range.label
        wire
    }.toTypedArray()
    value.presentation.tables = presentation.tables.map { table ->
        val wire = wireObject()
        wire.id = table.id
        wire.columns = table.columns.toTypedArray()
        wire.prologueText = table.prologueText
        wire.emptyMessage = table.emptyMessage
        wire.defaultSortColumn = table.defaultSortColumn
        wire.defaultSortAscending = table.defaultSortAscending
        wire.alwaysShowSearch = table.alwaysShowSearch
        wire.searchPlaceholder = table.searchPlaceholder
        wire.showIdColumn = table.showIdColumn
        wire.filters = table.filters.map { filter ->
            val filterWire = wireObject()
            filterWire.label = filter.label
            filterWire.matchingRootRows = filter.matchingRootRows.toTypedArray()
            filterWire
        }.toTypedArray()
        wire.defaultFilterShowAll = table.defaultFilterShowAll
        wire.records = table.records.map(::encodeRecordWire).toTypedArray()
        wire
    }.toTypedArray()
    value.presentation.componentGraph = presentation.componentGraph?.let { graph ->
        val wire = wireObject()
        wire.nodes = graph.nodes.map { node ->
            val item = wireObject()
            item.id = node.id; item.kind = node.kind; item.label = node.label; item.sublabel = node.sublabel
            item.offset = node.offset; item.size = node.size
            item
        }.toTypedArray()
        wire.edges = graph.edges.map { edge ->
            val item = wireObject()
            item.fromId = edge.fromId; item.toId = edge.toId; item.label = edge.label
            item
        }.toTypedArray()
        wire
    }
    value.presentation.legacyTabs = presentation.legacyTabs.toTypedArray()
    val toolbar = wireObject()
    toolbar.runnableExportName = presentation.toolbar.runnableExportName
    toolbar.proposalIds = presentation.toolbar.proposalIds.toTypedArray()
    toolbar.nativeImage = presentation.toolbar.nativeImage?.let { nativeImage ->
        val nativeImageWire = wireObject()
        nativeImageWire.version = nativeImage.version
        nativeImageWire.edition = nativeImage.edition
        nativeImageWire.platform = nativeImage.platform
        nativeImageWire.hasSbom = nativeImage.hasSbom
        nativeImageWire
    }
    value.presentation.toolbar = toolbar
    value.presentation.diagnostics = presentation.diagnostics.map { diagnostic ->
        val wire = wireObject()
        wire.code = diagnostic.code; wire.message = diagnostic.message
        wire
    }.toTypedArray()
    value.partial = js("({})")
    with(value.partial) {
        this.partial = partial.partial; reasons = partial.reasons.toTypedArray(); emittedCount = partial.emittedCount
        totalCount = partial.totalCount; totalCountLowerBound = partial.totalCountLowerBound
    }
    return value
}

private fun encodeRecordWire(record: ParserWorkerRecord): dynamic {
    val item = wireObject()
    item.key = record.key; item.value = record.value; item.values = record.values.toTypedArray()
    item.offset = record.offset; item.size = record.size
    item.numericValues = record.numericValues.toTypedArray()
    item.actions = record.actions.map { action ->
        val actionWire = wireObject()
        actionWire.kind = action.kind; actionWire.index = action.index; actionWire.name = action.name
        actionWire.offset = action.offset; actionWire.size = action.size
        actionWire.dataOffset = action.dataOffset; actionWire.compressedSize = action.compressedSize
        actionWire.uncompressedSize = action.uncompressedSize?.toDouble(); actionWire.compressionMethod = action.compressionMethod
        actionWire.contentType = action.contentType; actionWire.openable = action.openable
        actionWire
    }.toTypedArray()
    item.children = record.children.map(::encodeRecordWire).toTypedArray()
    return item
}

internal fun utf8WireBytes(value: dynamic): Int =
    js("new TextEncoder().encode(JSON.stringify(value)).length").unsafeCast<Int>()

internal fun parserWorkerResponseEnvelopeBytesOrNull(value: dynamic): Int? = try {
    if (!isPlainWireObject(value)) null
    else utf8WireBytes(value).takeIf { it <= MAX_PARSER_WORKER_WIRE_BYTES }
} catch (_: Throwable) {
    null
}

internal fun isValidParserWorkerRequestId(value: String): Boolean =
    value.isNotBlank() && value.length <= MAX_PARSER_WORKER_REQUEST_ID_CHARS

internal fun isValidParserWorkerFileName(value: String): Boolean =
    value.length <= MAX_PARSER_WORKER_FILE_NAME_CHARS

/**
 * Drops whole presentation records whose untrusted parser-derived strings cannot fit the protocol.
 * The caller passes [SanitizedParserWorkerPresentation.omittedRecordCount] into the bounded encoder,
 * so the UI receives an explicit partial-result marker instead of a misleading parse failure or a
 * truncated identifier.
 */
internal fun sanitizeParserWorkerPresentation(presentation: ParserWorkerPresentation): SanitizedParserWorkerPresentation {
    var omitted = 0L
    fun omit(count: Int = 1) {
        omitted += count
        require(omitted <= Int.MAX_VALUE) { "Parser-worker omitted record count exceeds the wire representation." }
    }
    fun omit(count: Long) {
        omitted += count
        require(omitted <= Int.MAX_VALUE) { "Parser-worker omitted record count exceeds the wire representation." }
    }

    val summary = presentation.summary.let { source ->
        if (isValidGraalWebImageMetadata(source)) source
        else {
            omit()
            source.copy(graalWebImage = false, graalWebImageMatchedImportModules = emptyList())
        }
    }
    val ranges = presentation.ranges.filter { range ->
        (isValidRange(range, summary.inputBytes) && range.label.length <= MAX_RANGE_LABEL_CHARS).also { valid ->
            if (!valid) omit()
        }
    }
    fun sanitizeRecord(record: ParserWorkerRecord, columnCount: Int, depth: Int): ParserWorkerRecord? {
        val valid = record.key.length <= MAX_RECORD_KEY_CHARS &&
            record.value.length <= MAX_RECORD_VALUE_CHARS &&
            record.values.size in 1..MAX_RECORD_VALUES &&
            record.values.all { it.length <= MAX_RECORD_VALUE_CHARS } &&
            record.values.size == columnCount &&
            record.numericValues.size == columnCount &&
            record.numericValues.all { it == null || (it.isFinite() && it >= -9_007_199_254_740_991.0 && it <= 9_007_199_254_740_991.0) } &&
            isValidRecordRange(record, summary.inputBytes)
        if (!valid) {
            omit(record.recordCount())
            return null
        }
        val actions = record.actions.filter { action ->
            isValidAction(action, summary.inputBytes).also { actionValid -> if (!actionValid) omit() }
        }
        if (depth >= MAX_RECORD_TREE_DEPTH) {
            omit(record.children.recordCount())
            return record.copy(actions = actions, children = emptyList())
        }
        val children = record.children.take(MAX_RECORD_CHILDREN).mapNotNull { child -> sanitizeRecord(child, columnCount, depth + 1) }
        if (record.children.size > MAX_RECORD_CHILDREN) omit(record.children.drop(MAX_RECORD_CHILDREN).recordCount())
        return record.copy(actions = actions, children = children)
    }
    val tables = presentation.tables.mapNotNull { table ->
        val validTableMetadata = table.id.isNotBlank() && table.id.length <= MAX_TABLE_ID_CHARS &&
            table.columns.size in 1..MAX_TABLE_COLUMNS && table.columns.all { it.length <= MAX_TABLE_COLUMN_CHARS } &&
            (table.defaultSortColumn == null || table.defaultSortColumn == TableTabDescriptor.ID_COLUMN ||
                table.defaultSortColumn in table.columns.indices) &&
            (table.searchPlaceholder == null || table.searchPlaceholder.length <= MAX_TABLE_TEXT_CHARS)
        if (!validTableMetadata) {
            omit(table.records.recordCount())
            return@mapNotNull null
        }
        val prologueText = table.prologueText?.takeIf { it.length <= MAX_TABLE_TEXT_CHARS }.also {
            if (table.prologueText != null && it == null) omit()
        }
        val emptyMessage = table.emptyMessage?.takeIf { it.length <= MAX_TABLE_TEXT_CHARS }.also {
            if (table.emptyMessage != null && it == null) omit()
        }
        val retainedRootRowsByOriginalRow = mutableMapOf<Int, Int>()
        val records = buildList {
            table.records.forEachIndexed { originalRow, record ->
                sanitizeRecord(record, table.columns.size, depth = 0)?.let { sanitizedRecord ->
                    retainedRootRowsByOriginalRow[originalRow] = size
                    add(sanitizedRecord)
                }
            }
        }
        val filters = table.filters.take(MAX_TABLE_FILTERS).mapNotNull { filter ->
            if (
                filter.label.isBlank() || filter.label.length > MAX_TABLE_TEXT_CHARS ||
                filter.matchingRootRows.size > table.records.size ||
                filter.matchingRootRows.distinct().size != filter.matchingRootRows.size ||
                filter.matchingRootRows.any { it !in table.records.indices }
            ) {
                omit()
                null
            } else {
                filter.copy(matchingRootRows = filter.matchingRootRows.mapNotNull(retainedRootRowsByOriginalRow::get))
            }
        }
        if (table.filters.size > MAX_TABLE_FILTERS) omit(table.filters.size - MAX_TABLE_FILTERS)
        table.copy(records = records, prologueText = prologueText, emptyMessage = emptyMessage, filters = filters)
    }
    val diagnostics = presentation.diagnostics.filter { diagnostic ->
        val valid = diagnostic.code.length <= MAX_DIAGNOSTIC_CODE_CHARS && diagnostic.message.length <= MAX_DIAGNOSTIC_MESSAGE_CHARS
        if (!valid) omit()
        valid
    }
    val componentGraph = presentation.componentGraph?.let { graph ->
        val nodes = graph.nodes.filter { node ->
            val valid = isValidComponentGraphNode(node, summary.inputBytes)
            if (!valid) omit()
            valid
        }
        val nodeIds = nodes.mapTo(mutableSetOf()) { it.id }
        val edges = graph.edges.filter { edge ->
            val valid = isValidComponentGraphEdge(edge) && edge.fromId in nodeIds && edge.toId in nodeIds
            if (!valid) omit()
            valid
        }
        ParserWorkerComponentGraph(nodes, edges).takeIf { nodes.isNotEmpty() || edges.isNotEmpty() }
    }

    val displayLabel = presentation.displayLabel.takeIf { it.isNotBlank() && it.length <= MAX_DISPLAY_LABEL_CHARS }.also {
        if (it == null) omit()
    } ?: "Binary"
    val coherentToolbar = presentation.toolbar.copy(
        nativeImage = presentation.toolbar.nativeImage?.let { nativeImage ->
            val version = nativeImage.version?.takeIf { it.length <= MAX_NATIVE_IMAGE_TEXT_CHARS }.also {
                if (nativeImage.version != null && it == null) omit()
            }
            val platform = nativeImage.platform?.takeIf { it.length <= MAX_NATIVE_IMAGE_TEXT_CHARS }.also {
                if (nativeImage.platform != null && it == null) omit()
            }
            nativeImage.copy(
                version = version,
                platform = platform,
                hasSbom = tables.any { it.id == "SBOM" },
            )
        },
    )

    return SanitizedParserWorkerPresentation(
        presentation.copy(
            summary = summary,
            ranges = ranges,
            tables = tables,
            diagnostics = diagnostics,
            componentGraph = componentGraph,
            toolbar = coherentToolbar,
            displayLabel = displayLabel,
        ),
        omitted.toInt(),
    )
}

internal fun componentGraphSourceId(sort: ComponentSort, index: Int): String? = when (sort) {
    is ComponentSortWithCoreSort -> when (sort.cs) {
        ComponentCoreSort.Module -> "module:$index"
        ComponentCoreSort.Instance -> "core-instance:$index"
        else -> null
    }
    ComponentSort.Instance -> "instance:$index"
    ComponentSort.Component -> "component:$index"
    else -> null
}

private fun requireValidProducerPresentation(presentation: ParserWorkerPresentation) {
    require(isValidFormat(presentation.format)) { "Unsupported parser-worker format." }
    require(isValidCapabilities(presentation.capabilities)) { "Invalid parser-worker capability collection." }
    require(isValidLegacyTabs(presentation.format, presentation.legacyTabs)) { "Invalid parser-worker legacy tabs." }
    require(isValidToolbar(presentation.format, presentation.toolbar)) { "Invalid parser-worker toolbar presentation." }
    require(presentation.displayLabel.isNotBlank() && presentation.displayLabel.length <= MAX_DISPLAY_LABEL_CHARS) {
        "Invalid parser-worker display label."
    }
    require(presentation.tables.size <= MAX_PARSER_WORKER_TABLES) { "Too many parser-worker tables." }
    require(isValidSummary(presentation.summary)) { "Invalid parser-worker summary." }
    require(hasCoherentContent(
        presentation.format,
        presentation.capabilities,
        presentation.ranges,
        presentation.tables,
        presentation.diagnostics,
        presentation.componentGraph ?: ParserWorkerComponentGraph(emptyList(), emptyList()),
        presentation.toolbar,
    )) {
        "Parser-worker content does not match its capabilities."
    }
    presentation.responseRecordCount()
    presentation.ranges.forEach { range ->
        require(isValidRange(range, presentation.summary.inputBytes)) { "Invalid parser-worker range." }
        require(range.label.length <= MAX_RANGE_LABEL_CHARS) { "Parser-worker range label is too long." }
    }
    presentation.tables.forEach { table ->
        require(table.id.isNotBlank() && table.id.length <= MAX_TABLE_ID_CHARS) { "Invalid parser-worker table ID." }
        require(table.columns.size in 1..MAX_TABLE_COLUMNS && table.columns.all { it.length <= MAX_TABLE_COLUMN_CHARS }) {
            "Invalid parser-worker table columns."
        }
        require((table.prologueText == null || table.prologueText.length <= MAX_TABLE_TEXT_CHARS) &&
            (table.emptyMessage == null || table.emptyMessage.length <= MAX_TABLE_TEXT_CHARS)) {
            "Parser-worker table text is too long."
        }
        require(table.defaultSortColumn == null || table.defaultSortColumn == TableTabDescriptor.ID_COLUMN ||
            table.defaultSortColumn in table.columns.indices) { "Invalid parser-worker table sort column." }
        require(table.searchPlaceholder == null || table.searchPlaceholder.length <= MAX_TABLE_TEXT_CHARS) {
            "Parser-worker table search placeholder is too long."
        }
        require(table.filters.size <= MAX_TABLE_FILTERS && table.filters.all { filter ->
            filter.label.isNotBlank() && filter.label.length <= MAX_TABLE_TEXT_CHARS &&
                filter.matchingRootRows.size <= table.records.size &&
                filter.matchingRootRows.distinct().size == filter.matchingRootRows.size &&
                filter.matchingRootRows.all { it in table.records.indices }
        }) { "Invalid parser-worker table filters." }
        table.records.forEach { record ->
            requireValidRecord(record, table.columns.size, presentation.summary.inputBytes, depth = 0)
        }
    }
    require(presentation.tables.map { it.id }.distinct().size == presentation.tables.size) {
        "Duplicate parser-worker table ID."
    }
    presentation.diagnostics.forEach { diagnostic ->
        require(diagnostic.code.length <= MAX_DIAGNOSTIC_CODE_CHARS) { "Parser-worker diagnostic code is too long." }
        require(diagnostic.message.length <= MAX_DIAGNOSTIC_MESSAGE_CHARS) { "Parser-worker diagnostic message is too long." }
    }
    presentation.componentGraph?.let { requireValidComponentGraph(it, presentation.summary.inputBytes) }
}

private fun isValidFormat(format: String): Boolean = format.length <= MAX_FORMAT_CHARS && format in supportedFormats

private fun isValidCapabilities(capabilities: List<String>): Boolean =
    capabilities.size <= MAX_PARSER_WORKER_CAPABILITIES &&
        capabilities.all { it.length <= MAX_CAPABILITY_CHARS && it in supportedCapabilities } &&
        capabilities.distinct().size == capabilities.size &&
        "summary" in capabilities

private fun isValidLegacyTabs(format: String, legacyTabs: List<String>): Boolean =
    legacyTabs.size <= MAX_PARSER_WORKER_LEGACY_TABS &&
        legacyTabs.all { it.length <= MAX_LEGACY_TAB_LABEL_CHARS && it in supportedLegacyTabsByFormat.getValue(format) } &&
        legacyTabs.distinct().size == legacyTabs.size

private fun isValidToolbar(format: String, toolbar: ParserWorkerToolbar): Boolean =
    toolbar.proposalIds.size <= MAX_TOOLBAR_PROPOSALS &&
        toolbar.proposalIds.all { it.length <= MAX_TOOLBAR_PROPOSAL_ID_CHARS && it in supportedToolbarProposalIds } &&
        toolbar.proposalIds.distinct().size == toolbar.proposalIds.size &&
        (format == "core_wasm" || toolbar.proposalIds.isEmpty()) &&
        when (format) {
            "core_wasm" -> toolbar.runnableExportName == null || toolbar.runnableExportName in setOf("_start", "_initialize")
            "wasm_component" -> toolbar.runnableExportName == null || toolbar.runnableExportName == "wasi:cli/run"
            else -> toolbar.runnableExportName == null
        } &&
        (toolbar.nativeImage == null ||
            (format in setOf("elf", "macho", "pe") && isValidNativeImage(toolbar.nativeImage)))

private fun isValidNativeImage(nativeImage: ParserWorkerNativeImage): Boolean =
    nativeImage.edition in setOf("community", "enterprise", "unknown") &&
        (nativeImage.version == null || nativeImage.version.length <= MAX_NATIVE_IMAGE_TEXT_CHARS) &&
        (nativeImage.platform == null || nativeImage.platform.length <= MAX_NATIVE_IMAGE_TEXT_CHARS)

private fun hasCoherentContent(
    format: String,
    capabilities: List<String>,
    ranges: List<ParserWorkerRange>,
    tables: List<ParserWorkerTable>,
    diagnostics: List<ParserWorkerDiagnostic>,
    componentGraph: ParserWorkerComponentGraph,
    toolbar: ParserWorkerToolbar,
): Boolean =
    (ranges.isEmpty() || "structural_ranges" in capabilities) &&
        (tables.isEmpty() || "tables" in capabilities) &&
        (diagnostics.isEmpty() || "diagnostics" in capabilities) &&
        ("component_dependency_graph" !in capabilities || format == "wasm_component") &&
        ((componentGraph.nodes.isEmpty() && componentGraph.edges.isEmpty()) ||
            (format == "wasm_component" && "component_dependency_graph" in capabilities)) &&
        (toolbar.nativeImage?.let { nativeImage ->
            nativeImage.hasSbom == tables.any { it.id == "SBOM" }
        } ?: true)

private fun isValidSummary(summary: ParserWorkerSummary): Boolean =
    summary.scopeMarker == "DedicatedWorkerGlobalScope" &&
        summary.documentAbsent &&
        summary.inputBytes >= 0 &&
        summary.sectionCount >= 0 &&
        summary.importCount >= 0 &&
        summary.definedFunctionCount >= 0 &&
        summary.codeBodyCount >= 0 &&
        summary.exportCount >= 0 &&
        summary.moduleCount >= 0 &&
        summary.proposalCount >= 0 &&
        isValidGraalWebImageMetadata(summary) &&
        summary.elapsedMs.isFinite() && summary.elapsedMs >= 0

private fun isValidGraalWebImageMetadata(summary: ParserWorkerSummary): Boolean =
    (summary.graalWebImage || summary.graalWebImageMatchedImportModules.isEmpty()) &&
        summary.graalWebImageMatchedImportModules.size <= MAX_TOOLCHAIN_IMPORT_MODULES &&
        summary.graalWebImageMatchedImportModules.all { it.isNotBlank() && it.length <= MAX_TOOLCHAIN_IMPORT_MODULE_CHARS } &&
        summary.graalWebImageMatchedImportModules.distinct().size == summary.graalWebImageMatchedImportModules.size

private fun isValidRange(range: ParserWorkerRange, inputBytes: Int): Boolean =
    range.offset >= 0 && range.size > 0 && range.size <= inputBytes && range.offset <= inputBytes - range.size

private fun isValidRecordRange(record: ParserWorkerRecord, inputBytes: Int): Boolean = when {
    record.offset == null && record.size == null -> true
    record.offset == null || record.size == null -> false
    else -> record.offset >= 0 && record.size > 0 && record.size <= inputBytes && record.offset <= inputBytes - record.size
}

private fun requireValidRecord(record: ParserWorkerRecord, columnCount: Int, inputBytes: Int, depth: Int) {
    require(record.key.length <= MAX_RECORD_KEY_CHARS) { "Parser-worker record key is too long." }
    require(record.value.length <= MAX_RECORD_VALUE_CHARS) { "Parser-worker record value is too long." }
    require(record.values.size in 1..MAX_RECORD_VALUES && record.values.all { it.length <= MAX_RECORD_VALUE_CHARS }) {
        "Invalid parser-worker record values."
    }
    require(record.values.size == columnCount) { "Parser-worker record values do not match table columns." }
    require(record.numericValues.size == columnCount && record.numericValues.all { value ->
        value == null || (value.isFinite() && value >= -9_007_199_254_740_991.0 && value <= 9_007_199_254_740_991.0)
    }) { "Invalid parser-worker record numeric values." }
    require(isValidRecordRange(record, inputBytes)) { "Invalid parser-worker record range." }
    require(record.actions.size <= MAX_RECORD_ACTIONS && record.actions.all { isValidAction(it, inputBytes) }) {
        "Invalid parser-worker record action."
    }
    require(record.children.size <= MAX_RECORD_CHILDREN) { "Too many parser-worker child records." }
    require(depth < MAX_RECORD_TREE_DEPTH || record.children.isEmpty()) { "Parser-worker record tree is too deep." }
    record.children.forEach { child -> requireValidRecord(child, columnCount, inputBytes, depth + 1) }
}

private fun isValidAction(action: ParserWorkerAction, inputBytes: Int): Boolean {
    if (
        action.kind.length > MAX_ACTION_KIND_CHARS ||
        (action.name?.length ?: 0) > MAX_ACTION_NAME_CHARS ||
        (action.contentType?.length ?: 0) > MAX_ACTION_CONTENT_TYPE_CHARS
    ) {
        return false
    }
    fun validRange(offset: Int?, size: Int?): Boolean =
        offset != null && size != null && offset >= 0 && size >= 0 && size <= inputBytes && offset <= inputBytes - size

    return when (action.kind) {
        "zip_entry" ->
            action.index == null && !action.name.isNullOrBlank() && action.offset == null && action.size == null &&
                validRange(action.dataOffset, action.compressedSize) && action.uncompressedSize != null &&
                action.uncompressedSize in 0..9_007_199_254_740_991L &&
                action.compressionMethod != null && action.compressionMethod in 0..0xffff && action.contentType == null && !action.openable
        "archive_member" ->
            action.index != null && action.index >= 0 && !action.name.isNullOrBlank() && validRange(action.offset, action.size) &&
                action.dataOffset == null && action.compressedSize == null && action.uncompressedSize == null &&
                action.compressionMethod == null && action.contentType == null
        "pcap_http_body" ->
            action.index != null && action.index >= 0 && !action.name.isNullOrBlank() && action.size != null && action.size > 0 &&
                validRange(action.offset, action.size) &&
                action.dataOffset == null && action.compressedSize == null && action.uncompressedSize == null &&
                action.compressionMethod == null
        else -> false
    }
}

private fun requireValidComponentGraph(graph: ParserWorkerComponentGraph, inputBytes: Int) {
    require(graph.nodes.all { isValidComponentGraphNode(it, inputBytes) }) { "Invalid parser-worker component graph node." }
    require(graph.nodes.map { it.id }.distinct().size == graph.nodes.size) { "Duplicate parser-worker component graph node ID." }
    val nodeIds = graph.nodes.mapTo(mutableSetOf()) { it.id }
    require(graph.edges.all { edge ->
        isValidComponentGraphEdge(edge) && edge.fromId in nodeIds && edge.toId in nodeIds
    }) { "Invalid parser-worker component graph edge." }
}

private fun isValidComponentGraphNode(node: ParserWorkerComponentGraphNode, inputBytes: Int): Boolean =
    node.id.isNotBlank() && node.id.length <= MAX_GRAPH_NODE_ID_CHARS &&
        node.kind.length <= MAX_GRAPH_NODE_KIND_CHARS && node.kind in supportedGraphNodeKinds &&
        node.label.length <= MAX_GRAPH_NODE_LABEL_CHARS &&
        (node.sublabel == null || node.sublabel.length <= MAX_GRAPH_NODE_LABEL_CHARS) &&
        isValidComponentGraphNodeRange(node, inputBytes)

private fun isValidComponentGraphNodeRange(node: ParserWorkerComponentGraphNode, inputBytes: Int): Boolean = when {
    node.offset == null && node.size == null -> true
    node.offset == null || node.size == null -> false
    else -> node.offset >= 0 && node.size > 0 && node.size <= inputBytes && node.offset <= inputBytes - node.size
}

private fun isValidComponentGraphEdge(edge: ParserWorkerComponentGraphEdge): Boolean =
    edge.fromId.isNotBlank() && edge.fromId.length <= MAX_GRAPH_NODE_ID_CHARS &&
        edge.toId.isNotBlank() && edge.toId.length <= MAX_GRAPH_NODE_ID_CHARS &&
        (edge.label == null || edge.label.length <= MAX_GRAPH_EDGE_LABEL_CHARS)

private fun isValidPartialMetadata(
    partial: Boolean,
    reasons: List<String>,
    emitted: Int,
    total: Int?,
    lowerBound: Int?,
): Boolean = when (reasons) {
    emptyList<String>() -> !partial && total == emitted && lowerBound == null
    listOf(BYTE_LIMIT_REASON) -> partial && total != null && total > emitted && lowerBound == null
    listOf(CONTENT_LIMIT_REASON), listOf(CONTENT_LIMIT_REASON, BYTE_LIMIT_REASON) ->
        partial && total != null && total > emitted && lowerBound == null
    listOf(RECORD_LIMIT_REASON),
    listOf(RECORD_LIMIT_REASON, BYTE_LIMIT_REASON),
    listOf(CONTENT_LIMIT_REASON, RECORD_LIMIT_REASON),
    listOf(CONTENT_LIMIT_REASON, RECORD_LIMIT_REASON, BYTE_LIMIT_REASON) ->
        partial && total == null && lowerBound != null && lowerBound > emitted
    else -> false
}

private fun wireObject(): dynamic = js("({})")

private fun decodeSummary(value: dynamic): ParserWorkerSummary? {
    if (!isPlainWireObject(value)) return null
    val summary = ParserWorkerSummary(
        value.scopeMarker as? String ?: return null,
        value.documentAbsent as? Boolean ?: return null,
        safeWireInt(value.inputBytes) ?: return null,
        safeWireInt(value.sectionCount) ?: return null,
        safeWireInt(value.importCount) ?: return null,
        safeWireInt(value.definedFunctionCount) ?: return null,
        safeWireInt(value.codeBodyCount) ?: return null,
        safeWireDouble(value.elapsedMs) ?: return null,
        safeWireInt(value.exportCount) ?: return null,
        safeWireInt(value.moduleCount) ?: return null,
        safeWireInt(value.proposalCount) ?: return null,
        value.graalWebImage as? Boolean ?: return null,
        decodeStrings(value.graalWebImageMatchedImportModules, MAX_TOOLCHAIN_IMPORT_MODULES, MAX_TOOLCHAIN_IMPORT_MODULE_CHARS)
            ?: return null,
    )
    return summary.takeIf(::isValidSummary)
}

private fun decodeRanges(value: dynamic, inputBytes: Int): List<ParserWorkerRange>? {
    val array = value as? Array<dynamic> ?: return null
    if (array.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) return null
    return array.map { item ->
        if (!isPlainWireObject(item)) return null
        val range = ParserWorkerRange(
            safeWireInt(item.offset) ?: return null,
            safeWireInt(item.size) ?: return null,
            safeString(item.label, MAX_RANGE_LABEL_CHARS) ?: return null,
        )
        range.takeIf { isValidRange(it, inputBytes) } ?: return null
    }
}

private fun decodeTables(value: dynamic, inputBytes: Int): List<ParserWorkerTable>? {
    val array = value as? Array<dynamic> ?: return null
    if (array.size > MAX_PARSER_WORKER_TABLES) return null
    return array.map { item ->
        if (!isPlainWireObject(item)) return null
        val columns = decodeStrings(item.columns, MAX_TABLE_COLUMNS, MAX_TABLE_COLUMN_CHARS) ?: return null
        if (columns.isEmpty()) return null
        val records = item.records as? Array<dynamic> ?: return null
        if (records.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) return null
        val budget = ParserWorkerTableDecodeBudget()
        ParserWorkerTable(
            id = safeString(item.id, MAX_TABLE_ID_CHARS) ?: return null,
            records = records.map { record ->
                decodeRecord(record, columns.size, inputBytes, budget = budget) ?: return null
            }.also { decoded ->
                if (!decoded.all { record ->
                        isValidRecord(record, columns.size, inputBytes)
                    }) return null
            },
            columns = columns,
            prologueText = if (item.prologueText == null) null else safeString(item.prologueText, MAX_TABLE_TEXT_CHARS) ?: return null,
            emptyMessage = if (item.emptyMessage == null) null else safeString(item.emptyMessage, MAX_TABLE_TEXT_CHARS) ?: return null,
            defaultSortColumn = if (item.defaultSortColumn == null) null else safeWireIntAllowNegativeOne(item.defaultSortColumn) ?: return null,
            defaultSortAscending = item.defaultSortAscending as? Boolean ?: return null,
            alwaysShowSearch = item.alwaysShowSearch as? Boolean ?: return null,
            searchPlaceholder = if (item.searchPlaceholder == null) null else safeString(item.searchPlaceholder, MAX_TABLE_TEXT_CHARS) ?: return null,
            showIdColumn = item.showIdColumn as? Boolean ?: return null,
            filters = decodeTableFilters(item.filters, records.size) ?: return null,
            defaultFilterShowAll = item.defaultFilterShowAll as? Boolean ?: return null,
        ).takeIf { table ->
            table.id.isNotBlank() &&
                (table.defaultSortColumn == null || table.defaultSortColumn == TableTabDescriptor.ID_COLUMN ||
                    table.defaultSortColumn in table.columns.indices)
        } ?: return null
    }.takeIf { tables -> tables.map { it.id }.distinct().size == tables.size }
}

private class ParserWorkerTableDecodeBudget(
    var remaining: Int = MAX_PARSER_WORKER_RECORDS_PER_COLLECTION,
)

private fun decodeRecord(
    value: dynamic,
    columnCount: Int,
    inputBytes: Int,
    depth: Int = 0,
    budget: ParserWorkerTableDecodeBudget,
): ParserWorkerRecord? {
    if (!isPlainWireObject(value)) return null
    if (depth > MAX_RECORD_TREE_DEPTH) return null
    if (budget.remaining == 0) return null
    budget.remaining--
    val values = decodeStrings(value.values, MAX_RECORD_VALUES, MAX_RECORD_VALUE_CHARS) ?: return null
    val numericValues = decodeNumericValues(value.numericValues, columnCount) ?: return null
    val children = value.children as? Array<dynamic> ?: return null
    if (children.size > MAX_RECORD_CHILDREN) return null
    val record = ParserWorkerRecord(
        safeString(value.key, MAX_RECORD_KEY_CHARS) ?: return null,
        safeString(value.value, MAX_RECORD_VALUE_CHARS) ?: return null,
        values,
        if (value.offset == null) null else safeWireInt(value.offset) ?: return null,
        if (value.size == null) null else safeWireInt(value.size) ?: return null,
        decodeActions(value.actions, inputBytes) ?: return null,
        children.map { child -> decodeRecord(child, columnCount, inputBytes, depth + 1, budget) ?: return null },
        numericValues,
    )
    return record.takeIf { isValidRecord(it, columnCount, inputBytes, depth) }
}

private fun isValidRecord(record: ParserWorkerRecord, columnCount: Int, inputBytes: Int, depth: Int = 0): Boolean =
    record.values.size == columnCount && isValidRecordRange(record, inputBytes) &&
        record.numericValues.size == columnCount &&
        record.numericValues.all { value ->
            value == null || (value.isFinite() && value >= -9_007_199_254_740_991.0 && value <= 9_007_199_254_740_991.0)
        } &&
        record.actions.size <= MAX_RECORD_ACTIONS && record.actions.all { isValidAction(it, inputBytes) } &&
        record.children.size <= MAX_RECORD_CHILDREN &&
        (depth < MAX_RECORD_TREE_DEPTH || record.children.isEmpty()) &&
        record.children.all { isValidRecord(it, columnCount, inputBytes, depth + 1) }

private fun decodeNumericValues(value: dynamic, columnCount: Int): List<Double?>? {
    val array = value as? Array<dynamic> ?: return null
    if (array.size != columnCount) return null
    return array.map { item ->
        if (item == null) null else safeWireFiniteNumber(item) ?: return null
    }
}

private fun decodeTableFilters(value: dynamic, rootRecordCount: Int): List<ParserWorkerTableFilter>? {
    val array = value as? Array<dynamic> ?: return null
    if (array.size > MAX_TABLE_FILTERS) return null
    return array.map { item ->
        if (!isPlainWireObject(item)) return null
        val matching = item.matchingRootRows as? Array<dynamic> ?: return null
        ParserWorkerTableFilter(
            label = safeString(item.label, MAX_TABLE_TEXT_CHARS)?.takeIf { it.isNotBlank() } ?: return null,
            matchingRootRows = matching.map { safeWireInt(it) ?: return null },
        ).takeIf { filter ->
            filter.matchingRootRows.distinct().size == filter.matchingRootRows.size &&
                filter.matchingRootRows.all { it in 0 until rootRecordCount }
        } ?: return null
    }
}

private fun decodeActions(value: dynamic, inputBytes: Int): List<ParserWorkerAction>? {
    val array = value as? Array<dynamic> ?: return null
    if (array.size > MAX_RECORD_ACTIONS) return null
    return array.map { item ->
        if (!isPlainWireObject(item)) return null
        ParserWorkerAction(
            kind = safeString(item.kind, MAX_ACTION_KIND_CHARS) ?: return null,
            index = if (item.index == null) null else safeWireInt(item.index) ?: return null,
            name = if (item.name == null) null else safeString(item.name, MAX_ACTION_NAME_CHARS) ?: return null,
            offset = if (item.offset == null) null else safeWireInt(item.offset) ?: return null,
            size = if (item.size == null) null else safeWireInt(item.size) ?: return null,
            dataOffset = if (item.dataOffset == null) null else safeWireInt(item.dataOffset) ?: return null,
            compressedSize = if (item.compressedSize == null) null else safeWireInt(item.compressedSize) ?: return null,
            uncompressedSize = if (item.uncompressedSize == null) null else safeWireLong(item.uncompressedSize) ?: return null,
            compressionMethod = if (item.compressionMethod == null) null else safeWireInt(item.compressionMethod) ?: return null,
            contentType = if (item.contentType == null) null else safeString(item.contentType, MAX_ACTION_CONTENT_TYPE_CHARS) ?: return null,
            openable = item.openable as? Boolean ?: return null,
        ).takeIf { isValidAction(it, inputBytes) } ?: return null
    }
}

private fun decodeComponentGraph(value: dynamic, inputBytes: Int): ParserWorkerComponentGraph? {
    if (value == null) return ParserWorkerComponentGraph(emptyList(), emptyList())
    if (!isPlainWireObject(value)) return null
    val nodes = value.nodes as? Array<dynamic> ?: return null
    val edges = value.edges as? Array<dynamic> ?: return null
    if (nodes.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION || edges.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) return null
    val decodedNodes = nodes.map { node ->
        if (!isPlainWireObject(node)) return null
        ParserWorkerComponentGraphNode(
            safeString(node.id, MAX_GRAPH_NODE_ID_CHARS) ?: return null,
            safeString(node.kind, MAX_GRAPH_NODE_KIND_CHARS) ?: return null,
            safeString(node.label, MAX_GRAPH_NODE_LABEL_CHARS) ?: return null,
            if (node.sublabel == null) null else safeString(node.sublabel, MAX_GRAPH_NODE_LABEL_CHARS) ?: return null,
            if (node.offset == null) null else safeWireInt(node.offset) ?: return null,
            if (node.size == null) null else safeWireInt(node.size) ?: return null,
        ).takeIf { isValidComponentGraphNode(it, inputBytes) } ?: return null
    }
    val decodedEdges = edges.map { edge ->
        if (!isPlainWireObject(edge)) return null
        ParserWorkerComponentGraphEdge(
            safeString(edge.fromId, MAX_GRAPH_NODE_ID_CHARS) ?: return null,
            safeString(edge.toId, MAX_GRAPH_NODE_ID_CHARS) ?: return null,
            if (edge.label == null) null else safeString(edge.label, MAX_GRAPH_EDGE_LABEL_CHARS) ?: return null,
        ).takeIf(::isValidComponentGraphEdge) ?: return null
    }
    val graph = ParserWorkerComponentGraph(decodedNodes, decodedEdges)
    return try {
        requireValidComponentGraph(graph, inputBytes)
        graph
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun decodeDiagnostics(value: dynamic): List<ParserWorkerDiagnostic>? {
    val array = value as? Array<dynamic> ?: return null
    if (array.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) return null
    return array.map { item ->
        if (!isPlainWireObject(item)) return null
        ParserWorkerDiagnostic(
            safeString(item.code, MAX_DIAGNOSTIC_CODE_CHARS) ?: return null,
            safeString(item.message, MAX_DIAGNOSTIC_MESSAGE_CHARS) ?: return null,
        )
    }
}

private fun decodeLegacyTabs(value: dynamic, format: String): List<String>? =
    decodeStrings(value, MAX_PARSER_WORKER_LEGACY_TABS, MAX_LEGACY_TAB_LABEL_CHARS)
        ?.takeIf { isValidLegacyTabs(format, it) }

private fun decodeToolbar(value: dynamic, format: String): ParserWorkerToolbar? {
    if (!isPlainWireObject(value)) return null
    val toolbar = ParserWorkerToolbar(
        if (value.runnableExportName == null) null else safeString(value.runnableExportName, MAX_TOOLBAR_RUNNABLE_EXPORT_CHARS) ?: return null,
        decodeStrings(value.proposalIds, MAX_TOOLBAR_PROPOSALS, MAX_TOOLBAR_PROPOSAL_ID_CHARS) ?: return null,
        decodeNativeImage(value.nativeImage),
    )
    return toolbar.takeIf { isValidToolbar(format, it) }
}

private fun decodeNativeImage(value: dynamic): ParserWorkerNativeImage? {
    if (value == null) return null
    if (!isPlainWireObject(value)) return null
    return ParserWorkerNativeImage(
        version = if (value.version == null) null else safeString(value.version, MAX_NATIVE_IMAGE_TEXT_CHARS) ?: return null,
        edition = safeString(value.edition, MAX_NATIVE_IMAGE_TEXT_CHARS) ?: return null,
        platform = if (value.platform == null) null else safeString(value.platform, MAX_NATIVE_IMAGE_TEXT_CHARS) ?: return null,
        hasSbom = value.hasSbom as? Boolean ?: return null,
    ).takeIf(::isValidNativeImage)
}

private fun decodeStrings(value: dynamic, maxItems: Int, maxLength: Int): List<String>? {
    val array = value as? Array<dynamic> ?: return null
    if (array.size > maxItems) return null
    return array.map { safeString(it, maxLength) ?: return null }
}

private fun safeString(value: dynamic, maxLength: Int): String? = (value as? String)?.takeIf { it.length <= maxLength }
internal fun isPlainWireObject(value: dynamic): Boolean = value != null && js("Object.prototype.toString.call(value) === '[object Object]'").unsafeCast<Boolean>()
internal fun safeWireInt(value: dynamic): Int? {
    if (jsTypeOf(value) != "number") return null
    val number = value.unsafeCast<Double>()
    return number.takeIf { it.isFinite() && it >= 0 && it <= Int.MAX_VALUE && it % 1.0 == 0.0 }?.toInt()
}
private fun safeWireIntAllowNegativeOne(value: dynamic): Int? {
    if (jsTypeOf(value) != "number") return null
    val number = value.unsafeCast<Double>()
    return when {
        number == -1.0 -> -1
        number.isFinite() && number >= 0 && number <= Int.MAX_VALUE && number % 1.0 == 0.0 -> number.toInt()
        else -> null
    }
}

private fun safeWireFiniteNumber(value: dynamic): Double? {
    if (jsTypeOf(value) != "number") return null
    return value.unsafeCast<Double>().takeIf {
        it.isFinite() && it >= -9_007_199_254_740_991.0 && it <= 9_007_199_254_740_991.0
    }
}
internal fun safeWireLong(value: dynamic): Long? {
    if (jsTypeOf(value) != "number") return null
    val number = value.unsafeCast<Double>()
    return number.takeIf { it.isFinite() && it >= 0 && it <= 9_007_199_254_740_991.0 && it % 1.0 == 0.0 }?.toLong()
}
internal fun safeWireDouble(value: dynamic): Double? = if (jsTypeOf(value) == "number") value.unsafeCast<Double>().takeIf { it.isFinite() && it >= 0 } else null
