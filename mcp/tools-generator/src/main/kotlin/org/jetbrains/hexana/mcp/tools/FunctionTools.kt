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
    list("ret", of = string()).serialName("return").describe("Return types, in declaration order.")
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
            Use this when a task has narrowed the search to one function and needs a compact, production-oriented body snapshot.
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
