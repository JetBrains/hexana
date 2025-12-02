package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.WasmTableExternalType

fun CommonByteBuffer.moduleSummary(wasmModule: WasmModule?): ModuleSummary? {
    wasmModule ?: return null
    val functions = listFunctionsInternal(wasmModule)
    val importedFunctions = functions.filter { it.definedIn == "import" }.size
    val definedFunctions = functions.filter { it.definedIn == "function_section" }.size

    val memories = listMemory(wasmModule)
    val importedMemories = memories.filter { it.definedIn == "import" }.size
    val definedMemories = memories.filter { it.definedIn == "memory_section" }.size

    val globals = listGlobals(wasmModule)
    val importedGlobals = globals.filter { it.definedIn == "import" }.size
    val definedGlobals = globals.filter { it.definedIn == "global_section" }.size

    val types = listTypes(wasmModule).size

    val importedTables = with(wasmModule!!.importSection) {
        this ?: return@with emptyList()
        getImportRecords()
    }.filter { it.type is WasmTableExternalType }.size

    val tables = with(wasmModule.tableSection) {
        this ?: return@with emptyList()
        scanTable(wasmModule)
    }.size

    val exports = listExports(wasmModule)
    return ModuleSummary(
        definedFunctions,
        exports.size,
        definedMemories,
        importedGlobals + definedGlobals,
        tables,
        types,
        ImportSummary(importedFunctions, importedMemories, importedTables, importedGlobals),
        position() + remaining()
    )
}

data class ModuleSummary(
    val functions: Int,
    val exports: Int,
    val memories: Int,
    val globals: Int,
    val tables: Int,
    val types: Int,
    val imports: ImportSummary,
    val moduleSize: Int
)

data class ImportSummary(val functions: Int, val memories: Int, val tables: Int, val globals: Int)