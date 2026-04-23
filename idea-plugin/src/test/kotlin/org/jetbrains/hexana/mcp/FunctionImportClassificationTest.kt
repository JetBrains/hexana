package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.mcp.query.readModuleSummary
import org.jetbrains.hexana.providers.WasmBinaryProvider

class FunctionImportClassificationTest : WasmTest() {
    fun testImportFunctionsIgnoresNonFunctionImports() = doTest("functionIndexSpace") { wasmModule ->
        val importedFunctions = importFunctions(wasmModule)
        assertEquals(1, importedFunctions.size)
        assertEquals("env", importedFunctions.single().module)
        assertEquals("callback", importedFunctions.single().name)

        val functions = listFunctionsInternal(wasmModule)
        assertEquals(2, functions.size)
        assertEquals(1, functions.count { it.definedIn == "import" })
        assertEquals(1, functions.count { it.definedIn == "function_section" })
        assertEquals(0, functions.first { it.definedIn == "import" }.funcidx)
        assertEquals(1, functions.first { it.definedIn == "function_section" }.funcidx)

        val summary = WasmBinaryProvider(position(0)).readModuleSummary()
        assertEquals(1, summary.imports.functions)
        assertEquals(0, summary.memories)
        assertEquals(1, summary.globals)
        assertEquals(1, summary.functions)
        val expectedTypes = with(requireNotNull(wasmModule).typesSection) {
            this ?: return@with 0
            parseTypes().size
        }
        assertEquals(expectedTypes, summary.types)
    }
}
