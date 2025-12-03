package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule

internal fun CommonByteBuffer.listDataSections(wasmModule: WasmModule?, range: IntRange): DataEntryMetadata {
    val entries = listDataSectionInternal(wasmModule)
    val returned = entries.filter { it.index in range }

    val nextOffset = entries.firstOrNull { it.index >= range.last }?.index

    return DataEntryMetadata(range.first, returned.last().index, returned, entries.size, entries.size > returned.size, nextOffset)
}

data class DataEntryMetadata(val from: Int, val to: Int, val returned: List<DataEntry>, val totalSegments: Int, val truncate: Boolean, val nextOffset: Int?)
