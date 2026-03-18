package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.GlobalRecord
import org.jetbrains.hexana.mcp.listGlobals
import org.jetbrains.hexana.mcp.model.globals.GlobalResult
import org.jetbrains.hexana.mcp.model.globals.ListGlobalsResult
import org.jetbrains.hexana.wasm.WasmModule

internal fun CommonByteBuffer.readGlobals(wasmModule: WasmModule): ListGlobalsResult {
    return ListGlobalsResult(
        globals = listGlobals(wasmModule).map { it.toGlobalResult() }
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
