package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.CodesByFunction
import org.jetbrains.hexana.mcp.model.functions.FunctionInstructionsResult
import org.jetbrains.hexana.mcp.model.functions.GetInstructionsForFunctionsResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

fun WasmBinaryProvider.readInstructionsForFunctions(
    indices: List<Int>
): GetInstructionsForFunctionsResult {
    return GetInstructionsForFunctionsResult(
        functions = readFunctionInstructions(indices).map { it.toFunctionInstructionsResult() }
    )
}

private fun CodesByFunction.toFunctionInstructionsResult(): FunctionInstructionsResult {
    return FunctionInstructionsResult(
        funcidx = funcidx,
        instructions = instructions
    )
}
