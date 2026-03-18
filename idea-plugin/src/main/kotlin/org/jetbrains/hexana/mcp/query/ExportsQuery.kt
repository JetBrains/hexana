package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.listExports
import org.jetbrains.hexana.mcp.model.exports.ExportResult
import org.jetbrains.hexana.mcp.model.exports.ListExportsResult
import org.jetbrains.hexana.wasm.WasmModule

internal fun CommonByteBuffer.readExports(wasmModule: WasmModule): ListExportsResult {
    return ListExportsResult(
        exports = listExports(wasmModule).map { record ->
            ExportResult(
                name = record.name,
                externIndex = record.externIndex,
                kind = record.kind
            )
        }
    )
}
