package org.jetbrains.hexana.mcp.tools

val GetGlobalsForIndicesInput = indicesInput(
    "GetGlobalsForIndicesInput",
    "List of global indices (`globalidx`) to return.",
)

val GlobalResultClass = class_("GlobalResult") {
    int("globalIdx").describe("Global index in the unified global index space.")
    nullable("prefix", string()).describe("Import module prefix, when the global is imported.")
    nullable("name", string()).describe("Import or exported name, when applicable.")
    string("type").describe("Global value type (`i32`, `i64`, `f32`, `f64`, ...).")
    boolean("mutability").describe("True when the global is mutable.")
    nullable("expression", string()).describe("Initialization expression in WAT syntax.")
    string("definedIn").describe("Section the global is declared in.")
}

val ListGlobalsResultClass = class_("ListGlobalsResult") {
    list("globals", of = GlobalResultClass).describe("Globals declared or imported by the module.")
}

internal val GlobalTools: List<ToolSpec> = tools {
    tool("list_globals") {
        summary = "List imported and defined globals from a `.wasm` module."
        guidance = """
            List imported and defined globals declared by a WebAssembly (.wasm) module.
            Returns each global with its value type, mutability, and normalized initializer expression.
        """
        input = WasmInputClass
        output = ListGlobalsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readGlobals"
    }

    tool("get_globals_for_indices") {
        summary = "Return globals for the requested indices from the unified global index space."
        guidance = """
            Return only the requested globals from the unified global index space.
            Use this for targeted analysis when `list_globals` would return more data than needed.
        """
        input = GetGlobalsForIndicesInput
        output = ListGlobalsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readGlobalsByIndices"
    }
}
