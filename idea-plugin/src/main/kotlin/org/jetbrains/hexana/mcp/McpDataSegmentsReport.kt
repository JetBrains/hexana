package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule

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
internal class McpDataSegmentsReport(
    val memory: List<McpMemoryReport>,
    val globals: List<McpGlobalReport>,
    val segments: List<McpDataSegment>,
    val linkingMetadata: McpLinkingMetadata?,
    val exportedSymbols: List<String>,
    val summary: McpSummary
)

//TODO:
internal class McpLinkingMetadata

internal class McpMemoryReport(val memidx: Int, val limits: Limits<*>, val definedIn: String)
internal class McpGlobalReport(
    val globalidx: Int,
    val module: String?,
    val name: String?,
    val type: String,
    val mutable: Boolean,
    val initExpr: String?,
    val definedIn: String,
    val exportedAs: List<String>?
)

class McpDataSegment(
    val dataidx: Int,
    val mode: String,
    val memory: Int?,
    val offsetExpr: String?,
    val byteLength: Int,
    val hexPreview: String?,
    val utf8Preview: String?,
    val rawAvailable: Boolean
)

fun DataEntry.toMcpDataSegment(showPreview: Boolean): McpDataSegment {
    val preview = data.take(32).toByteArray()
    return McpDataSegment(
        this.index,
        if(kind == 1) "passive" else "active",
        memoryIndex?.toInt(),
        expression,
        data.size,
        preview.joinToString(separator = " ") { it.toHexString() }.takeIf { showPreview },
        try {
             String(preview, Charsets.UTF_8).takeIf { showPreview }
        } catch (e: Exception) {
            null
        },
        preview.isNotEmpty()
    )
}

internal fun GlobalRecord.toMcpGlobalReport(exports: List<ExportRecord>): McpGlobalReport {
    val exportedGlobals = exports.filter { it.kind == 0x3.toByte() }
    return McpGlobalReport(
        globalIdx,
        prefix,
        name,
        type,
        mutability,
        expression,
        definedIn,
        exportedGlobals.filter { it.externIndex.toInt() == globalIdx }.map { it.name },
    )
}


internal class McpSummary(val totalSegments: Int, val activeSegments: Int, val passiveSegments: Int, val memories: Int)

internal fun CommonByteBuffer.listDataSegments(
    segmentRange: IntRange,
    wasmModule: WasmModule?,
    includeExports: Boolean,
    includeGlobals: Boolean,
    minByteLength: Int?
): McpDataSegmentsReport {
    val start = segmentRange.first
    val endExclusive = if (segmentRange.isEmpty()) start else segmentRange.last + 1
    return listDataSegments(start, endExclusive, wasmModule, includeExports, includeGlobals, minByteLength)
}

internal fun CommonByteBuffer.listDataSegments(
    segmentStartInclusive: Int,
    segmentEndExclusive: Int,
    wasmModule: WasmModule?,
    includeExports: Boolean,
    includeGlobals: Boolean,
    minByteLength: Int?
): McpDataSegmentsReport {
    wasmModule ?: return McpDataSegmentsReport(
        emptyList(), emptyList(),
        segments = emptyList(),
        linkingMetadata = null,
        exportedSymbols = emptyList(),
        summary = McpSummary(0, 0, 0, 0)
    )

    val memory = listMemory(wasmModule)
    val mcpMempory = memory.map {
            record -> McpMemoryReport(record.memoryIdx, record.limit, record.definedIn)
    }
    val exports = if (includeExports) {
        listExports(wasmModule)
    } else {
        emptyList()
    }
    val listDataSections = listDataSectionInternal(wasmModule)
    val dataSegments0 = listDataSections.map { it.toMcpDataSegment(true) }

    val start = segmentStartInclusive.coerceAtLeast(0)
    val endExclusive = segmentEndExclusive.coerceAtLeast(start).coerceAtMost(dataSegments0.size)
    val dataSegments1 = if (start >= endExclusive) {
        emptyList()
    } else {
        dataSegments0.subList(start, endExclusive)
    }

    val dataSegments = dataSegments1.filter { it.byteLength >= (minByteLength ?: 0) }

    val globals = if (includeGlobals) {
        listGlobals(wasmModule).map { it.toMcpGlobalReport(exports) }
    } else {
        emptyList()
    }
    val summary = McpSummary(dataSegments0.size, dataSegments0.count { it.mode == "active" }, dataSegments0.count { it.mode == "passive" }, memory.size)
    return McpDataSegmentsReport(mcpMempory, globals, dataSegments, null, exports.map { it.name }, summary)
}
