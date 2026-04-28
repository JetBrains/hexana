package org.jetbrains.hexana.mcp.tools

val ImportFunctionSignatureClass = class_("ImportFunctionSignature") {
    list("params", of = string()).describe("Parameter types, in declaration order.")
    list("ret", of = string()).serialName("return").describe("Return types, in declaration order.")
}

val ImportFunctionResultClass = class_("ImportFunctionResult") {
    int("funcidx").describe("Funcidx in the unified function index space.")
    string("module").describe("Imported module name.")
    string("name").describe("Imported field name.")
    nullable("signature", of = ImportFunctionSignatureClass)
        .describe("Parsed signature, when available.")
}

val ListImportsResultClass = class_("ListImportsResult") {
    list("imports", of = ImportFunctionResultClass)
        .describe("Imported functions declared by the module.")
}

internal val ImportTools: List<ToolSpec> = tools {
    tool("list_imports") {
        summary = "List imported functions declared by a `.wasm` module."
        guidance = """
            List imported functions declared by a WebAssembly (.wasm) module.
            This is the primary MCP tool for import inventories, link-mismatch analysis, and mapping direct host calls to imported functions.
            Returns only function imports from the unified function index space, including the `funcidx` values needed to map direct `call` targets back to imports.
            For direct host-call analysis, pair these `funcidx` values with `get_instructions_for_functions` to resolve `call N` back to `module.name` imports.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmOnly
        output = ListImportsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readImportFunctions"
    }
}
