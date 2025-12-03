package org.jetbrains.hexana.mcp

import com.fasterxml.jackson.annotation.JsonProperty
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule

internal fun CommonByteBuffer.listDataSections(wasmModule: WasmModule?, range: IntRange): DataEntryMetadata {
    val entries = listDataSectionInternal(wasmModule)
    val returned = entries.filter { it.index in range }

    val nextOffset = entries.firstOrNull { it.index >= range.last }?.index

    return DataEntryMetadata(range.first, returned.last().index, returned, entries.size, entries.size > returned.size, nextOffset)
}

class DataEntryMetadata(from: Int,
                        to: Int,
                        returned: List<DataEntry>,
                        @JsonProperty("total_segments") override val total: Int,
                        truncate: Boolean,
                        nextOffset: Int?
) : McpMetadata<DataEntry>(from, to, returned,
    total,
    truncate, nextOffset
)

