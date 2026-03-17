package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule

internal fun CommonByteBuffer.listExportedFunctions(wasmModule: WasmModule?, range: IntRange): ExportedFunctionMetadata {
    wasmModule ?: return ExportedFunctionMetadata(0, 0, emptyList(), 0, false, null)
    val entries = listExportedInternal(wasmModule)
    val from = range.first
    val to = minOf(range.last, entries.size - 1)
    val returned = entries.slice(from.. to)

    val nextOffset = if (to == entries.size - 1) null else to + 1
    return ExportedFunctionMetadata(from, to, returned, entries.size, returned.size < entries.size, nextOffset)
}

class ExportedFunctionMetadata(from: Int, to: Int, returned: List<WasmExportFunctionRecord>, total: Int,
                               truncate: Boolean, nextOffset: Int?
) : McpMetadata<WasmExportFunctionRecord>(from,
    to,
    returned,
    total,
    truncate,
    nextOffset
)




