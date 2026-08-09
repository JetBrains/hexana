package org.jetbrains.hexana.webapp

/** The serialised JSON representation of a worker success is never larger than this value. */
internal const val MAX_PARSER_WORKER_WIRE_BYTES = 10 * 1024 * 1024
internal const val MAX_PARSER_WORKER_RECORDS_PER_COLLECTION = 10_000
internal const val MAX_PARSER_WORKER_REQUEST_ID_CHARS = 128
/** Fixed schema collections are rejected on the producer side instead of silently changing their identity. */
internal const val MAX_PARSER_WORKER_CAPABILITIES = 32
internal const val MAX_PARSER_WORKER_TABLES = 32
internal const val MAX_PARSER_WORKER_LEGACY_TABS = 16
internal const val PARSER_WORKER_PROTOCOL_VERSION = 4

private const val MAX_FORMAT_CHARS = 64
private const val MAX_CAPABILITY_CHARS = 64
private const val MAX_RANGE_LABEL_CHARS = 256
private const val MAX_TABLE_ID_CHARS = 64
private const val MAX_TABLE_COLUMNS = 16
private const val MAX_TABLE_COLUMN_CHARS = 64
private const val MAX_RECORD_KEY_CHARS = 1_024
private const val MAX_RECORD_VALUE_CHARS = 16_384
private const val MAX_RECORD_VALUES = MAX_TABLE_COLUMNS
private const val MAX_DIAGNOSTIC_CODE_CHARS = 64
private const val MAX_DIAGNOSTIC_MESSAGE_CHARS = 1_024
private const val MAX_PARTIAL_REASONS = 2
private const val MAX_PARTIAL_REASON_CHARS = 32
private const val MAX_GRAPH_NODE_ID_CHARS = 128
private const val MAX_GRAPH_NODE_KIND_CHARS = 32
private const val MAX_GRAPH_NODE_LABEL_CHARS = 1_024
private const val MAX_GRAPH_EDGE_LABEL_CHARS = 1_024
private const val MAX_LEGACY_TAB_LABEL_CHARS = 64

private val supportedFormats = setOf("core_wasm", "wasm_component", "unknown")
private val supportedCapabilities = setOf("summary", "structural_ranges", "tables", "diagnostics", "component_dependency_graph")
private val supportedGraphNodeKinds = setOf("import", "module", "nested_component", "core_instance", "instance", "export")
private val supportedLegacyTabsByFormat = mapOf(
    "core_wasm" to setOf("Data", "Tags", "Exceptions", "Custom", "Top", "Monos", "Garbage", "Wat", "WAT (virtualized)"),
    "wasm_component" to setOf("Components", "Wat", "WAT (virtualized)"),
    "unknown" to emptySet(),
)
private const val RECORD_LIMIT_REASON = "record_limit"
private const val BYTE_LIMIT_REASON = "byte_limit"

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
)

