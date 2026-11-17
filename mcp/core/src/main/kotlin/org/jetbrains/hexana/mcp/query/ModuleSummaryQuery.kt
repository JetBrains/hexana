package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleImports
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.mcp.types
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmGlobalType
import org.jetbrains.hexana.wasm.sections.WasmMemType
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmTableExternalType

fun WasmBinaryProvider.readModuleSummary(): SummarizeModuleResult {
    val wasmModule = wasmModule ?: error("WasmBinaryProvider without parsed wasmModule should not be summarized")

    return buildSummary(
        functions = functions.size,
        exports = exports.size,
        memories = wasmModule.memorySection?.let { with(it) { bytes.scan().size } } ?: 0,
        globals = imports.count { it.type is WasmGlobalType } + (wasmModule.globalSection?.let { with(it) { bytes.scan().size } } ?: 0),
        tables = wasmModule.tableSection?.let { with(it) { bytes.scanTable().size } } ?: 0,
        types = bytes.types(wasmModule).size,
        importedFunctions = importFuncCount,
        importedMemories = imports.count { it.type is WasmMemType },
        importedTables = imports.count { it.type is WasmTableExternalType },
        importedGlobals = imports.count { it.type is WasmGlobalType },
        moduleSize = bytes.position() + bytes.remaining()
    )
}

private fun buildSummary(
    functions: Int,
    exports: Int,
    memories: Int,
    globals: Int,
    tables: Int,
    types: Int,
    importedFunctions: Int,
    importedMemories: Int,
    importedTables: Int,
    importedGlobals: Int,
    moduleSize: Int
): SummarizeModuleResult {
    return SummarizeModuleResult(
        functions = functions,
        exports = exports,
        memories = memories,
        globals = globals,
        tables = tables,
        types = types,
        imports = SummarizeModuleImports(
            functions = importedFunctions,
            memories = importedMemories,
            tables = importedTables,
            globals = importedGlobals
        ),
        moduleSize = moduleSize
    )
}
