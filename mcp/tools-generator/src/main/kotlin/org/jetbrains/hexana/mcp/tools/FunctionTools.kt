package org.jetbrains.hexana.mcp.tools

private const val MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.functions"

val ListFunctionsInput = WasmInputClass.with("ListFunctionsInput") {
    int("offset", default = 0)
        .describe("Offset into the unified function index space")
        .minimum(0)
    int("limit", default = 200)
        .describe("Maximum number of entries to return")
        .minimum(1)
}

val FunctionsForIndicesInput = indicesInput(
    "FunctionsForIndicesInput",
    "List of unified function indices (`funcidx`) to resolve.",
)

val ResolveFunctionSignatureInput = WasmInputClass.with("ResolveFunctionSignatureInput") {
    int("funcidx")
        .describe("Unified function index (`funcidx`) to resolve.")
        .minimum(0)
}

val DisassembleFunctionInput = WasmInputClass.with("DisassembleFunctionInput") {
    int("funcidx")
        .describe("Unified function index (`funcidx`) to disassemble.")
        .minimum(0)
}

val GetFunctionCallGraphInput = indicesInput(
    "GetFunctionCallGraphInput",
    "List of unified function indices (`funcidx`) whose direct call graph and effect summary should be returned.",
)

val SearchFunctionsByNameInput = WasmInputClass.with("SearchFunctionsByNameInput") {
    string("query")
        .describe("Case-insensitive substring to search in import, export, and debug function names.")
        .maxLength(200)
    int("limit", default = 50)
        .describe("Maximum number of matching function name entries to return.")
        .minimum(1)
        .maximum(200)
}

val WasmFrameInputClass = class_("WasmFrameInput", kotlinPackage = MODEL_PACKAGE) {
    int("funcidx")
        .describe("Unified function index from a stack frame or crash report.")
        .minimum(0)
    nullable("codeOffset", of = long())
        .describe("Optional absolute byte offset inside the `.wasm` file for this frame instruction.")
    nullable("module", of = string())
        .describe("Optional module path or runtime module label from the original frame.")
        .maxLength(500)
    nullable("label", of = string())
        .describe("Optional raw frame label preserved from the original crash report.")
        .maxLength(500)
}

val ResolveWasmFramesInput = WasmInputClass.with("ResolveWasmFramesInput") {
    nullable("frames", of = list(of = WasmFrameInputClass))
        .describe("Optional crash frames to resolve. Each frame must come from the original stack or crash report and is keyed by `funcidx`; include the report's absolute `codeOffset` when present.")
        .maxItems(200)
    nullable("crashReportPath", of = string())
        .describe("Optional path to a crash report JSON file containing stack frames with `funcidx`, code offsets, and raw labels. Prefer this over manually constructing `frames` when a report file exists, including previous/current crash reports resolved one build at a time.")
        .maxLength(1000)
    nullable("sourceMapPath", of = string())
        .describe("Optional path to a WebAssembly source map for source-location resolution.")
        .maxLength(1000)
}

val ResolveFunctionSymbolsInput = indicesInput(
    "ResolveFunctionSymbolsInput",
    "List of unified function indices (`funcidx`) to resolve to compact symbols and metadata.",
).with("ResolveFunctionSymbolsInput") {
    nullable("sourceMapPath", of = string())
        .describe("Optional path to a WebAssembly source map for source-location resolution.")
        .maxLength(1000)
}

val SourceLocationInputClass = class_("SourceLocationInput", kotlinPackage = MODEL_PACKAGE) {
    nullable("funcidx", of = int())
        .describe("Optional unified function index used to choose the function body start offset.")
        .minimum(0)
    nullable("codeOffset", of = long())
        .describe("Optional absolute byte offset inside the `.wasm` file.")
}

val ResolveSourceLocationsInput = WasmInputClass.with("ResolveSourceLocationsInput") {
    list("locations", of = SourceLocationInputClass)
        .describe("Source-location queries keyed by `funcidx`, absolute `codeOffset`, or both.")
        .maxItems(200)
    nullable("sourceMapPath", of = string())
        .describe("Optional path to a WebAssembly source map. DWARF locations are used when no source-map hit exists.")
        .maxLength(1000)
}

