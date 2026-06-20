package org.jetbrains.hexana.mcp.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.*
import org.jetbrains.hexana.mcp.model.data.*
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.WasmModule

private val dataPayloadPolicyJson = Json {
    ignoreUnknownKeys = true
}

fun WasmBinaryProvider.readDataSegments(
    segmentStartInclusive: Int,
    segmentEndExclusive: Int,
    includeExports: Boolean,
    includeGlobals: Boolean,
    minByteLength: Int?
): ListDataSegmentsResult {
    val wasmModule = requireNotNull(wasmModule)
    return bytes.buildDataSegmentsResult(
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

fun WasmBinaryProvider.auditDataPayloadPolicy(
    policyText: String,
    pathLabel: String? = null,
    policyPathLabel: String? = null
): AuditDataPayloadPolicyResult {
    val wasmModule = requireNotNull(wasmModule)
    val policy = parseDataPayloadPolicy(policyText)
    val segments = bytes.listDataSectionInternal(wasmModule)
    val matches = segments
        .flatMap { segment ->
            policy.forbiddenPayloads.mapNotNull { forbidden ->
                if (!segment.containsUtf8Payload(forbidden.match)) return@mapNotNull null
                segment.toDataPayloadPolicyMatch(forbidden)
            }
        }
        .sortedWith(compareBy<DataPayloadPolicyMatchResult> { it.segmentIndex }.thenBy { it.policyEntryId })
    val matchedSegmentIndices = matches.map { it.segmentIndex }.toSet()
    val cleanSegments = segments
        .map { it.index }
        .filter { it !in matchedSegmentIndices }
        .sorted()
    val minimalFix = DataPayloadPolicyMinimalFixResult(
        removeOrReplaceSegments = matchedSegmentIndices.sorted()
    )
    val recommendedReport = DataPayloadPolicyRecommendedReportResult(
        policyId = policy.policyId,
        module = pathLabel,
        scannedSegments = segments.size,
        matches = matches.map { it.toReportMatch() },
        cleanSegments = cleanSegments,
        minimalFix = DataPayloadPolicyReportMinimalFixResult(
            removeOrReplaceSegments = minimalFix.removeOrReplaceSegments
        )
    )
    return AuditDataPayloadPolicyResult(
        policyId = policy.policyId,
        module = pathLabel,
        policyPath = policyPathLabel,
        scannedSegments = segments.size,
        matches = matches,
        cleanSegments = cleanSegments,
        minimalFix = minimalFix,
        recommendedReport = recommendedReport
    )
}

private fun CommonByteBuffer.buildDataSegmentsResult(
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

private fun parseDataPayloadPolicy(policyText: String): DataPayloadPolicy {
    val root = try {
        dataPayloadPolicyJson.parseToJsonElement(policyText).jsonObject
    } catch (error: Exception) {
        throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.INVALID_INPUT,
            "Invalid payload policy JSON",
            error
        )
    }
    val policyId = root.stringField("policy_id")
        ?: throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "Payload policy missing `policy_id`")
    val forbiddenPayloads = root["forbidden_payloads"]?.jsonArray.orEmpty()
        .mapIndexed { index, element ->
            val obj = element.jsonObject
            DataPayloadPolicyEntry(
                id = obj.stringField("id") ?: "forbidden-payload-$index",
                match = obj.stringField("match")
                    ?: throw HexanaAnalysisException(
                        HexanaAnalysisErrorCode.INVALID_INPUT,
                        "Payload policy entry missing `match`"
                    ),
                severity = obj.stringField("severity") ?: "unknown"
            )
        }
        .filter { it.match.isNotEmpty() }
    return DataPayloadPolicy(policyId = policyId, forbiddenPayloads = forbiddenPayloads)
}

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun DataEntry.containsUtf8Payload(payload: String): Boolean {
    if (payload.isEmpty()) return false
    return data.indexOf(payload.encodeToByteArray()) >= 0
}

private fun ByteArray.indexOf(needle: ByteArray): Int {
    if (needle.isEmpty()) return 0
    if (needle.size > size) return -1
    for (start in 0..(size - needle.size)) {
        var matched = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                matched = false
                break
            }
        }
        if (matched) return start
    }
    return -1
}

private fun DataEntry.toDataPayloadPolicyMatch(
    forbidden: DataPayloadPolicyEntry
): DataPayloadPolicyMatchResult {
    val mode = if (kind == 1) "passive" else "active"
    return DataPayloadPolicyMatchResult(
        policyEntryId = forbidden.id,
        forbiddenString = forbidden.match,
        severity = forbidden.severity,
        segmentIndex = index,
        mode = mode,
        memory = memoryIndex?.toInt() ?: if (mode == "active") 0 else null,
        offsetExpr = expression,
        byteLength = data.size,
        rawBytesConfirmed = true
    )
}

private fun DataPayloadPolicyMatchResult.toReportMatch(): DataPayloadPolicyReportMatchResult =
    DataPayloadPolicyReportMatchResult(
        policyEntryId = policyEntryId,
        forbiddenString = forbiddenString,
        severity = severity,
        segmentIndex = segmentIndex,
        mode = mode,
        memory = memory.toJsonOrNull(),
        offsetExpr = offsetExpr.toJsonOrNull(),
        byteLength = byteLength,
        rawBytesConfirmed = rawBytesConfirmed
    )

private fun Int?.toJsonOrNull(): JsonElement =
    this?.let(::JsonPrimitive) ?: JsonNull

private fun String?.toJsonOrNull(): JsonElement =
    this?.let(::JsonPrimitive) ?: JsonNull

private data class DataPayloadPolicy(
    val policyId: String,
    val forbiddenPayloads: List<DataPayloadPolicyEntry>
)

private data class DataPayloadPolicyEntry(
    val id: String,
    val match: String,
    val severity: String
)

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
