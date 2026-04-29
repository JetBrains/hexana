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
    val to = range.last
    val lastAvailableIndex = entries.size - 1
    val lastReturnedIndex = minOf(to, lastAvailableIndex)
    val returned = if (entries.isEmpty() || from > lastReturnedIndex) {
        emptyList()
    } else {
        entries.filter { it.index in from..lastReturnedIndex }
    }
    val nextOffset = if (to < lastAvailableIndex) to + 1 else null

    return ListDataSectionsResult(
        from = from,
        to = to,
        returned = returned.map { it.toDataEntryResult() },
        totalSegments = entries.size,
        truncate = returned.isNotEmpty() && to > lastAvailableIndex,
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