val GetLocalsForFunctionsInput = indicesInput(
    "GetLocalsForFunctionsInput",
    "List of unified function indices (`funcidx`) whose locals should be returned.",
)

val GetInstructionsForFunctionsInput = indicesInput(
    "GetInstructionsForFunctionsInput",
    "List of unified function indices (`funcidx`) whose instructions should be returned.",
)

val FunctionImportResultClass = class_("FunctionImportResult") {
    string("module").describe("Import module name.")
    string("name").describe("Import field name.")
}

val FunctionSignatureResultClass = class_("FunctionSignatureResult") {
    list("params", of = string()).describe("Parameter types, in declaration order.")
    list("results", of = string()).describe("Result types, in declaration order.")
    list("ret", of = string()).serialName("return")
        .describe("Legacy alias for `results`; prefer `results` for new report shapes.")
}

val FunctionSummaryResultClass = class_("FunctionSummaryResult") {
    int("funcidx").describe("Funcidx in the unified function index space.")
    nullable("typeidx", of = int()).describe("Function type index, when resolved.")
    nullable("signature", of = FunctionSignatureResultClass).describe("Parsed signature when available.")
    nullable("importInfo", of = FunctionImportResultClass).serialName("import")
        .describe("Import descriptor when the function is imported.")
    list("exportedAs", of = string()).describe("Export names pointing at this function.")
    string("definedIn").describe("Which section the function lives in.")
    nullable("locationKind", of = string()).describe("Stable location kind for imported functions, currently `import`.")
    nullable("module", of = string()).describe("Import module name when the function is imported.")
    nullable("name", of = string()).describe("Import field name when the function is imported.")
    nullable("locationDisplay", of = string())
        .describe("Stable human-readable import location, for example `import host.bind_external`.")
    nullable("codeOffset", long()).describe("Byte offset of the function body in the module.")
    nullable("codeSize", long()).describe("Size of the function body in bytes.")
    nullable("instructionCount", long()).describe("Number of decoded instructions in the body.")
}

val FunctionsResultClass = class_("FunctionsResult", kotlinPackage = MODEL_PACKAGE) {
    list("functions", of = FunctionSummaryResultClass)
        .describe("Resolved function entries.")
}

val CodeSectionOffsetResultClass = class_("CodeSectionOffsetResult") {
    int("funcidx").describe("Unified function index for this defined function body.")
    long("offset").describe("Byte offset of the function body in the module.")
    long("size").describe("Size of the function body in bytes.")
}

val ListCodeSectionOffsetsResultClass = class_("ListCodeSectionOffsetsResult", kotlinPackage = MODEL_PACKAGE) {
    list("functions", of = CodeSectionOffsetResultClass)
        .describe("Code-section byte ranges keyed by unified function index.")
}

val ResolveFunctionSignatureResultClass = class_("ResolveFunctionSignatureResult", kotlinPackage = MODEL_PACKAGE) {
    int("funcidx").describe("Funcidx in the unified function index space.")
    nullable("typeidx", of = int()).describe("Function type index, when resolved.")
    list("params", of = string()).describe("Parameter types, in declaration order.")
    list("results", of = string()).describe("Result types, in declaration order.")
    string("kind").describe("Function origin: `import`, `export`, or `internal`.")
    nullable("module", of = string()).describe("Import module name for imported functions.")
    nullable("importName", of = string()).describe("Import field name for imported functions.")
    list("exportNames", of = string()).describe("Export names pointing at this function.")
}

val DisassembleFunctionSignatureResultClass = class_("DisassembleFunctionSignatureResult") {
    list("params", of = string()).describe("Parameter types, in declaration order.")
    list("results", of = string()).describe("Result types, in declaration order.")
}

val DisassembledLocalGroupResultClass = class_("DisassembledLocalGroupResult") {
    int("count").describe("Number of local slots in this grouped declaration.")
    string("type").describe("Local type (`i32`, `i64`, `v128`, reference types, etc.).")
}

