package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.*
import org.jetbrains.hexana.mcp.model.data.*
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.WasmModule

fun WasmBinaryProvider.readDataSegments(
    segmentStartInclusive: Int,
    segmentEndExclusive: Int,
    includeExports: Boolean,
    includeGlobals: Boolean,
    minByteLength: Int?
): ListDataSegmentsResult {
    val wasmModule = requireNotNull(wasmModule)
    return bytes.readDataSegments(
        segmentStartInclusive = segmentStartInclusive,
        segmentEndExclusive = segmentEndExclusive,
        wasmModule = wasmModule,
        includeExports = includeExports,
        includeGlobals = includeGlobals,
        minByteLength = minByteLength
    )
}

fun WasmBinaryProvider.readDataSegmentsByRange(
    range: IntRange,
    includeExports: Boolean,
    includeGlobals: Boolean,
    minByteLength: Int,
): ListDataSegmentsResult = readDataSegments(
    segmentStartInclusive = range.first,
    segmentEndExclusive = if (range.isEmpty()) range.first else range.last + 1,
    includeExports = includeExports,
    includeGlobals = includeGlobals,
    minByteLength = minByteLength.takeIf { it > 0 },
)

fun CommonByteBuffer.readDataSegments(
    segmentRange: IntRange,
    wasmModule: WasmModule,
    includeExports: Boolean,
    includeGlobals: Boolean,
    minByteLength: Int?
): ListDataSegmentsResult {
    val start = segmentRange.first
    val endExclusive = if (segmentRange.isEmpty()) start else segmentRange.last + 1
    return readDataSegments(start, endExclusive, wasmModule, includeExports, includeGlobals, minByteLength)
}

/**
 * Tool-friendly entry point that adapts an MCP `minByteLength` (non-nullable, 0 = "no filter")
 * to the internal nullable semantics (null = "no filter", positive = minimum segment byte length).
 */
fun CommonByteBuffer.readDataSegmentsByRange(
    wasmModule: WasmModule,
    range: IntRange,
    includeExports: Boolean,
    includeGlobals: Boolean,
    minByteLength: Int,
): ListDataSegmentsResult = readDataSegments(
    segmentRange = range,
    wasmModule = wasmModule,
    includeExports = includeExports,
    includeGlobals = includeGlobals,
    minByteLength = minByteLength.takeIf { it > 0 },
)

fun CommonByteBuffer.readDataSegments(
    segmentStartInclusive: Int,
    segmentEndExclusive: Int,
    wasmModule: WasmModule,
    includeExports: Boolean,
    includeGlobals: Boolean,
    minByteLength: Int?
): ListDataSegmentsResult {
    val memory = listMemory(wasmModule)
    val exports = if (includeExports) {
        listExports(wasmModule)
    } else {
        emptyList()
    }
    val dataSegments0 = listDataSectionInternal(wasmModule).map { it.toDataSegmentResult(showPreview = true) }

    val start = segmentStartInclusive.coerceAtLeast(0)
    val endExclusive = segmentEndExclusive.coerceAtLeast(start).coerceAtMost(dataSegments0.size)
    val dataSegments1 = if (start >= endExclusive) {
        emptyList()
    } else {
        dataSegments0.subList(start, endExclusive)
    }

    val dataSegments = dataSegments1.filter { it.byteLength >= (minByteLength ?: 0) }
    val globals = if (includeGlobals) {
        listGlobals(wasmModule).map { it.toDataSegmentGlobalResult(exports) }
    } else {
        emptyList()
    }

    return ListDataSegmentsResult(
        memory = memory.map { it.toDataSegmentMemoryResult() },
        globals = globals,
        segments = dataSegments,
        exportedSymbols = exports.map { it.name },
        summary = DataSegmentsSummaryResult(
            totalSegments = dataSegments0.size,
            activeSegments = dataSegments0.count { it.mode == "active" },
            passiveSegments = dataSegments0.count { it.mode == "passive" },
            memories = memory.size
        )
    )
}

private fun MemoryRecord.toDataSegmentMemoryResult(): DataSegmentMemoryResult {
    return DataSegmentMemoryResult(
        memidx = memoryIdx,
        limits = limit.toDataSegmentMemoryLimitsResult(),
        definedIn = definedIn
    )
}

private fun Limits<*>.toDataSegmentMemoryLimitsResult(): DataSegmentMemoryLimitsResult {
    return DataSegmentMemoryLimitsResult(
        addressType = addressType,
        min = requireNotNull(min).toLongValue(),
        max = max.toLongValueOrNull(),
        hasMax = hasMax,
        range = range,
        flag = flag
    )
}

private fun Any.toLongValue(): Long {
    return when (this) {
        is Int -> toLong()
        is Long -> this
        else -> error("Unknown numeric type: ${this::class.qualifiedName}")
    }
}

private fun Any?.toLongValueOrNull(): Long? {
    return this?.toLongValue()
}

private fun DataEntry.toDataSegmentResult(showPreview: Boolean): DataSegmentResult {
    val preview = data.take(32).toByteArray()
    return DataSegmentResult(
        dataidx = index,
        mode = if (kind == 1) "passive" else "active",
        memory = memoryIndex?.toInt(),
        offsetExpr = expression,
        byteLength = data.size,
        hexPreview = preview.joinToString(separator = " ") { it.toHexString() }.takeIf { showPreview },
        utf8Preview = try {
            String(preview, Charsets.UTF_8).takeIf { showPreview }
        } catch (_: Exception) {
            null
        },
        rawAvailable = preview.isNotEmpty()
    )
}

private fun GlobalRecord.toDataSegmentGlobalResult(exports: List<ExportRecord>): DataSegmentGlobalResult {
    val exportedGlobals = exports.filter { it.kind == 0x3.toByte() }
    return DataSegmentGlobalResult(
        globalidx = globalIdx,
        module = prefix,
        name = name,
        type = type,
        mutable = mutability,
        initExpr = expression,
        definedIn = definedIn,
        exportedAs = exportedGlobals.filter { it.externIndex.toInt() == globalIdx }.map { it.name }
    )
}
