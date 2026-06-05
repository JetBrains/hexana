package org.jetbrains.hexana.mcp.tools

private const val MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.memory"

val GetMemoryForIndicesInput = indicesInput(
    "GetMemoryForIndicesInput",
    "List of memory indices (`memidx`) to return.",
)

val MemoryLimitsResultClass = class_("MemoryLimitsResult") {
    string("addressType").describe("Address space type (`i32` or `i64`).")
    long("min").describe("Minimum number of pages.")
    nullable("max", long()).describe("Maximum number of pages, when the memory is bounded.")
    boolean("hasMax").describe("True when an explicit upper bound was declared.")
    string("range").describe("Human-readable range such as `1..ε`.")
    string("flag").describe("Raw limits flag byte as hex (`0x00`, `0x01`, etc.).")
}

val MemoryImportResultClass = class_("MemoryImportResult") {
    string("module").describe("Import module name.")
    string("name").describe("Import field name.")
}

val MemoryResultClass = class_("MemoryResult") {
    int("memoryIdx").describe("Index in the unified memory index space.")
    embed("limit", of = MemoryLimitsResultClass).describe("Page limits for the memory.")
    nullable("importInfo", of = MemoryImportResultClass).serialName("import")
        .describe("Import descriptor when the memory is imported.")
    string("definedIn").describe("Section the memory is declared in.")
}

val ListMemoryResultClass = class_("ListMemoryResult", kotlinPackage = MODEL_PACKAGE) {
    list("memories", of = MemoryResultClass).describe("Imported and defined memories.")
}

internal val MemoryTools: List<ToolSpec> = tools {
    tool("list_memory") {
        summary = "List imported and defined memories from a `.wasm` module."
        guidance = """
            List imported and defined memories declared by a WebAssembly (.wasm) module.
            Returns memory indices together with decoded WebAssembly limits.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmOnly
        output = ListMemoryResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readMemory"
    }

    tool("get_memory_for_indices") {
        summary = "Return memories for the requested indices from the unified memory index space."
        guidance = """
            Return only the requested memories from the unified memory index space.
            Useful for multi-memory or wasm64 modules when only a subset matters.
        """
        input = GetMemoryForIndicesInput
        inputPattern = ToolInputPattern.Indices
        output = ListMemoryResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readMemoryByIndices"
    }
}
