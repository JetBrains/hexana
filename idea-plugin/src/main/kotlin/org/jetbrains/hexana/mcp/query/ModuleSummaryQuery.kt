package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.listExports
import org.jetbrains.hexana.mcp.listFunctionsInternal
import org.jetbrains.hexana.mcp.listGlobals
import org.jetbrains.hexana.mcp.listMemory
import org.jetbrains.hexana.mcp.listTypes
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleImports
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.sections.WasmTableExternalType

internal fun CommonByteBuffer.readModuleSummary(wasmModule: WasmModule): SummarizeModuleResult {
    val functions = listFunctionsInternal(wasmModule)
    val importedFunctions = functions.count { it.definedIn == "import" }
    val definedFunctions = functions.count { it.definedIn == "function_section" }

    val memories = listMemory(wasmModule)
    val importedMemories = memories.count { it.definedIn == "import" }
    val definedMemories = memories.count { it.definedIn == "memory_section" }

    val globals = listGlobals(wasmModule)
    val importedGlobals = globals.count { it.definedIn == "import" }
    val definedGlobals = globals.count { it.definedIn == "global_section" }

    val types = listTypes(wasmModule).size

    val importedTables = with(wasmModule.importSection) {
        this ?: return@with emptyList()
        getImportRecords()
    }.count { it.type is WasmTableExternalType }

    val tables = with(wasmModule.tableSection) {
        this ?: return@with emptyList()
        scanTable()
    }.size

    val exports = listExports(wasmModule)
    return SummarizeModuleResult(
        functions = definedFunctions,
        exports = exports.size,
        memories = definedMemories,
        globals = importedGlobals + definedGlobals,
        tables = tables,
        types = types,
        imports = SummarizeModuleImports(
            functions = importedFunctions,
            memories = importedMemories,
            tables = importedTables,
            globals = importedGlobals
        ),
        moduleSize = position() + remaining()
    )
}
