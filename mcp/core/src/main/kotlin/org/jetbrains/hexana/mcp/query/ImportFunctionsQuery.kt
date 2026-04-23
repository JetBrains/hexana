package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.importFunctions
import org.jetbrains.hexana.mcp.model.imports.ImportFunctionResult
import org.jetbrains.hexana.mcp.model.imports.ImportFunctionSignature
import org.jetbrains.hexana.mcp.model.imports.ListImportsResult
import org.jetbrains.hexana.mcp.toSignature
import org.jetbrains.hexana.mcp.types
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

fun WasmBinaryProvider.readImportFunctions(): ListImportsResult {
    val wasmModule = wasmModule ?: return ListImportsResult(emptyList())
    val types = bytes.types(wasmModule)
    val functionImports = imports.filter { it.type is WasmTypeIndexExternalType }

    return ListImportsResult(
        imports = functionImports.mapIndexed { index, record ->
            val functionType = record.type as WasmTypeIndexExternalType
            ImportFunctionResult(
                funcidx = index,
                module = record.prefix,
                name = record.name,
                signature = functionType.toSignature(types)?.toImportFunctionSignature()
            )
        }
    )
}

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
