package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.DataEntry
import org.jetbrains.hexana.mcp.listDataSectionInternal
import org.jetbrains.hexana.mcp.model.data.DataEntryResult
import org.jetbrains.hexana.mcp.model.data.ListDataSectionsResult
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.WasmModule

fun WasmBinaryProvider.readDataSections(
    range: IntRange
): ListDataSectionsResult {
    val wasmModule = requireNotNull(wasmModule)
    val entries = bytes.listDataSectionInternal(wasmModule)
    val from = range.first
    val to = minOf(range.last, entries.size - 1)
    val returned = if (entries.isEmpty() || from > to) {
        emptyList()
    } else {
        entries.filter { it.index in from..to }
    }
    val nextOffset = if (to == entries.size - 1) null else to + 1

    return ListDataSectionsResult(
        from = from,
        to = to,
        returned = returned.map { it.toDataEntryResult() },
        totalSegments = entries.size,
        truncate = entries.size > returned.size,
        nextOffset = nextOffset
    )
}

private fun DataEntry.toDataEntryResult(): DataEntryResult {
    return DataEntryResult(
        kind = kind,
        index = index,
        offset = offset,
        data = data,
        memoryIndex = memoryIndex?.toLong(),
        expression = expression
    )
}
