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
    return bytes.readDataSections(wasmModule, range)
}

fun CommonByteBuffer.readDataSections(
    wasmModule: WasmModule,
    range: IntRange
): ListDataSectionsResult {
    val entries = listDataSectionInternal(wasmModule)
    val returned = entries.filter { it.index in range }

    val nextOffset = entries.firstOrNull { it.index >= range.last }?.index

    return ListDataSectionsResult(
        from = range.first,
        to = returned.last().index,
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
