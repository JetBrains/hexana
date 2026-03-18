package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.FunctionEntry
import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.ImportFunctionEntry
import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.functionsByIndices
import org.jetbrains.hexana.mcp.model.functions.FunctionImportResult
import org.jetbrains.hexana.mcp.model.functions.FunctionSignatureResult
import org.jetbrains.hexana.mcp.model.functions.FunctionSummaryResult
import org.jetbrains.hexana.mcp.model.functions.FunctionsResult
import org.jetbrains.hexana.wasm.WasmModule

internal fun CommonByteBuffer.readFunctionsByIndices(
    wasmModule: WasmModule,
    indices: List<Int>
): FunctionsResult {
    return FunctionsResult(
        functions = functionsByIndices(wasmModule, indices).map { it.toFunctionSummaryResult() }
    )
}

private fun FunctionEntry.toFunctionSummaryResult(): FunctionSummaryResult {
    return when (this) {
        is ImportFunctionEntry -> FunctionSummaryResult(
            funcidx = funcidx,
            signature = (signature as? ParameterSignature)?.toFunctionSignatureResult(),
            importInfo = FunctionImportResult(module = module, name = name),
            definedIn = "import"
        )

        is GeneralFunctionEntry -> FunctionSummaryResult(
            funcidx = funcidx,
            signature = (signature as? ParameterSignature)?.toFunctionSignatureResult(),
            importInfo = import?.let { FunctionImportResult(module = it.module, name = it.name) },
            exportedAs = exportedAs,
            definedIn = definedIn,
            codeOffset = codeOffset,
            codeSize = codeSize,
            instructionCount = instructionCount
        )
    }
}

private fun ParameterSignature.toFunctionSignatureResult(): FunctionSignatureResult {
    return FunctionSignatureResult(
        params = params,
        ret = ret
    )
}
