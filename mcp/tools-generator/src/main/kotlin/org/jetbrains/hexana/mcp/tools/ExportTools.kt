package org.jetbrains.hexana.mcp.tools

private const val MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.exports"

val ListExportedFunctionsInput = WasmInputClass.with("ListExportedFunctionsInput") {
    embed("entriesRange", of = ClosedIntRange)
        .describe("Closed zero-based export slice. Pass `entriesRange = { from, to }`; both bounds are inclusive.")
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
            If you need function signatures or origins, skip directly to `list_exported_functions`.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmOnly
        output = ListExportsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readExports"
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
        """
        input = ListExportedFunctionsInput
        inputPattern = ToolInputPattern.ClosedRange(
            parameterName = "entriesRange",
        )
        output = ListExportedFunctionsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readExportedFunctions"
    }
}