val DisassembledInstructionResultClass = class_("DisassembledInstructionResult") {
    string("op").describe("Instruction mnemonic.")
    list("args", of = string()).describe("Rendered WAT operands for this instruction.")
    string("text").describe("Full decoded instruction text.")
    nullable("offset", of = long()).describe("Instruction byte offset when the parser provides one.")
}

val DisassembleFunctionResultClass = class_("DisassembleFunctionResult", kotlinPackage = MODEL_PACKAGE) {
    int("funcidx").describe("Funcidx in the unified function index space.")
    nullable("typeidx", of = int()).describe("Function type index, when resolved.")
    nullable("signature", of = DisassembleFunctionSignatureResultClass).describe("Parsed signature when available.")
    list("locals", of = string()).describe("Grouped local types for defined functions.")
    list("localGroups", of = DisassembledLocalGroupResultClass)
        .describe("Grouped local declarations with declaration counts preserved.")
    boolean("isImported").describe("Whether this function is imported and has no body.")
    boolean("isExported").describe("Whether this function is exported by at least one export entry.")
    list("exportNames", of = string()).describe("Export names pointing at this function.")
    nullable("importInfo", of = FunctionImportResultClass).serialName("import")
        .describe("Import descriptor when this function is imported.")
    nullable("codeOffset", of = long()).describe("Byte offset of the code entry for defined functions.")
    nullable("codeSize", of = long()).describe("Size of the code entry in bytes for defined functions.")
    list("instructions", of = DisassembledInstructionResultClass).describe("Decoded instruction records.")
    nullable("wat", of = string()).describe("Best-effort WAT-style function text for defined functions.")
    nullable("hex", of = string()).describe("Raw code-entry bytes rendered as space-separated hex for defined functions.")
}

val FunctionCallGraphLocalResultClass = class_("FunctionCallGraphLocalResult") {
    int("count").describe("Number of local slots in this grouped declaration.")
    string("type").describe("Local type (`i32`, `i64`, `v128`, reference types, etc.).")
}

val FunctionCallTargetResultClass = class_("FunctionCallTargetResult") {
    int("funcidx").describe("Resolved callee funcidx in the unified function index space.")
    string("kind").describe("Callee origin: `import` or `local`.")
    nullable("typeidx", of = int()).describe("Function type index, when resolved.")
    nullable("signature", of = FunctionSignatureResultClass).describe("Parsed signature when available.")
    nullable("importInfo", of = FunctionImportResultClass).serialName("import")
        .describe("Import descriptor when the callee is imported.")
    list("exportedAs", of = string()).describe("Export names pointing at the callee.")
}

val FunctionDirectCallResultClass = class_("FunctionDirectCallResult") {
    string("op").describe("Call instruction mnemonic, for example `call` or `return_call`.")
    string("text").describe("Full decoded call instruction text.")
    nullable("offset", of = long()).describe("Instruction byte offset when the parser provides one.")
    int("targetFuncidx").describe("Raw target funcidx encoded by the call instruction.")
    nullable("target", of = FunctionCallTargetResultClass)
        .describe("Resolved callee metadata when the target funcidx exists.")
}

val FunctionIndirectCallResultClass = class_("FunctionIndirectCallResult") {
    string("op").describe("Indirect call instruction mnemonic, for example `call_indirect`.")
    string("text").describe("Full decoded indirect call instruction text.")
    nullable("offset", of = long()).describe("Instruction byte offset when the parser provides one.")
    int("typeidx").describe("Function type index used by the indirect call.")
    int("tableidx").describe("Table index used by the indirect call.")
}

val FunctionMemoryWriteResultClass = class_("FunctionMemoryWriteResult") {
    string("op").describe("Memory-writing instruction mnemonic.")
    string("text").describe("Full decoded memory-writing instruction text.")
    nullable("offset", of = long()).describe("Instruction byte offset when the parser provides one.")
    nullable("memoryOffset", of = int()).describe("Static memory offset from memarg for store-like instructions.")
    nullable("alignment", of = int()).describe("Alignment from memarg for store-like instructions.")
}

