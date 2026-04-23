package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.GlobalRecord
import org.jetbrains.hexana.mcp.listGlobals
import org.jetbrains.hexana.mcp.listGlobalsByIndices
import org.jetbrains.hexana.mcp.model.globals.GlobalResult
import org.jetbrains.hexana.mcp.model.globals.ListGlobalsResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

fun WasmBinaryProvider.readGlobals(): ListGlobalsResult {
    val wasmModule = requireNotNull(wasmModule)
    return ListGlobalsResult(
        globals = bytes.listGlobals(wasmModule).map { it.toGlobalResult() }
    )
}

fun WasmBinaryProvider.readGlobalsByIndices(
    indices: List<Int>
): ListGlobalsResult {
    val wasmModule = requireNotNull(wasmModule)
    return ListGlobalsResult(
        globals = bytes.listGlobalsByIndices(wasmModule, indices).map { it.toGlobalResult() }
    )
}

private fun GlobalRecord.toGlobalResult(): GlobalResult {
    return GlobalResult(
        globalIdx = globalIdx,
        prefix = prefix,
        name = name,
        type = type,
        mutability = mutability,
        expression = expression,
        definedIn = definedIn
    )
}
