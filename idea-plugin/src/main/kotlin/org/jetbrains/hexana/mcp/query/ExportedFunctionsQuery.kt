package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.FunctionEntry
import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.ImportFunctionEntry
import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.WasmExportFunctionRecord
import org.jetbrains.hexana.mcp.listExportedInternal
import org.jetbrains.hexana.mcp.model.exports.ExportedFunctionEntryResult
import org.jetbrains.hexana.mcp.model.exports.ExportedFunctionResult
import org.jetbrains.hexana.mcp.model.exports.ExportedFunctionSignatureResult
import org.jetbrains.hexana.mcp.model.exports.ListExportedFunctionsResult
import org.jetbrains.hexana.wasm.WasmModule

internal fun CommonByteBuffer.readExportedFunctions(
    wasmModule: WasmModule,
    range: IntRange
): ListExportedFunctionsResult {
    val entries = listExportedInternal(wasmModule)
    val from = range.first
    val to = minOf(range.last, entries.size - 1)
    val returned = if (entries.isEmpty() || from > to) {
        emptyList()
    } else {
        entries.subList(from, to + 1).map { it.toExportedFunctionResult() }
    }
    val nextOffset = if (to == entries.size - 1) null else to + 1

    return ListExportedFunctionsResult(
        from = from,
        to = to,
        returned = returned,
        total = entries.size,
        truncate = returned.size < entries.size,
        nextOffset = nextOffset
    )
}

private fun WasmExportFunctionRecord.toExportedFunctionResult(): ExportedFunctionResult {
    return ExportedFunctionResult(
        name = name,
        entry = entry.toExportedFunctionEntryResult()
    )
}

private fun FunctionEntry.toExportedFunctionEntryResult(): ExportedFunctionEntryResult {
    return when (this) {
        is ImportFunctionEntry -> ExportedFunctionEntryResult(
            funcidx = funcidx,
            module = module,
            name = name,
            signature = signature?.toExportedFunctionSignatureResult()
        )

        is GeneralFunctionEntry -> ExportedFunctionEntryResult(
            funcidx = funcidx,
            signature = signature?.toExportedFunctionSignatureResult(),
            exportedAs = exportedAs,
            definedIn = definedIn,
            codeOffset = codeOffset,
            codeSize = codeSize,
            instructionCount = instructionCount
        )
    }
}

private fun ParameterSignature.toExportedFunctionSignatureResult(): ExportedFunctionSignatureResult {
    return ExportedFunctionSignatureResult(
        params = params,
        ret = ret
    )
}