val FunctionCallGraphResultClass = class_("FunctionCallGraphResult") {
    int("funcidx").describe("Funcidx in the unified function index space.")
    string("kind").describe("Function origin: `import` or `local`.")
    nullable("typeidx", of = int()).describe("Function type index, when resolved.")
    nullable("signature", of = FunctionSignatureResultClass).describe("Parsed signature when available.")
    nullable("importInfo", of = FunctionImportResultClass).serialName("import")
        .describe("Import descriptor when the function is imported.")
    list("exportedAs", of = string()).describe("Export names pointing at this function.")
    nullable("codeOffset", of = long()).describe("Byte offset of the code entry for defined functions.")
    nullable("codeSize", of = long()).describe("Size of the code entry in bytes for defined functions.")
    nullable("instructionCount", of = long()).describe("Number of decoded instructions in the body.")
    list("locals", of = FunctionCallGraphLocalResultClass)
        .describe("Grouped local declarations with declaration counts preserved.")
    list("directCalls", of = FunctionDirectCallResultClass)
        .describe("Direct `call` and `return_call` sites with resolved target metadata.")
    list("directImportedCalls", of = FunctionDirectCallResultClass)
        .describe("Subset of direct calls whose resolved target is an imported function.")
    list("indirectCalls", of = FunctionIndirectCallResultClass)
        .describe("Indirect call sites with type and table indices.")
    list("directMemoryWrites", of = FunctionMemoryWriteResultClass)
        .describe("Direct memory-writing instruction sites in this function body.")
}

val GetFunctionCallGraphResultClass = class_("GetFunctionCallGraphResult", kotlinPackage = MODEL_PACKAGE) {
    list("functions", of = FunctionCallGraphResultClass)
        .describe("Per-function direct call graph and memory-write summaries.")
}

val FunctionNameSearchSignatureResultClass = class_("FunctionNameSearchSignatureResult") {
    list("params", of = string()).describe("Parameter types, in declaration order.")
    list("results", of = string()).describe("Result types, in declaration order.")
}

val FunctionNameSearchResultClass = class_("FunctionNameSearchResult") {
    int("funcidx").describe("Funcidx in the unified function index space.")
    string("name").describe("Matched function name.")
    string("kind").describe("Name source: `import`, `export`, or `internal`.")
    nullable("module", of = string()).describe("Import module name for imported functions.")
    nullable("importName", of = string()).describe("Import field name for imported functions.")
    nullable("exportName", of = string()).describe("Export name for exported functions.")
    nullable("signature", of = FunctionNameSearchSignatureResultClass).describe("Parsed signature when available.")
    list("locals", of = string()).describe("Local types for defined functions.")
    string("repr").describe("Compact WAT-like function representation.")
    nullable("offset", of = long()).describe("Byte offset of the function body in the module.")
}

val SearchFunctionsByNameResultClass = class_("SearchFunctionsByNameResult", kotlinPackage = MODEL_PACKAGE) {
    list("functions", of = FunctionNameSearchResultClass)
        .describe("Matching function name entries.")
}

val FunctionCodeRangeResultClass = class_("FunctionCodeRangeResult") {
    long("offset").describe("Absolute byte offset of the function body in the module.")
    long("size").describe("Size of the function body in bytes.")
}

val FunctionSourceLocationResultClass = class_("FunctionSourceLocationResult") {
    string("file").describe("Resolved source file path.")
    int("line").describe("Resolved source line.")
    int("column").describe("Resolved source column.")
}

