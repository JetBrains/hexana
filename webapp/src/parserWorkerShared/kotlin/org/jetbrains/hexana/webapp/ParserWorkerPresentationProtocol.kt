package org.jetbrains.hexana.webapp

/** The serialised JSON representation of a worker success is never larger than this value. */
internal const val MAX_PARSER_WORKER_WIRE_BYTES = 10 * 1024 * 1024
internal const val MAX_PARSER_WORKER_RECORDS_PER_COLLECTION = 10_000
internal const val PARSER_WORKER_PROTOCOL_VERSION = 2

internal data class ParserWorkerSummary(
    val scopeMarker: String,
    val documentAbsent: Boolean,
    val inputBytes: Int,
    val sectionCount: Int,
    val importCount: Int,
    val definedFunctionCount: Int,
    val codeBodyCount: Int,
    val elapsedMs: Double,
)

internal data class ParserWorkerRange(val offset: Int, val size: Int, val label: String)
internal data class ParserWorkerRecord(val key: String, val value: String)
internal data class ParserWorkerTable(val id: String, val records: List<ParserWorkerRecord>)
internal data class ParserWorkerDiagnostic(val code: String, val message: String)

internal data class ParserWorkerPresentation(
    val format: String,
    val capabilities: List<String>,
    val summary: ParserWorkerSummary,
    val ranges: List<ParserWorkerRange> = emptyList(),
    val tables: List<ParserWorkerTable> = emptyList(),
    val diagnostics: List<ParserWorkerDiagnostic> = emptyList(),
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
 * Produces the deterministic response-record prefix. JSON is measured exactly as UTF-8 before it is posted.
 * The binary search probes a monotonic prefix length, avoiding repeated quadratic full-payload serialisation.
 */
internal fun boundParserWorkerSuccess(
    requestId: String,
    presentation: ParserWorkerPresentation,
    maxWireBytes: Int = MAX_PARSER_WORKER_WIRE_BYTES,
): BoundedParserWorkerSuccess {
    require(maxWireBytes > 0)
    val recordLimited = presentation.ranges.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION ||
        presentation.tables.any { it.records.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION } ||
        presentation.diagnostics.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION
    val cappedTables = presentation.tables.map { table ->
        table.copy(records = table.records.take(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION))
    }
    val capped = presentation.copy(
        ranges = presentation.ranges.take(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION),
        tables = cappedTables,
        diagnostics = presentation.diagnostics.take(MAX_PARSER_WORKER_RECORDS_PER_COLLECTION),
    )
    val total = presentation.recordCount()
    val cappedTotal = capped.recordCount()
    fun candidate(keep: Int, byteLimited: Boolean): Pair<dynamic, ParserWorkerPartial> {
        val bounded = capped.takeDeterministicPrefix(keep)
        val partial = ParserWorkerPartial(
            partial = recordLimited || byteLimited,
            reasons = buildList {
                if (recordLimited) add("record_limit")
                if (byteLimited) add("byte_limit")
            },
            emittedCount = bounded.recordCount(),
            totalCount = if (recordLimited) null else total,
            totalCountLowerBound = if (recordLimited) total else null,
        )
        return Pair(encodeSuccessWire(requestId, bounded, partial), partial)
    }
    val initial = candidate(cappedTotal, false)
    if (utf8WireBytes(initial.first) <= maxWireBytes) {
        return BoundedParserWorkerSuccess(capped, initial.second, utf8WireBytes(initial.first))
    }
    var low = 0
    var high = cappedTotal
    while (low < high) {
        val middle = (low + high + 1) ushr 1
        if (utf8WireBytes(candidate(middle, true).first) <= maxWireBytes) low = middle else high = middle - 1
    }
    val bounded = candidate(low, true)
    return BoundedParserWorkerSuccess(
        capped.takeDeterministicPrefix(low),
        bounded.second,
        utf8WireBytes(bounded.first),
    )
}

internal fun encodeBoundedParserWorkerSuccess(requestId: String, presentation: ParserWorkerPresentation): dynamic {
    val bounded = boundParserWorkerSuccess(requestId, presentation)
    val wire = encodeSuccessWire(requestId, bounded.presentation, bounded.partial)
    check(utf8WireBytes(wire) == bounded.wireBytes)
    check(bounded.wireBytes <= MAX_PARSER_WORKER_WIRE_BYTES)
    return wire
}

private fun ParserWorkerPresentation.recordCount(): Int =
    ranges.size + tables.sumOf { it.records.size } + diagnostics.size

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
    return copy(ranges = ranges, tables = tables, diagnostics = diagnostics)
}

