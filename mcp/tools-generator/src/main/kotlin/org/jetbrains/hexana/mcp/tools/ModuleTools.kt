package org.jetbrains.hexana.mcp.tools

private const val MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.summary"

val SummarizeModuleImportsClass = class_("SummarizeModuleImports") {
    int("functions").describe("Number of imported functions.")
    int("memories").describe("Number of imported memories.")
    int("tables").describe("Number of imported tables.")
    int("globals").describe("Number of imported globals.")
}

val SummarizeModuleResultClass = class_("SummarizeModuleResult", kotlinPackage = MODEL_PACKAGE) {
    int("functions").describe("Total function count (imports + defined).")
    int("exports").describe("Number of exports declared by the module.")
    int("memories").describe("Number of memories declared by the module.")
    int("globals").describe("Number of globals declared by the module.")
    int("tables").describe("Number of tables declared by the module.")
    int("types").describe("Number of type entries declared by the module.")
    embed("imports", of = SummarizeModuleImportsClass).describe("Import breakdown by kind.")
    int("moduleSize").describe("Size of the module binary in bytes.")
}

internal val ModuleTools: List<ToolSpec> = tools {
    tool("summarize_module") {
        summary = "Summarize a WebAssembly module — counts of imports, exports, memories, globals, functions, types."
        guidance = """
            Summarize a WebAssembly (.wasm) module with high-level counts for its major sections and declarations.
            Includes the nested import breakdown and total module size, making it a low-cost overview before calling heavier tools.
            For Wasm analysis tasks, this is usually the best first MCP call before choosing more specific import, export, or instruction tools.
            After this overview, prefer `list_imports` for import/link analysis, `list_exported_functions` for export analysis, and `get_instructions_for_functions` for body analysis.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmOnly
        output = SummarizeModuleResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readModuleSummary"
    }
}