val ResolvedWasmFrameResultClass = class_("ResolvedWasmFrameResult") {
    int("frameIndex").describe("Zero-based position of the input frame in the request.")
    int("funcidx").describe("Unified function index from the input frame.")
    nullable("inputCodeOffset", of = long()).describe("Absolute code offset supplied for the input frame.")
    nullable("module", of = string()).describe("Original frame module path or runtime module label, when supplied.")
    nullable("rawLabel", of = string()).describe("Original raw frame label, when supplied.")
    string("origin").describe("Function origin: `import`, `local`, or `unknown`.")
    string("symbol").describe("Best resolved symbol name or the `funcidx` fallback.")
    string("symbolSource")
        .describe("Symbol source: `import`, `name_section`, `dwarf_name`, `dwarf_linkage_name`, `export`, or `funcidx_fallback`.")
    nullable("signature", of = FunctionSignatureResultClass)
        .describe("Parsed function signature when available. Uses `params` and `results`, with `return` retained as a legacy alias.")
    nullable("importInfo", of = FunctionImportResultClass).serialName("import")
        .describe("Import descriptor when the function is imported.")
    list("exportedAs", of = string()).describe("Export names pointing at this function.")
    nullable("codeRange", of = FunctionCodeRangeResultClass).describe("Function body byte range for local functions.")
    nullable("sourceLocation", of = FunctionSourceLocationResultClass)
        .describe("Resolved source-map or DWARF source location when available.")
    nullable("sourceFallbackReason", of = string())
        .describe("Machine-readable reason when source location could not be resolved, for example `import_has_no_code_range` or `invalid_funcidx`.")
    list("warnings", of = string()).describe("Non-fatal resolution warnings for this frame.")
}

val WasmFrameHighlightResultClass = class_("WasmFrameHighlightResult") {
    int("frameIndex").describe("Zero-based position of the input frame in the request.")
    int("funcidx").describe("Unified function index from the highlighted frame.")
    string("origin").describe("Function origin: `import`, `local`, or `unknown`.")
    string("symbol").describe("Best resolved symbol name or the `funcidx` fallback.")
    string("symbolSource")
        .describe("Symbol source: `import`, `name_section`, `dwarf_name`, `dwarf_linkage_name`, `export`, or `funcidx_fallback`.")
    list("exportedAs", of = string()).describe("Export names pointing at this function.")
    nullable("importInfo", of = FunctionImportResultClass).serialName("import")
        .describe("Import descriptor when the highlighted frame is imported.")
    nullable("sourceLocation", of = FunctionSourceLocationResultClass)
        .describe("Resolved source-map or DWARF source location for local application frames.")
    string("reason").describe("Machine-readable reason this frame was highlighted.")
}

val WasmFrameHighlightsResultClass = class_("WasmFrameHighlightsResult") {
    nullable("topApplicationFrame", of = WasmFrameHighlightResultClass)
        .describe("Compact highlight for the first local frame in stack order with a resolved source location.")
    nullable("callerApplicationFrame", of = WasmFrameHighlightResultClass)
        .describe("Compact highlight for the next source-resolved local frame below the top application frame.")
    nullable("callerExportFrame", of = WasmFrameHighlightResultClass)
        .describe("Compact highlight for the next exported local frame below the top application frame, when present.")
    list("applicationFrames", of = WasmFrameHighlightResultClass)
        .describe("Source-resolved local application frames in stack order.")
    list("importFrames", of = WasmFrameHighlightResultClass)
        .describe("Imported frames in stack order.")
    list("fallbackFrames", of = WasmFrameHighlightResultClass)
        .describe("Invalid or `funcidx` fallback frames in stack order.")
}

val CrashReportMetadataResultClass = class_("CrashReportMetadataResult") {
    nullable("path", of = string()).describe("Crash report path used as input.")
    nullable("incidentId", of = string()).describe("Incident identifier from the crash report, when present.")
    nullable("crashEventId", of = string()).describe("Crash event identifier from the crash report, when present.")
    nullable("buildId", of = string()).describe("Build identifier from the crash report, when present.")
    nullable("module", of = string()).describe("Module path or label from the crash report, when present.")
    int("frameCount").describe("Number of frames read from the crash report.")
}

val SkippedWasmFrameResultClass = class_("SkippedWasmFrameResult") {
    int("frameIndex").describe("Zero-based position in the resolved frame list.")
    int("funcidx").describe("Unified function index for the skipped frame.")
    string("origin").describe("Function origin: `import`, `local`, or `unknown`.")
    string("reason").describe("Machine-readable reason this frame was skipped for top-application-frame selection.")
}

