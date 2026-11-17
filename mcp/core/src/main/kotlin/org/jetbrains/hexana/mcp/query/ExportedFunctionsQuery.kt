package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.*
import org.jetbrains.hexana.mcp.model.exports.ExportedFunctionEntryResult
import org.jetbrains.hexana.mcp.model.exports.ExportedFunctionResult
import org.jetbrains.hexana.mcp.model.exports.ExportedFunctionSignatureResult
import org.jetbrains.hexana.mcp.model.exports.ListExportedFunctionsResult
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry

fun WasmBinaryProvider.readExportedFunctions(
    range: IntRange
): ListExportedFunctionsResult {
    val functionEntriesByIndex = listFunctionEntries().associateBy { it.funcidx }
    val entries = exports
        .filterIsInstance<WasmFunctionExportRecordEntry>()
        .mapNotNull { export ->
            functionEntriesByIndex[export.index.toInt()]
                ?.let { entry -> WasmExportFunctionRecord(export.name, entry) }
        }
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

        is GeneralFunctionEntry -> {
            val imported = import
            if (definedIn == "import" && imported != null) {
                ExportedFunctionEntryResult(
                    funcidx = funcidx,
                    module = imported.module,
                    name = imported.name,
                    signature = signature?.toExportedFunctionSignatureResult()
                )
            } else {
                ExportedFunctionEntryResult(
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
    }
}

private fun ParameterSignature.toExportedFunctionSignatureResult(): ExportedFunctionSignatureResult {
    return ExportedFunctionSignatureResult(
        params = params,
        ret = ret
    )
}
