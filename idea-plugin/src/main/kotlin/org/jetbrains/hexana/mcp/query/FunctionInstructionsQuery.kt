package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.CodesByFunction
import org.jetbrains.hexana.mcp.codeForFunctions
import org.jetbrains.hexana.mcp.model.functions.FunctionInstructionsResult
import org.jetbrains.hexana.mcp.model.functions.GetInstructionsForFunctionsResult
import org.jetbrains.hexana.wasm.WasmModule

internal fun CommonByteBuffer.readInstructionsForFunctions(
    wasmModule: WasmModule,
    indices: List<Int>
): GetInstructionsForFunctionsResult {
    return GetInstructionsForFunctionsResult(
        functions = codeForFunctions(indices, wasmModule).map { it.toFunctionInstructionsResult() }
    )
}

private fun CodesByFunction.toFunctionInstructionsResult(): FunctionInstructionsResult {
    return FunctionInstructionsResult(
        funcidx = funcidx,
        instructions = instructions
    )
}
