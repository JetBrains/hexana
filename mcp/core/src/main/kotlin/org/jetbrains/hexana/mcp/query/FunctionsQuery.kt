package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.*
import org.jetbrains.hexana.mcp.model.functions.FunctionImportResult
import org.jetbrains.hexana.mcp.model.functions.FunctionSignatureResult
import org.jetbrains.hexana.mcp.model.functions.FunctionSummaryResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.wasm.WasmModule

fun CommonByteBuffer.readFunctions(
    wasmModule: WasmModule,
    offset: Int,
    limit: Int
): FunctionsResult {
    return FunctionsResult(
        functions = listFunctions(wasmModule, offset, limit).map { it.toFunctionSummaryResult() }
    )
}

fun CommonByteBuffer.readFunctionsByIndices(
    wasmModule: WasmModule,
    indices: List<Int>
): FunctionsResult {
    return FunctionsResult(
        functions = functionsByIndices(wasmModule, indices).map { it.toFunctionSummaryResult() }
    )
}

private fun GeneralFunctionEntry.toFunctionSummaryResult(): FunctionSummaryResult {
    return FunctionSummaryResult(
        funcidx = funcidx,
        signature = signature?.toFunctionSignatureResult(),
        importInfo = import?.let { FunctionImportResult(module = it.module, name = it.name) },
        exportedAs = exportedAs,
        definedIn = definedIn,
        codeOffset = codeOffset,
        codeSize = codeSize,
        instructionCount = instructionCount
    )
}

private fun FunctionEntry.toFunctionSummaryResult(): FunctionSummaryResult {
    return when (this) {
        is ImportFunctionEntry -> FunctionSummaryResult(
            funcidx = funcidx,
            signature = signature?.toFunctionSignatureResult(),
            importInfo = FunctionImportResult(module = module, name = name),
            definedIn = "import"
        )

        is GeneralFunctionEntry -> toFunctionSummaryResult()
    }
}

private fun ParameterSignature.toFunctionSignatureResult(): FunctionSignatureResult {
    return FunctionSignatureResult(
        params = params,
        ret = ret
    )
}
