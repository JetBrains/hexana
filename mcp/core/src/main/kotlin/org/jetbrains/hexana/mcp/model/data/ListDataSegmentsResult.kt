package org.jetbrains.hexana.mcp.model.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * {
 *   "memory": [
 *     {
 *       "memidx": 0,
 *       "limits": {
 *         "min": 2,
 *         "max": 4,
 *         "unit": "pages"
 *       },
 *       "definedIn": "MemorySection"
 *     }
 *   ],
 *
 *   "globals": [
 *     {
 *       "globalidx": 0,
 *       "source": "import",
 *       "module": "env",
 *       "name": "__heap_base",
 *       "type": "i32",
 *       "mutable": false,
 *       "initExpr": "imported",
 *       "exportedAs": "heap_base"
 *     },
 *     {
 *       "globalidx": 1,
 *       "source": "global",
 *       "type": "i32",
 *       "mutable": false,
 *       "initExpr": "i32.const 2048"
 *     }
 *   ],
 *
 *   "linkingMetadata": {
 *     "segmentInfo": {
 *       "0": { "name": ".rodata", "alignment": 4 },
 *       "1": { "name": ".data", "alignment": 4 }
 *     },
 *     "exportedSymbols": ["_start", "main"]
 *   },
 *
 *   "segments": [
 *     {
 *       "dataidx": 0,
 *       "mode": "active",
 *       "memory": 0,
 *       "offsetExpr": {
 *         "raw": "global.get 0",
 *         "resolved": {
 *           "viaGlobal": 0,
 *           "import": {
 *             "module": "env",
 *             "name": "__heap_base"
 *           },
 *           "note": "Exact value unknown at compile time"
 *         }
 *       },
 *       "byteLength": 180,
 *       "hexPreview": "48 65 6C 6C 6F 2C 20 52 75 73 74 21 00 ...",
 *       "utf8Preview": "Hello, Rust!",
 *       "probableTypeHint": "string-table",
 *       "repr": "(data (global.get 0) \"Hello, Rust!\\00...\")",
 *       "linking": {
 *         "segmentName": ".rodata",
 *         "alignment": 4
 *       }
 *     },
 *
 *     {
 *       "dataidx": 1,
 *       "mode": "active",
 *       "memory": 0,
 *       "offsetExpr": {
 *         "raw": "i32.const 4096",
 *         "resolved": 4096
 *       },
 *       "byteLength": 64,
 *       "hexPreview": "0C 00 00 00 12 00 00 00 19 00 00 00 ...",
 *       "utf8Preview": null,
 *       "probableTypeHint": "vtable",
 *       "alignmentHint": 4,
 *       "repr": "(data (i32.const 4096) ...)",
 *       "linking": {
 *         "segmentName": ".data",
 *         "alignment": 4
 *       }
 *     },
 *
 *     {
 *       "dataidx": 2,
 *       "mode": "passive",
 *       "memory": 0,
 *       "offsetExpr": null,
 *       "byteLength": 1024,
 *       "hexPreview": "00 61 73 6D 03 00 00 00 ...",
 *       "utf8Preview": null,
 *       "probableTypeHint": "embedded-resource",
 *       "repr": "(data passive ...)"
 *     }
 *   ],
 *
 *   "summary": {
 *     "totalSegments": 3,
 *     "activeSegments": 2,
 *     "passiveSegments": 1,
 *     "memories": 1,
 *     "globalsUsedForOffsets": [
 *       {
 *         "globalidx": 0,
 *         "importedFrom": { "module": "env", "name": "__heap_base" }
 *       }
 *     ]
 *   }
 * }
 */
@Serializable
data class ListDataSegmentsResult(
    val memory: List<DataSegmentMemoryResult>,
    val globals: List<DataSegmentGlobalResult>,
    val segments: List<DataSegmentResult>,
    val linkingMetadata: DataSegmentLinkingMetadataResult? = null,
    val exportedSymbols: List<String>,
    val summary: DataSegmentsSummaryResult
)

// TODO
@Serializable
class DataSegmentLinkingMetadataResult

@Serializable
data class DataSegmentMemoryResult(
    val memidx: Int,
    val limits: DataSegmentMemoryLimitsResult,
    val definedIn: String
)

@Serializable
data class DataSegmentMemoryLimitsResult(
    val addressType: String,
    val min: Long,
    val max: Long? = null,
    val hasMax: Boolean,
    val range: String,
    val flag: String
)

@Serializable
data class DataSegmentGlobalResult(
    val globalidx: Int,
    val module: String? = null,
    val name: String? = null,
    val type: String,
    val mutable: Boolean,
    val initExpr: String? = null,
    val definedIn: String,
    val exportedAs: List<String>
)

@Serializable
data class DataSegmentResult(
    val dataidx: Int,
    val mode: String,
    val memory: Int? = null,
    val offsetExpr: String? = null,
    val byteLength: Int,
    val hexPreview: String? = null,
    val utf8Preview: String? = null,
    val rawAvailable: Boolean
)

@Serializable
data class DataSegmentsSummaryResult(
    val totalSegments: Int,
    val activeSegments: Int,
    val passiveSegments: Int,
    val memories: Int
)

@Serializable
data class AuditDataPayloadPolicyResult(
    val policyId: String,
    val module: String? = null,
    val policyPath: String? = null,
    val scannedSegments: Int,
    val matches: List<DataPayloadPolicyMatchResult>,
    val cleanSegments: List<Int>,
    val minimalFix: DataPayloadPolicyMinimalFixResult,
    val recommendedReport: DataPayloadPolicyRecommendedReportResult
)

@Serializable
data class DataPayloadPolicyMatchResult(
    val policyEntryId: String,
    val forbiddenString: String,
    val severity: String,
    val segmentIndex: Int,
    val mode: String,
    val memory: Int? = null,
    val offsetExpr: String? = null,
    val byteLength: Int,
    val rawBytesConfirmed: Boolean
)

@Serializable
data class DataPayloadPolicyMinimalFixResult(
    val removeOrReplaceSegments: List<Int>
)

@Serializable
data class DataPayloadPolicyRecommendedReportResult(
    @SerialName("policy_id")
    val policyId: String,
    val module: String? = null,
    @SerialName("scanned_segments")
    val scannedSegments: Int,
    val matches: List<DataPayloadPolicyReportMatchResult>,
    @SerialName("clean_segments")
    val cleanSegments: List<Int>,
    @SerialName("minimal_fix")
    val minimalFix: DataPayloadPolicyReportMinimalFixResult
)

@Serializable
data class DataPayloadPolicyReportMatchResult(
    @SerialName("policy_entry_id")
    val policyEntryId: String,
    @SerialName("forbidden_string")
    val forbiddenString: String,
    val severity: String,
    @SerialName("segment_index")
    val segmentIndex: Int,
    val mode: String,
    val memory: JsonElement,
    @SerialName("offset_expr")
    val offsetExpr: JsonElement,
    @SerialName("byte_length")
    val byteLength: Int,
    @SerialName("raw_bytes_confirmed")
    val rawBytesConfirmed: Boolean
)

@Serializable
data class DataPayloadPolicyReportMinimalFixResult(
    @SerialName("remove_or_replace_segments")
    val removeOrReplaceSegments: List<Int>
)
