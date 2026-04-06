package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.Local
import org.jetbrains.hexana.mcp.LocalsByFunction
import org.jetbrains.hexana.mcp.localsForFunctions
import org.jetbrains.hexana.mcp.model.functions.FunctionLocalsResult
import org.jetbrains.hexana.mcp.model.functions.GetLocalsForFunctionsResult
import org.jetbrains.hexana.mcp.model.functions.LocalDeclarationResult
import org.jetbrains.hexana.mcp.renderedType
import org.jetbrains.hexana.wasm.WasmModule

fun CommonByteBuffer.readLocalsForFunctions(
    wasmModule: WasmModule,
    indices: List<Int>
): GetLocalsForFunctionsResult {
    return GetLocalsForFunctionsResult(
        functions = localsForFunctions(indices, wasmModule).map { it.toFunctionLocalsResult() }
    )
}

private fun LocalsByFunction.toFunctionLocalsResult(): FunctionLocalsResult {
    return FunctionLocalsResult(
        funcidx = funcidx,
        locals = locals.map { it.toLocalDeclarationResult() }
    )
}

private fun Local.toLocalDeclarationResult(): LocalDeclarationResult {
    return LocalDeclarationResult(
        local = count,
        type = renderedType()
    )
}
