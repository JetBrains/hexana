package org.jetbrains.hexana.mcp.tools

private const val MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.exports"

val ListExportedFunctionsInput = WasmInputClass.with("ListExportedFunctionsInput") {
    embed("entriesRange", of = ClosedIntRange)
        .describe("Closed zero-based export slice. Pass `entriesRange = { from, to }`; both bounds are inclusive.")
}

val SearchExportsInput = WasmInputClass.with("SearchExportsInput") {
    list("query", of = string())
        .describe("Case-insensitive export-name substrings to search for.")
        .maxItems(20)
    int("limit", default = 50)
        .describe("Maximum number of matching exports to return.")
        .minimum(1)
        .maximum(200)
}

val ExportResultClass = class_("ExportResult") {
    string("name").describe("Export field name.")
    long("externIndex").describe("Index into the exported address space (funcidx, tableidx, memidx, or globalidx).")
    byte("kind").describe("WebAssembly export kind byte (0=func, 1=table, 2=memory, 3=global).")
}

val ListExportsResultClass = class_("ListExportsResult", kotlinPackage = MODEL_PACKAGE) {
    list("exports", of = ExportResultClass)
        .describe("Exports declared by the module.")
}

val SearchExportFunctionSignatureResultClass = class_("SearchExportFunctionSignatureResult") {
    list("params", of = string()).describe("Parameter types.")
    list("results", of = string()).describe("Result types.")
}

val SearchExportResultClass = class_("SearchExportResult") {
    string("name").describe("Export name.")
    string("kind").describe("Export kind: `func`, `table`, `memory`, `global`, `tag`, or `unknown`.")
    int("externIndex").describe("Index into the exported address space.")
    nullable("funcidx", int()).describe("Function index for function exports.")
    nullable("tableidx", int()).describe("Table index for table exports.")
    nullable("memidx", int()).describe("Memory index for memory exports.")
    nullable("globalidx", int()).describe("Global index for global exports.")
    nullable("tagidx", int()).describe("Tag index for tag exports.")
    nullable("signature", of = SearchExportFunctionSignatureResultClass)
        .describe("Resolved function signature for function exports.")
    nullable("limits", of = MemoryLimitsResultClass).describe("Resolved page limits for memory exports.")
    string("repr").describe("Compact WAT export representation.")
    double("score").describe("Name-match score between 0.0 and 1.0.")
}

val SearchExportsResultClass = class_("SearchExportsResult", kotlinPackage = MODEL_PACKAGE) {
    list("results", of = SearchExportResultClass)
        .describe("Matching export entries.")
}

val ExportedFunctionSignatureResultClass = class_("ExportedFunctionSignatureResult") {
    list("params", of = string()).describe("Parameter types.")
    list("ret", of = string()).serialName("return").describe("Return types.")
}

val ExportedFunctionEntryResultClass = class_("ExportedFunctionEntryResult") {
    int("funcidx").describe("Funcidx in the unified function index space.")
    nullable("module", string()).describe("Import module name, when the export resolves to an imported function.")
    nullable("name", string()).describe("Import field name, when applicable.")
    nullable("signature", of = ExportedFunctionSignatureResultClass).describe("Parsed signature when available.")
    list("exportedAs", of = string()).describe("All export names pointing at this function.")
    nullable("definedIn", string()).describe("Which section the function body lives in.")
    nullable("codeOffset", long()).describe("Byte offset of the function body in the module.")
    nullable("codeSize", long()).describe("Size of the function body in bytes.")
    nullable("instructionCount", long()).describe("Number of decoded instructions in the body.")
}

val ExportedFunctionResultClass = class_("ExportedFunctionResult") {
    string("name").describe("Export name.")
    embed("entry", of = ExportedFunctionEntryResultClass).describe("Resolved function entry for the export.")
}

val ListExportedFunctionsResultClass = class_("ListExportedFunctionsResult", kotlinPackage = MODEL_PACKAGE) {
    int("from").describe("Inclusive lower bound that was requested.")
    int("to").describe("Inclusive upper bound that was requested.")
    list("returned", of = ExportedFunctionResultClass).describe("Entries inside the requested range.")
    int("total").describe("Total number of exported functions in the module.")
    boolean("truncate").describe("True when the range was clipped to the module's export count.")
    nullable("nextOffset", int()).describe("Next offset to continue pagination; null when the range was exhausted.")
}

internal val ExportTools: List<ToolSpec> = tools {
    tool("list_exports") {
        summary = "List exports declared by a `.wasm` module."
        guidance = """
            Includes exported functions, tables, memories, and globals; `kind` uses the standard tags 0=function, 1=table, 2=memory, 3=global.
            This is the light name-and-kind view of exports.
            Use this when you only need exported names, kinds, or raw external indices.
            For WebAssembly component compatibility drift, provider selection, candidate component scans, dependency lock repair, or component run/link fixes, use `check_component_compatibility` instead.
            Do not use this low-level export inventory as the main workflow for selecting component dependency files.
            If you need function signatures or origins, skip directly to `list_exported_functions`.
            For baseline-plus-policy global-state audits or release reports, use `audit_global_state_policy`; this low-level view does not identify new public globals, allowed baseline globals, policy violations, or nested report evidence.
            For entrypoint host-call policy repair or audit JSON, use `get_entrypoint_policy`; this low-level view does not distinguish direct imported calls from transitive local helper calls.
        """
        input = WasmInputClass
        output = ListExportsResultClass
    }

    tool("search_exports") {
        summary = "Search exports by name and resolve common export metadata."
        guidance = """
            Search exported functions, tables, memories, globals, and tags by case-insensitive export-name substrings.
            Function matches include resolved signatures, and memory matches include decoded page limits.
            Use this when logs, host code, or expected API names identify exports symbolically and you need the exact exported index.
        """
        input = SearchExportsInput
        output = SearchExportsResultClass
    }

    tool("list_exported_functions") {
        summary = "List exported functions within a closed range of the export section."
        guidance = """
            List exported functions from a WebAssembly (.wasm) module using a closed export-range slice.
            Pass `entriesRange = { from, to }` with inclusive export-list positions; do not use `offset/limit` or `start/end`.
            The range applies to export-list order, not to `funcidx`, and each result resolves to either an imported or a defined function entry.
            This is the primary MCP tool for export inventories, export diffs, function-signature extraction, and import-vs-local origin checks.
            Prefer this over `list_exports` for export diffs or signature extraction, because each result already includes the resolved function entry, signature, and origin.
            The signature strings are usually ready to use as-is, so `list_types` is often unnecessary unless you need raw type definitions.
            For entrypoint host-call policy repair or audit JSON, use `get_entrypoint_policy`; it already filters exported local functions to direct imported-call entrypoints and returns the copy-ready payload.
        """
        input = ListExportedFunctionsInput
        output = ListExportedFunctionsResultClass
    }
}
