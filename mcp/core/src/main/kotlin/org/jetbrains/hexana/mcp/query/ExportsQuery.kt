package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.listExports
import org.jetbrains.hexana.mcp.model.exports.ExportResult
import org.jetbrains.hexana.mcp.model.exports.ListExportsResult
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmUnsupportedExportRecordEntry

fun WasmBinaryProvider.readExports(): ListExportsResult {
    return ListExportsResult(
        exports = exports.mapNotNull { record -> record.toExportResult() }
    )
}

fun CommonByteBuffer.readExports(wasmModule: WasmModule): ListExportsResult {
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

private fun org.jetbrains.hexana.wasm.sections.WasmExportRecordEntry.toExportResult(): ExportResult? =
    when (this) {
        is WasmFunctionExportRecordEntry -> ExportResult(name = name, externIndex = index.toLong(), kind = externType)
        is WasmTableExportRecordEntry -> ExportResult(name = name, externIndex = index.toLong(), kind = externType)
        is WasmMemoryExportRecordEntry -> ExportResult(name = name, externIndex = index.toLong(), kind = externType)
        is WasmGlobalExportRecordEntry -> ExportResult(name = name, externIndex = index.toLong(), kind = externType)
        is WasmTagExportRecordEntry -> ExportResult(name = name, externIndex = index.toLong(), kind = externType)
        is WasmUnsupportedExportRecordEntry -> ExportResult(name = name, externIndex = index.toLong(), kind = externType)
    }