val ResolveWasmFramesResultClass = class_("ResolveWasmFramesResult", kotlinPackage = MODEL_PACKAGE) {
    list("frames", of = ResolvedWasmFrameResultClass)
        .describe("Resolved crash frame records.")
    nullable("crashReport", of = CrashReportMetadataResultClass)
        .describe("Crash report metadata when frames were loaded from `crashReportPath`.")
    nullable("sourceMapPath", of = string())
        .describe("Source map path used for source-location resolution, including auto-discovered adjacent maps.")
    nullable("topApplicationFrame", of = ResolvedWasmFrameResultClass)
        .describe("First local frame in stack order with a resolved source location.")
    nullable("applicationFrameSummary", of = string())
        .describe("Report-ready prose naming source-resolved local application frames in stack order.")
    nullable("reportSummary", of = string())
        .describe("Report-ready incident summary naming the top application frame, caller/export context, imports, and invalid/fallback frames.")
    embed("frameHighlights", of = WasmFrameHighlightsResultClass)
        .describe("Compact structured highlights for application, import, and fallback frame triage.")
    list("skippedFrames", of = SkippedWasmFrameResultClass)
        .describe("Imported, unknown, or otherwise non-source-resolved frames skipped when choosing the top application frame.")
}

val ResolvedFunctionSymbolResultClass = class_("ResolvedFunctionSymbolResult") {
    int("funcidx").describe("Unified function index from the input request.")
    string("origin").describe("Function origin: `import`, `local`, or `unknown`.")
    string("symbol").describe("Best resolved symbol name or the `funcidx` fallback.")
    string("symbolSource")
        .describe("Symbol source: `import`, `name_section`, `dwarf_name`, `dwarf_linkage_name`, `export`, or `funcidx_fallback`.")
    nullable("typeidx", of = int()).describe("Function type index, when resolved.")
    nullable("signature", of = FunctionSignatureResultClass).describe("Parsed function signature when available.")
    nullable("importInfo", of = FunctionImportResultClass).serialName("import")
        .describe("Import descriptor when the function is imported.")
    list("exportedAs", of = string()).describe("Export names pointing at this function.")
    nullable("codeRange", of = FunctionCodeRangeResultClass).describe("Function body byte range for local functions.")
    nullable("sourceLocation", of = FunctionSourceLocationResultClass)
        .describe("Resolved source-map or DWARF source location when available.")
    list("warnings", of = string()).describe("Non-fatal resolution warnings for this function.")
}

val ResolveFunctionSymbolsResultClass = class_("ResolveFunctionSymbolsResult", kotlinPackage = MODEL_PACKAGE) {
    list("symbols", of = ResolvedFunctionSymbolResultClass)
        .describe("Resolved function symbol records.")
}

val ResolvedSourceLocationResultClass = class_("ResolvedSourceLocationResult") {
    int("queryIndex").describe("Zero-based position of the input location query.")
    nullable("funcidx", of = int()).describe("Resolved or supplied unified function index.")
    nullable("codeOffset", of = long()).describe("Resolved or supplied absolute code offset.")
    nullable("codeRange", of = FunctionCodeRangeResultClass).describe("Function body byte range when known.")
    nullable("sourceLocation", of = FunctionSourceLocationResultClass)
        .describe("Resolved source-map or DWARF source location when available.")
    nullable("source", of = string()).describe("Location source: `source_map` or `dwarf`.")
    list("warnings", of = string()).describe("Non-fatal resolution warnings for this query.")
}

val ResolveSourceLocationsResultClass = class_("ResolveSourceLocationsResult", kotlinPackage = MODEL_PACKAGE) {
    list("locations", of = ResolvedSourceLocationResultClass)
        .describe("Resolved source-location records.")
}

val FunctionInstructionsResultClass = class_("FunctionInstructionsResult") {
    int("funcidx").describe("Funcidx whose body was decoded.")
    list("instructions", of = string()).describe("Decoded instruction text, one entry per instruction.")
}

val GetInstructionsForFunctionsResultClass = class_(
    "GetInstructionsForFunctionsResult",
    kotlinPackage = MODEL_PACKAGE,
) {
    list("functions", of = FunctionInstructionsResultClass)
        .describe("Per-function decoded instructions.")
}

val LocalDeclarationResultClass = class_("LocalDeclarationResult") {
    int("local").describe("Local slot index.")
    string("type").describe("Local type (`i32`, `i64`, `f32`, `f64`, `v128`, reference types, etc.).")
}