internal data class ParserWorkerRange(val offset: Int, val size: Int, val label: String)
internal data class ParserWorkerRecord(
    val key: String,
    val value: String,
    val values: List<String> = listOf(key, value),
    val offset: Int? = null,
    val size: Int? = null,
)
internal data class ParserWorkerTable(
    val id: String,
    val records: List<ParserWorkerRecord>,
    val columns: List<String> = listOf("Key", "Value"),
)
internal data class ParserWorkerDiagnostic(val code: String, val message: String)
internal data class ParserWorkerComponentGraphNode(
    val id: String,
    val kind: String,
    val label: String,
    val sublabel: String? = null,
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

internal data class ParserWorkerPresentation(
    val format: String,
    val capabilities: List<String>,
    val summary: ParserWorkerSummary,
    val ranges: List<ParserWorkerRange> = emptyList(),
    val tables: List<ParserWorkerTable> = emptyList(),
    val diagnostics: List<ParserWorkerDiagnostic> = emptyList(),
    val componentGraph: ParserWorkerComponentGraph? = null,
    val legacyTabs: List<String> = emptyList(),
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

/**
 * Produces a deterministic response-record prefix. JSON is measured exactly as UTF-8 before it is posted.
 * The binary search probes a monotonic prefix length, avoiding repeated quadratic full-payload serialisation.
 */
internal fun boundParserWorkerSuccess(
    requestId: String,
    presentation: ParserWorkerPresentation,
    maxWireBytes: Int = MAX_PARSER_WORKER_WIRE_BYTES,
): BoundedParserWorkerSuccess {
    require(maxWireBytes > 0)
    require(isValidParserWorkerRequestId(requestId)) { "Invalid parser-worker request ID." }
    requireValidProducerPresentation(presentation)

    val recordLimited = presentation.ranges.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION ||
        presentation.tables.any { it.records.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION } ||
        presentation.diagnostics.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION ||
        (presentation.componentGraph?.nodes?.size ?: 0) > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION ||
        (presentation.componentGraph?.edges?.size ?: 0) > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION
    val capped = presentation.copy(
        ranges = presentation.ranges.take(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION),
        tables = presentation.tables.map { table ->
            table.copy(records = table.records.take(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION))
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
    val total = presentation.responseRecordCount()
    val cappedTotal = capped.responseRecordCount()

    fun candidate(keep: Int, byteLimited: Boolean): Pair<ParserWorkerPresentation, ParserWorkerPartial> {
        val bounded = capped.takeDeterministicPrefix(keep)
        return Pair(
            bounded,
            partialMetadata(recordLimited, byteLimited, bounded.responseRecordCount(), total),
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

internal fun encodeBoundedParserWorkerSuccess(requestId: String, presentation: ParserWorkerPresentation): dynamic {
    require(isValidParserWorkerRequestId(requestId)) { "Invalid parser-worker request ID." }
    val bounded = boundParserWorkerSuccess(requestId, presentation)
    return encodeSuccessWire(requestId, bounded.presentation, bounded.partial)
}

private fun partialMetadata(
    recordLimited: Boolean,
    byteLimited: Boolean,
    emittedCount: Int,
    totalCount: Int,
): ParserWorkerPartial = when {
    recordLimited -> ParserWorkerPartial(
        partial = true,
        reasons = if (byteLimited) listOf(RECORD_LIMIT_REASON, BYTE_LIMIT_REASON) else listOf(RECORD_LIMIT_REASON),
        emittedCount = emittedCount,
        totalCount = null,
        totalCountLowerBound = totalCount,
    )
    byteLimited -> ParserWorkerPartial(
        partial = true,
        reasons = listOf(BYTE_LIMIT_REASON),
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
    val count = ranges.size.toLong() + tables.sumOf { it.records.size.toLong() } + diagnostics.size.toLong() +
        (componentGraph?.nodes?.size ?: 0) + (componentGraph?.edges?.size ?: 0)
    require(count <= Int.MAX_VALUE) { "Parser-worker record count exceeds the wire representation." }
    return count.toInt()
}

private fun ParserWorkerPresentation.takeDeterministicPrefix(count: Int): ParserWorkerPresentation {
    var remaining = count
    val ranges = ranges.take(minOf(remaining, ranges.size))
    remaining -= ranges.size
    val tables = tables.map { table ->
        val emitted = minOf(remaining, table.records.size)
        remaining -= emitted
        table.copy(records = table.records.take(emitted))
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
        val componentGraph = decodeComponentGraph(presentation.componentGraph) ?: return null
        val legacyTabs = decodeLegacyTabs(presentation.legacyTabs, format) ?: return null
        if (!hasCoherentContent(format, capabilities, ranges, tables, diagnostics, componentGraph)) return null

        val reasons = decodeStrings(partial.reasons, MAX_PARTIAL_REASONS, MAX_PARTIAL_REASON_CHARS) ?: return null
        val isPartial = partial.partial as? Boolean ?: return null
        val emitted = safeWireInt(partial.emittedCount) ?: return null
        val total = if (partial.totalCount == null) null else safeWireInt(partial.totalCount) ?: return null
        val lowerBound = if (partial.totalCountLowerBound == null) null else safeWireInt(partial.totalCountLowerBound) ?: return null
        val actual = ranges.size + tables.sumOf { it.records.size } + diagnostics.size +
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
    value.presentation.capabilities = presentation.capabilities.toTypedArray()
    value.presentation.summary = js("({})")
    with(value.presentation.summary) {
        scopeMarker = presentation.summary.scopeMarker; documentAbsent = presentation.summary.documentAbsent
        inputBytes = presentation.summary.inputBytes; sectionCount = presentation.summary.sectionCount
        importCount = presentation.summary.importCount; definedFunctionCount = presentation.summary.definedFunctionCount
        codeBodyCount = presentation.summary.codeBodyCount; elapsedMs = presentation.summary.elapsedMs
        exportCount = presentation.summary.exportCount; moduleCount = presentation.summary.moduleCount
        proposalCount = presentation.summary.proposalCount
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
        wire.records = table.records.map { record ->
            val item = wireObject()
            item.key = record.key; item.value = record.value; item.values = record.values.toTypedArray()
            item.offset = record.offset; item.size = record.size
            item
        }.toTypedArray()
        wire
    }.toTypedArray()
    value.presentation.componentGraph = presentation.componentGraph?.let { graph ->
        val wire = wireObject()
        wire.nodes = graph.nodes.map { node ->
            val item = wireObject()
            item.id = node.id; item.kind = node.kind; item.label = node.label; item.sublabel = node.sublabel
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

private fun requireValidProducerPresentation(presentation: ParserWorkerPresentation) {
    require(isValidFormat(presentation.format)) { "Unsupported parser-worker format." }
    require(isValidCapabilities(presentation.capabilities)) { "Invalid parser-worker capability collection." }
    require(isValidLegacyTabs(presentation.format, presentation.legacyTabs)) { "Invalid parser-worker legacy tabs." }
    require(presentation.tables.size <= MAX_PARSER_WORKER_TABLES) { "Too many parser-worker tables." }
    require(isValidSummary(presentation.summary)) { "Invalid parser-worker summary." }
    require(hasCoherentContent(presentation.format, presentation.capabilities, presentation.ranges, presentation.tables, presentation.diagnostics, presentation.componentGraph ?: ParserWorkerComponentGraph(emptyList(), emptyList()))) {
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
        table.records.forEach { record ->
            require(record.key.length <= MAX_RECORD_KEY_CHARS) { "Parser-worker record key is too long." }
            require(record.value.length <= MAX_RECORD_VALUE_CHARS) { "Parser-worker record value is too long." }
            require(record.values.size in 1..MAX_RECORD_VALUES && record.values.all { it.length <= MAX_RECORD_VALUE_CHARS }) {
                "Invalid parser-worker record values."
            }
            require(record.values.size == table.columns.size) { "Parser-worker record values do not match table columns." }
            require(isValidRecordRange(record, presentation.summary.inputBytes)) { "Invalid parser-worker record range." }
        }
    }
    require(presentation.tables.map { it.id }.distinct().size == presentation.tables.size) {
        "Duplicate parser-worker table ID."
    }
    presentation.diagnostics.forEach { diagnostic ->
        require(diagnostic.code.length <= MAX_DIAGNOSTIC_CODE_CHARS) { "Parser-worker diagnostic code is too long." }
        require(diagnostic.message.length <= MAX_DIAGNOSTIC_MESSAGE_CHARS) { "Parser-worker diagnostic message is too long." }
    }
    presentation.componentGraph?.let(::requireValidComponentGraph)
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

private fun hasCoherentContent(
    format: String,
    capabilities: List<String>,
    ranges: List<ParserWorkerRange>,
    tables: List<ParserWorkerTable>,
    diagnostics: List<ParserWorkerDiagnostic>,
    componentGraph: ParserWorkerComponentGraph,
): Boolean =
    (ranges.isEmpty() || "structural_ranges" in capabilities) &&
        (tables.isEmpty() || "tables" in capabilities) &&
        (diagnostics.isEmpty() || "diagnostics" in capabilities) &&
        ("component_dependency_graph" !in capabilities || format == "wasm_component") &&
        ((componentGraph.nodes.isEmpty() && componentGraph.edges.isEmpty()) ||
            (format == "wasm_component" && "component_dependency_graph" in capabilities))

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
        summary.elapsedMs.isFinite() && summary.elapsedMs >= 0

private fun isValidRange(range: ParserWorkerRange, inputBytes: Int): Boolean =
    range.offset >= 0 && range.size > 0 && range.size <= inputBytes && range.offset <= inputBytes - range.size

private fun isValidRecordRange(record: ParserWorkerRecord, inputBytes: Int): Boolean = when {
    record.offset == null && record.size == null -> true
    record.offset == null || record.size == null -> false
    else -> record.offset >= 0 && record.size > 0 && record.size <= inputBytes && record.offset <= inputBytes - record.size
}

private fun requireValidComponentGraph(graph: ParserWorkerComponentGraph) {
    require(graph.nodes.all(::isValidComponentGraphNode)) { "Invalid parser-worker component graph node." }
    require(graph.nodes.map { it.id }.distinct().size == graph.nodes.size) { "Duplicate parser-worker component graph node ID." }
    val nodeIds = graph.nodes.mapTo(mutableSetOf()) { it.id }
    require(graph.edges.all { edge ->
        isValidComponentGraphEdge(edge) && edge.fromId in nodeIds && edge.toId in nodeIds
    }) { "Invalid parser-worker component graph edge." }
}

private fun isValidComponentGraphNode(node: ParserWorkerComponentGraphNode): Boolean =
    node.id.isNotBlank() && node.id.length <= MAX_GRAPH_NODE_ID_CHARS &&
        node.kind.length <= MAX_GRAPH_NODE_KIND_CHARS && node.kind in supportedGraphNodeKinds &&
        node.label.length <= MAX_GRAPH_NODE_LABEL_CHARS &&
        (node.sublabel == null || node.sublabel.length <= MAX_GRAPH_NODE_LABEL_CHARS)

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
    listOf(RECORD_LIMIT_REASON), listOf(RECORD_LIMIT_REASON, BYTE_LIMIT_REASON) ->
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
        ParserWorkerTable(safeString(item.id, MAX_TABLE_ID_CHARS) ?: return null, records.map { record ->
            if (!isPlainWireObject(record)) return null
            val values = decodeStrings(record.values, MAX_RECORD_VALUES, MAX_RECORD_VALUE_CHARS) ?: return null
            ParserWorkerRecord(
                safeString(record.key, MAX_RECORD_KEY_CHARS) ?: return null,
                safeString(record.value, MAX_RECORD_VALUE_CHARS) ?: return null,
                values,
                if (record.offset == null) null else safeWireInt(record.offset) ?: return null,
                if (record.size == null) null else safeWireInt(record.size) ?: return null,
            )
        }.also { decoded ->
            if (!decoded.all { it.values.size == columns.size && isValidRecordRange(it, inputBytes) }) return null
        }, columns).takeIf { it.id.isNotBlank() } ?: return null
    }.takeIf { tables -> tables.map { it.id }.distinct().size == tables.size }
}

private fun decodeComponentGraph(value: dynamic): ParserWorkerComponentGraph? {
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
        ).takeIf(::isValidComponentGraphNode) ?: return null
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
        requireValidComponentGraph(graph)
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
internal fun safeWireDouble(value: dynamic): Double? = if (jsTypeOf(value) == "number") value.unsafeCast<Double>().takeIf { it.isFinite() && it >= 0 } else null
