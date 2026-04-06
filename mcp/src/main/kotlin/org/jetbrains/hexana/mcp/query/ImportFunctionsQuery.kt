package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.importFunctions
import org.jetbrains.hexana.mcp.model.imports.ImportFunctionResult
import org.jetbrains.hexana.mcp.model.imports.ImportFunctionSignature
import org.jetbrains.hexana.mcp.model.imports.ListImportsResult
import org.jetbrains.hexana.wasm.WasmModule

fun CommonByteBuffer.readImportFunctions(wasmModule: WasmModule): ListImportsResult {
    return ListImportsResult(
        imports = importFunctions(wasmModule).map { entry ->
            ImportFunctionResult(
                funcidx = entry.funcidx,
                module = entry.module,
                name = entry.name,
                signature = entry.signature?.toImportFunctionSignature()
            )
        }
    )
}

private fun ParameterSignature.toImportFunctionSignature(): ImportFunctionSignature {
    return ImportFunctionSignature(
        params = params,
        ret = ret
    )
}