internal fun decodeParserWorkerSuccess(value: dynamic, requestId: String): BoundedParserWorkerSuccess? {
    if (!isPlainWireObject(value)) return null
    val wireBytes = try {
        utf8WireBytes(value)
    } catch (_: Throwable) {
        return null
    }
    if (wireBytes > MAX_PARSER_WORKER_WIRE_BYTES) return null
    if (value.protocolVersion != PARSER_WORKER_PROTOCOL_VERSION || value.kind != "success" || value.requestId != requestId) return null
    val presentation = value.presentation
    val partial = value.partial
    if (!isPlainWireObject(presentation) || !isPlainWireObject(partial)) return null
    val format = presentation.format as? String ?: return null
    if (format !in setOf("core_wasm", "wasm_component", "unknown") || format.length > 64) return null
    val capabilities = decodeStrings(presentation.capabilities, 32, 64) ?: return null
    if (capabilities.any { it !in setOf("summary", "structural_ranges", "tables", "diagnostics") }) return null
    val summary = decodeSummary(presentation.summary) ?: return null
    val ranges = decodeRanges(presentation.ranges) ?: return null
    val tables = decodeTables(presentation.tables) ?: return null
    val diagnostics = decodeDiagnostics(presentation.diagnostics) ?: return null
    val reasons = decodeStrings(partial.reasons, 2, 32) ?: return null
    if (reasons.any { it !in setOf("record_limit", "byte_limit") } || reasons.distinct().size != reasons.size) return null
    val isPartial = partial.partial as? Boolean ?: return null
    val emitted = safeWireInt(partial.emittedCount) ?: return null
    val total = if (partial.totalCount == null) null else safeWireInt(partial.totalCount) ?: return null
    val lowerBound = if (partial.totalCountLowerBound == null) null else safeWireInt(partial.totalCountLowerBound) ?: return null
    val actual = ranges.size + tables.sumOf { it.records.size } + diagnostics.size
    if (emitted != actual || emitted > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION * (tables.size + 2)) return null
    if (isPartial != reasons.isNotEmpty()) return null
    if (!isPartial && (total != actual || lowerBound != null)) return null
    if (isPartial && total == null && (lowerBound == null || lowerBound < emitted)) return null
    if (total != null && total < emitted) return null
    return BoundedParserWorkerSuccess(
        ParserWorkerPresentation(format, capabilities, summary, ranges, tables, diagnostics),
        ParserWorkerPartial(isPartial, reasons, emitted, total, lowerBound),
        wireBytes,
    )
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
    }
    value.presentation.ranges = presentation.ranges.map { range ->
        val wire = wireObject()
        wire.offset = range.offset; wire.size = range.size; wire.label = range.label
        wire
    }.toTypedArray()
    value.presentation.tables = presentation.tables.map { table ->
        val wire = wireObject()
        wire.id = table.id
        wire.records = table.records.map { record ->
            val item = wireObject()
            item.key = record.key; item.value = record.value
            item
        }.toTypedArray()
        wire
    }.toTypedArray()
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

private fun wireObject(): dynamic = js("({})")

private fun decodeSummary(value: dynamic): ParserWorkerSummary? {
    if (!isPlainWireObject(value)) return null
    val marker = value.scopeMarker as? String ?: return null
    val documentAbsent = value.documentAbsent as? Boolean ?: return null
    if (marker != "DedicatedWorkerGlobalScope" || !documentAbsent) return null
    return ParserWorkerSummary(marker, documentAbsent, safeWireInt(value.inputBytes) ?: return null,
        safeWireInt(value.sectionCount) ?: return null, safeWireInt(value.importCount) ?: return null,
        safeWireInt(value.definedFunctionCount) ?: return null, safeWireInt(value.codeBodyCount) ?: return null,
        safeWireDouble(value.elapsedMs) ?: return null)
}

private fun decodeRanges(value: dynamic): List<ParserWorkerRange>? {
    val array = value as? Array<dynamic> ?: return null
    if (array.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) return null
    return array.map { item ->
        if (!isPlainWireObject(item)) return null
        ParserWorkerRange(safeWireInt(item.offset) ?: return null, safeWireInt(item.size) ?: return null,
            safeString(item.label, 256) ?: return null)
    }
}

private fun decodeTables(value: dynamic): List<ParserWorkerTable>? {
    val array = value as? Array<dynamic> ?: return null
    if (array.size > 32) return null
    return array.map { item ->
        if (!isPlainWireObject(item)) return null
        val records = item.records as? Array<dynamic> ?: return null
        if (records.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) return null
        ParserWorkerTable(safeString(item.id, 64) ?: return null, records.map { record ->
            if (!isPlainWireObject(record)) return null
            ParserWorkerRecord(safeString(record.key, 1024) ?: return null, safeString(record.value, 16_384) ?: return null)
        })
    }
}

private fun decodeDiagnostics(value: dynamic): List<ParserWorkerDiagnostic>? {
    val array = value as? Array<dynamic> ?: return null
    if (array.size > MAX_PARSER_WORKER_RECORDS_PER_COLLECTION) return null
    return array.map { item ->
        if (!isPlainWireObject(item)) return null
        ParserWorkerDiagnostic(safeString(item.code, 64) ?: return null, safeString(item.message, 1024) ?: return null)
    }
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
internal fun safeWireDouble(value: dynamic): Double? = if (jsTypeOf(value) == "number") value.unsafeCast<Double>().takeIf { it.isFinite() && it >= 0 } else null
