package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.*
import org.jetbrains.hexana.mcp.model.functions.FunctionImportResult
import org.jetbrains.hexana.mcp.model.functions.FunctionSignatureResult
import org.jetbrains.hexana.mcp.model.functions.FunctionSummaryResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

fun WasmBinaryProvider.readFunctions(
    offset: Int,
    limit: Int
): FunctionsResult {
    return FunctionsResult(
        functions = listFunctionEntries()
            .drop(offset)
            .take(limit)
            .map { it.toFunctionSummaryResult() }
    )
}

fun WasmBinaryProvider.readFunctionsByIndices(
    indices: List<Int>
): FunctionsResult {
    return FunctionsResult(
        functions = listFunctionEntries()
            .filter { it.funcidx in indices }
            .map { it.toFunctionSummaryResult() }
    )
}

private fun GeneralFunctionEntry.toFunctionSummaryResult(): FunctionSummaryResult {
    val importLocation = import
    return FunctionSummaryResult(
        funcidx = funcidx,
        typeidx = typeidx,
        signature = signature?.toFunctionSignatureResult(),
        importInfo = importLocation?.let { FunctionImportResult(module = it.module, name = it.name) },
        exportedAs = exportedAs,
        definedIn = definedIn,
        locationKind = importLocation?.let { "import" },
        module = importLocation?.module,
        name = importLocation?.name,
        locationDisplay = importLocation?.locationDisplay(),
        codeOffset = codeOffset,
        codeSize = codeSize,
        instructionCount = instructionCount
    )
}

private fun FunctionEntry.toFunctionSummaryResult(): FunctionSummaryResult {
    return when (this) {
        is ImportFunctionEntry -> FunctionSummaryResult(
            funcidx = funcidx,
            typeidx = typeidx,
            signature = signature?.toFunctionSignatureResult(),
            importInfo = FunctionImportResult(module = module, name = name),
            definedIn = "import",
            locationKind = "import",
            module = module,
            name = name,
            locationDisplay = Import(module, name).locationDisplay()
        )

        is GeneralFunctionEntry -> toFunctionSummaryResult()
    }
}

private fun ParameterSignature.toFunctionSignatureResult(): FunctionSignatureResult {
    return FunctionSignatureResult(
        params = params,
        results = ret
    )
}

internal fun WasmBinaryProvider.listFunctionEntries(): List<GeneralFunctionEntry> {
    val wasmModule = wasmModule ?: return emptyList()
    val types = bytes.types(wasmModule)
    val exportedFunctionsByIndex = exports
        .filterIsInstance<WasmFunctionExportRecordEntry>()
        .groupBy(
            keySelector = { it.index.toInt() },
            valueTransform = { it.name }
        )

    val importEntries = imports
        .filter { it.type is WasmTypeIndexExternalType }
        .mapIndexed { index, record ->
            val signature = (record.type as WasmTypeIndexExternalType).toSignature(types)
            GeneralFunctionEntry(
                funcidx = index,
                typeidx = (record.type as WasmTypeIndexExternalType).index.toInt(),
                signature = signature,
                import = Import(module = record.prefix, name = record.name),
                exportedAs = exportedFunctionsByIndex[index].orEmpty(),
                definedIn = "import",
                codeOffset = null,
                codeSize = null,
                instructionCount = null
            )
        }

    val functionTypeIndices = with(wasmModule.functionSection) {
        this ?: return@with emptyList()
        bytes.parseFunctions()
    }
    val declaredCodeRecords = codeRecords
    val importBase = importEntries.size
    val functions = functionTypeIndices.mapIndexedNotNull { index, typeIndex ->
        resolveFunctionTypeRecord(types, typeIndex)?.toSignature()?.let { signature ->
            val funcidx = importBase + index
            val codeRecord = declaredCodeRecords.getOrNull(index)
            GeneralFunctionEntry(
                funcidx = funcidx,
                typeidx = typeIndex,
                signature = signature,
                import = null,
                exportedAs = exportedFunctionsByIndex[funcidx].orEmpty(),
                definedIn = "function_section",
                codeOffset = codeRecord?.offset?.toLong(),
                codeSize = codeRecord?.size?.toLong(),
                instructionCount = codeRecord?.opCodes?.size?.toLong()
            )
        }
    }

    return importEntries + functions
}

internal fun WasmBinaryProvider.readFunctionInstructions(indices: List<Int>): List<CodesByFunction> {
    val importBase = importFuncCount
    return indices.distinct().mapNotNull { funcidx ->
        when {
            funcidx < importBase -> CodesByFunction(funcidx, emptyList())
            else -> getCodeRecordByIndex(funcidx - importBase)?.let { codeRecord ->
                CodesByFunction(funcidx, codeRecord.opCodes.map { it.watString().toString() })
            }
        }
    }
}

internal fun WasmBinaryProvider.readFunctionLocals(indices: List<Int>): List<LocalsByFunction> {
    val importBase = importFuncCount
    return indices.distinct().mapNotNull { funcidx ->
        when {
            funcidx < importBase -> LocalsByFunction(funcidx, emptyList())
            else -> getCodeRecordByIndex(funcidx - importBase)?.let { codeRecord ->
                LocalsByFunction(funcidx, codeRecord.locals.serializeLocals())
            }
        }
    }
}
