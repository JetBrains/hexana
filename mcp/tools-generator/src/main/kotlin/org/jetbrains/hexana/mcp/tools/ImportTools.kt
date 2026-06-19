package org.jetbrains.hexana.mcp.tools

private const val MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.imports"

val ImportFunctionSignatureClass = class_("ImportFunctionSignature") {
    list("params", of = string()).describe("Parameter types, in declaration order.")
    list("ret", of = string()).serialName("return").describe("Return types, in declaration order.")
}

val ImportFunctionResultClass = class_("ImportFunctionResult") {
    int("funcidx").describe("Funcidx in the unified function index space.")
    nullable("typeidx", of = int()).describe("Function type index referenced by this import, when resolved.")
    string("locationKind").describe("Stable location kind. Function imports use `import`.")
    string("module").describe("Imported module name.")
    string("name").describe("Imported field name.")
    string("locationDisplay").describe("Stable human-readable import location, for example `import host.bind_external`.")
    nullable("signature", of = ImportFunctionSignatureClass)
        .describe("Parsed signature, when available.")
}

val ListImportsResultClass = class_("ListImportsResult", kotlinPackage = MODEL_PACKAGE) {
    list("imports", of = ImportFunctionResultClass)
        .describe("Imported functions declared by the module.")
}

internal val ImportTools: List<ToolSpec> = tools {
    tool("list_imports") {
        summary = "List imported functions declared by a `.wasm` module."
        guidance = """
            List imported functions declared by a WebAssembly (.wasm) module.
            This is the primary MCP tool for import inventories, link-mismatch analysis, and mapping direct host calls to imported functions.
            For WebAssembly component compatibility drift, provider selection, candidate component scans, dependency lock repair, or component run/link fixes, use `check_component_compatibility` instead.
            Do not use this low-level function-import inventory as the main workflow for selecting component dependency files.
            Returns only function imports from the unified function index space, including the `funcidx` values needed to map direct `call` targets back to imports.
            Each import includes structured location fields plus `locationDisplay`, a stable string suitable for report fields such as `where`.
            For direct host-call analysis, pair these `funcidx` values with `get_instructions_for_functions` to resolve `call N` back to `module.name` imports.
            For entrypoint host-call policy repair or audit JSON, use `get_entrypoint_policy` instead; it returns the complete copy-ready policy/report payload directly.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmOnly
        output = ListImportsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readImportFunctions"
    }
}