val FunctionLocalsResultClass = class_("FunctionLocalsResult") {
    int("funcidx").describe("Funcidx whose locals were resolved.")
    list("locals", of = LocalDeclarationResultClass).describe("Grouped local declarations for the function.")
}

val GetLocalsForFunctionsResultClass = class_("GetLocalsForFunctionsResult", kotlinPackage = MODEL_PACKAGE) {
    list("functions", of = FunctionLocalsResultClass)
        .describe("Per-function local declarations.")
}

internal val FunctionTools: List<ToolSpec> = tools {
    tool("list_functions") {
        summary = "List imported and defined functions with offset/limit pagination."
        guidance = """
            List imported and defined functions from a WebAssembly (.wasm) module using offset/limit pagination.
            The offset is in the unified function index space, so imports appear before defined bodies.
            Use this when you need one mixed index space over both imports and defined bodies; for import-only analysis, prefer `list_imports`.
            Imported entries include structured location fields plus `locationDisplay`, a stable string suitable for report fields such as `where`.
            Use this to map or inspect `funcidx` ranges before targeted body lookup with `get_instructions_for_functions`.
        """
        input = ListFunctionsInput
        inputPattern = ToolInputPattern.FunctionPage
        output = FunctionsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readFunctions"
    }

    tool("list_code_section_offsets") {
        summary = "List byte offsets and sizes for defined function bodies."
        guidance = """
            List code-section byte ranges for all defined WebAssembly function bodies.
            The returned `funcidx` values are in the unified function index space, so imported functions are skipped and defined functions keep their import-offset-adjusted indices.
            Use this to connect disassembly, binary offsets, source locations, and body-level triage.
        """
        input = WasmInputClass
        inputPattern = ToolInputPattern.WasmOnly
        output = ListCodeSectionOffsetsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readCodeSectionOffsets"
    }

    tool("functions_for_indices") {
        summary = "Resolve function entries for the given unified function indices."
        guidance = """
            Resolve imported and defined function entries for the requested unified function indices.
            Use this as a targeted follow-up when you already know the exact `funcidx` values you need.
        """
        input = FunctionsForIndicesInput
        inputPattern = ToolInputPattern.Indices
        output = FunctionsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readFunctionsByIndices"
    }

    tool("resolve_function_signature") {
        summary = "Resolve the type index and signature for one function index."
        guidance = """
            Resolve the type index and signature for one unified WebAssembly function index.
            Use this as a targeted lookup when logs, stack traces, exports, or other tools already identify the `funcidx`.
        """
        input = ResolveFunctionSignatureInput
        inputPattern = ToolInputPattern.Funcidx
        output = ResolveFunctionSignatureResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readFunctionSignature"
    }

    tool("disassemble_function") {
        summary = "Disassemble one function by unified function index."
        guidance = """
            Disassemble one WebAssembly function by `funcidx`, returning signature, import/export metadata, locals, decoded instruction records, WAT-style text, and raw code bytes when the function has a body.
            Imported functions return metadata with empty body fields because imports have no code entry.
            Use this when the request has narrowed the search to one function and needs a compact, production-oriented body snapshot.
        """
        input = DisassembleFunctionInput
        inputPattern = ToolInputPattern.Funcidx
        output = DisassembleFunctionResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readFunctionDisassembly"
    }

    tool("get_function_call_graph") {
        summary = "Return resolved direct calls and memory write sites for function indices."
        guidance = """
            Return a compact per-function call graph for requested unified WebAssembly `funcidx` values.
            The result resolves direct `call` and `return_call` targets to import/export/signature metadata, separates imported callees, reports `call_indirect` sites, preserves grouped local counts, and lists direct memory-writing instructions.
            Use this when a stack trace, export, log, or previous lookup identifies one or more function indices and you need production-oriented dependency/effect context without reading full disassembly.
        """
        input = GetFunctionCallGraphInput
        inputPattern = ToolInputPattern.Indices
        output = GetFunctionCallGraphResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readFunctionCallGraph"
    }

    tool("search_functions_by_name") {
        summary = "Search function names across imports, exports, and debug names."
        guidance = """
            Search import names, export names, and WebAssembly name-section function names with a case-insensitive substring query.
            Use this before targeted lookups when you know a symbolic name from logs, host bindings, exports, or source-level debugging.
        """
        input = SearchFunctionsByNameInput
        inputPattern = ToolInputPattern.FunctionNameSearch
        output = SearchFunctionsByNameResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readFunctionsByName"
    }

    tool("resolve_wasm_frames") {
        summary = "Aggregate WebAssembly crash-report and stack-frame triage from function indices, labels, and offsets."
        guidance = """
            Resolve crash-report or stack-frame `funcidx` values, raw labels, and optional code offsets into symbols, signatures, exported names, code ranges, source locations, top application frame, skipped-frame reasons, and report-ready summaries.
            Prefer `crashReportPath` with a matching `sourceMapPath` when a crash report JSON is available; for previous/current build regression triage, call this once for each build's module, crash report, and source map, then compare the returned `topApplicationFrame` symbols and exports.
            Use this aggregate workflow for crash triage, frame regression analysis, and source-map-backed incident reports before lower-level symbol/source lookups or full disassembly.
        """
        input = ResolveWasmFramesInput
        inputPattern = ToolInputPattern.WasmFrames
        output = ResolveWasmFramesResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.resolveWasmFrames"
    }

    tool("resolve_function_symbols") {
        summary = "Focused lookup for function indices, symbols, signatures, and metadata."
        guidance = """
            Batch-resolve unified WebAssembly function indices to compact symbol records.
            Symbol resolution prefers name-section entries, then DWARF names, DWARF linkage names, export names, and finally an explicit `funcidx_fallback`.
            Use this focused lookup when you already have `funcidx` values and need stable names, signatures, imports, exports, code ranges, or debug source locations outside a crash-frame workflow.
            For crash reports, stack traces, top application frame selection, or previous/current frame regression analysis, call `resolve_wasm_frames` instead.
        """
        input = ResolveFunctionSymbolsInput
        inputPattern = ToolInputPattern.SourceMappedIndices
        output = ResolveFunctionSymbolsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.resolveFunctionSymbols"
    }

    tool("resolve_source_locations") {
        summary = "Focused source lookup for WebAssembly code offsets or function indices."
        guidance = """
            Map absolute WebAssembly code offsets or unified `funcidx` values to source-map or DWARF source locations.
            When only `funcidx` is supplied, the function body start offset is used; when only `codeOffset` is supplied, the containing local function is inferred when possible.
            This is source-location lookup only; it does not perform full frame triage or enrich results with symbol fallback ranking, signatures, exports, and code ranges.
            For crash reports, stack traces, top application frame selection, or previous/current frame regression analysis, call `resolve_wasm_frames` instead.
        """
        input = ResolveSourceLocationsInput
        inputPattern = ToolInputPattern.SourceLocations
        output = ResolveSourceLocationsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.resolveSourceLocations"
    }

    tool("get_locals_for_functions") {
        summary = "Return grouped local declarations for the requested function indices."
        guidance = """
            Return grouped local declarations for the requested unified function indices.
            Imported functions return empty local lists because they have no body.
        """
        input = GetLocalsForFunctionsInput
        inputPattern = ToolInputPattern.Indices
        output = GetLocalsForFunctionsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readLocalsForFunctions"
    }

    tool("get_instructions_for_functions") {
        summary = "Return decoded instruction text for the requested function indices."
        guidance = """
            Return decoded instruction text for the requested unified function indices.
            Imported functions return empty instruction lists because they have no body.
            This is the primary MCP tool for WebAssembly body analysis, including direct `call` and `call_indirect` inspection, and is usually preferable to external disassemblers or shell-based Wasm tools.
            For direct host-call analysis, pair it with `list_imports` or `list_functions` so `call N` can be mapped back to the imported or exported function entry.
        """
        input = GetInstructionsForFunctionsInput
        inputPattern = ToolInputPattern.Indices
        output = GetInstructionsForFunctionsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readInstructionsForFunctions"
    }
}
