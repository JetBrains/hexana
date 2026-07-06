package org.jetbrains.hexana.mcp.tools

private const val BINARIES_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.binaries"

val BinaryModuleIdentityResultClass = class_("BinaryModuleIdentityResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    nullable("path", of = string()).describe("Caller-supplied path label, when the binary came from a path.")
    string("format").describe("Stable format identifier such as `wasm`, `elf`, `mach_o`, `pe`, `coff`, `archive`, or `generic`.")
    string("displayFormat").describe("Human-readable format label.")
    int("byteLength").describe("Binary size in bytes.")
    nullable("architecture", of = string()).describe("Architecture or machine label when known.")
    nullable("endian", of = string()).describe("Endian label when known.")
    nullable("wordSize", of = string()).describe("Word size label when known.")
}

val BinarySummaryCountsResultClass = class_("BinarySummaryCountsResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    nullable("functions", of = int()).describe("Function count when the format exposes functions.")
    nullable("types", of = int()).describe("Type count when the format exposes type entries.")
    nullable("memories", of = int()).describe("Memory declaration count when known.")
    nullable("globals", of = int()).describe("Global declaration count when known.")
    nullable("tables", of = int()).describe("Table declaration count when known.")
    nullable("symbols", of = int()).describe("Symbol count when symbol tables are available.")
    nullable("imports", of = int()).describe("Import count when the format exposes imports.")
    nullable("exports", of = int()).describe("Export count when the format exposes exports.")
    nullable("sections", of = int()).describe("Section count when the format exposes sections.")
    nullable("segments", of = int()).describe("Segment or segment-like count when the format exposes it.")
    nullable("loadCommands", of = int()).describe("Mach-O load command count when known.")
    nullable("architectures", of = int()).describe("Universal/fat binary architecture slice count when known.")
    nullable("coreModules", of = int()).describe("Embedded core module count for WebAssembly components when known.")
    nullable("nestedComponents", of = int()).describe("Nested component count for WebAssembly components when known.")
    nullable("relocations", of = int()).describe("Relocation count when known.")
    nullable("strings", of = int()).describe("Printable string count when scanned for native formats.")
    nullable("archiveMembers", of = int()).describe("Archive member count when the format is an archive.")
}

val BinaryWarningResultClass = class_("BinaryWarningResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    string("code").describe("Stable warning code.")
    string("message").describe("Short warning message.")
}

val BinarySummaryResultClass = class_("BinarySummaryResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    embed("module", of = BinaryModuleIdentityResultClass).describe("Format, path label, size, and architecture identity.")
    embed("counts", of = BinarySummaryCountsResultClass).describe("Compact format-specific entity counts.")
    list("warnings", of = BinaryWarningResultClass).describe("Warnings for partial or unsupported deeper parsing.")
}

private val QueryBinaryInput = BinaryInputClass.with("QueryBinaryInput") {
    string("operation", default = "overview")
        .describe("Query operation. Default `overview` returns compact format/count identity. Supported operations include `find`, `all`, `functions`, `symbols`, `imports`, `exports`, `sections`, `segments`, `relocations`, `strings`, `archive_members`, `debug`, and `native_image`; unsupported formats return a warning instead of raw bytes.")
        .maxLength(32)
    nullable("entityTypes", of = list(of = string()))
        .describe("Optional record-kind filters such as `function`, `symbol`, `import`, `export`, `section`, `segment`, `relocation`, `string`, `archive_member`, `debug`, or `native_image`. Mostly useful with `operation=find` or `operation=all`.")
        .maxItems(20)
    nullable("query", of = list(of = string()))
        .describe("Optional case-insensitive terms used to filter names, summaries, attributes, locations, or evidence.")
        .maxItems(20)
    int("offset", default = 0)
        .describe("Zero-based record offset after filtering.")
        .minimum(0)
    int("limit", default = 25)
        .describe("Maximum records to consider before byte budgeting. The implementation clamps values above 100.")
        .minimum(1)
        .maximum(100)
    string("detail", default = "summary")
        .describe("Detail level: `summary` for compact records or `evidence` for extra offsets and parser record context.")
        .maxLength(32)
    int("maxBytes", default = 24_000)
        .describe("Approximate maximum JSON response bytes. Start small; the implementation clamps values above 60000.")
        .minimum(1)
        .maximum(60_000)
}

private val BinaryFunctionPageInput = BinaryInputClass.with("BinaryFunctionPageInput") {
    int("offset", default = 0)
        .describe("Zero-based function offset after filtering.")
        .minimum(0)
    int("limit", default = 25)
        .describe("Maximum functions to return. The implementation clamps values above 100.")
        .minimum(1)
        .maximum(100)
}

private val SearchBinaryFunctionsInput = BinaryFunctionPageInput.with("SearchBinaryFunctionsInput") {
    list("query", of = string())
        .describe("Case-insensitive terms; all terms must match one function record.")
        .maxItems(20)
}

private val BinaryRecordLocationResultClass = class_("BinaryRecordLocationResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    nullable("index", of = int()).describe("Primary record index when one exists.")
    nullable("fileOffset", of = long()).describe("File offset when known.")
    nullable("virtualAddress", of = long()).describe("Virtual address or RVA when known.")
    nullable("size", of = long()).describe("Record, range, or entity size when known.")
    nullable("section", of = string()).describe("Owning section or segment label when known.")
}

private val BinaryRecordAttributeResultClass = class_("BinaryRecordAttributeResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    string("name").describe("Attribute name.")
    string("value").describe("Attribute value rendered as a compact string.")
}

private val BinaryRecordResultClass = class_("BinaryRecordResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    string("kind").describe("Record kind such as `function`, `import`, `export`, `section`, `symbol`, `string`, `segment`, `relocation`, `load_command`, or `archive_member`.")
    nullable("name", of = string()).describe("Record name when the binary format exposes one.")
    string("summary").describe("Compact human-readable summary.")
    nullable("location", of = BinaryRecordLocationResultClass).describe("Stable location fields when offsets, addresses, sizes, or sections are known.")
    list("attributes", of = BinaryRecordAttributeResultClass).describe("Bounded key-value attributes for this record.")
    list("evidence", of = string()).describe("Extra parser context returned only for `detail=evidence` where available.")
}

private val BinaryOmittedResultClass = class_("BinaryOmittedResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    string("kind").describe("Omitted entity kind.")
    int("count").describe("Number of omitted records.")
    string("reason").describe("Why records were omitted.")
}

private val QueryBinaryResultClass = class_("QueryBinaryResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    embed("module", of = BinaryModuleIdentityResultClass).describe("Format, path label, size, and architecture identity.")
    string("operation").describe("Executed operation.")
    int("total").describe("Total matching records after filtering and before offset/limit/maxBytes bounds.")
    int("offset").describe("Executed zero-based record offset.")
    int("returned").describe("Number of records returned.")
    nullable("nextOffset", of = int()).describe("Next offset to request more records, or null when there are no more records.")
    list("records", of = BinaryRecordResultClass).describe("Bounded query records.")
    list("omitted", of = BinaryOmittedResultClass).describe("Omission summaries for records outside offset/limit/maxBytes bounds.")
    list("warnings", of = BinaryWarningResultClass).describe("Non-fatal query warnings.")
}

private val BinaryFunctionResultClass = class_("BinaryFunctionResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    nullable("funcidx", of = int()).describe("Unified Wasm function index.")
    nullable("symbolIndex", of = int()).describe("Native symbol index.")
    string("source").describe("Wasm source or native symbol source.")
    nullable("name", of = string()).describe("Best display name.")
    nullable("demangledName", of = string()).describe("Native demangled name.")
    nullable("mangledName", of = string()).describe("Native mangled name.")
    boolean("imported").describe("Imported or undefined function flag.")
    nullable("thunkLike", of = boolean()).describe("Native thunk/stub-like flag.")
    nullable("importModule", of = string()).describe("Wasm import module.")
    nullable("importName", of = string()).describe("Wasm import name.")
    nullable("typeidx", of = int()).describe("Wasm function type index.")
    nullable("section", of = string()).describe("Native section label.")
    nullable("virtualAddress", of = long()).describe("Native virtual address.")
    nullable("fileOffset", of = long()).describe("File offset.")
    nullable("size", of = long()).describe("Function or range size.")
    nullable("rangeConfidence", of = string()).describe("Native range confidence.")
}

private val BinaryFunctionsResultClass = class_("BinaryFunctionsResult", kotlinPackage = BINARIES_MODEL_PACKAGE) {
    embed("module", of = BinaryModuleIdentityResultClass).describe("Binary identity.")
    list("query", of = string()).describe("Applied search terms.")
    int("total").describe("Total matching functions.")
    int("offset").describe("Executed offset.")
    int("limit").describe("Executed limit.")
    int("returned").describe("Returned count.")
    nullable("nextOffset", of = int()).describe("Next page offset, if any.")
    list("functions", of = BinaryFunctionResultClass).describe("Paged function records.")
    list("omitted", of = BinaryOmittedResultClass).describe("Omitted function counts.")
    list("warnings", of = BinaryWarningResultClass).describe("Non-fatal warnings.")
}

internal val BinaryTools: List<ToolSpec> = tools {
    tool("summarize_binary") {
        summary = "Summarize a Wasm or native binary with compact identity and entity counts."
        surfaces = productionCliSurface("summarize-binary", ToolGroup.Binary, ToolOutputPolicy.ToolDefined)
        guidance = """
            Use this first for an unknown binary before choosing a format-specific follow-up tool.
            Returns format, path label, size, known architecture/endian details, and bounded counts only; never raw bytes or unbounded lists.
            For WebAssembly core modules, `summarize_module` remains the Wasm-specific summary with its existing payload shape.
            Warnings indicate unsupported or partial deeper parsing while preserving the compact summary when possible.
        """
        input = BinaryInputClass
        inputPattern = ToolInputPattern.BinaryOnly
        output = BinarySummaryResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readBinarySummary"
    }

    tool("query_binary") {
        summary = "Query Wasm or native binary records with bounded output."
        surfaces = productionCliSurface("query-binary", ToolGroup.Binary, ToolOutputPolicy.ToolDefined)
        guidance = """
            Use this when `summarize_binary` shows the format has relevant records and you need a compact, paged lookup across generic binary entities.
            Prefer a focused `operation` over `all`; use `entityTypes`, `query`, `offset`, and `limit` to avoid broad scans, and request `detail=evidence` only for the few records that need parser offsets or record context.
            For archives, `operation=archive_members` enumerates members and `operation=symbols` searches symbols inside openable member objects while annotating the containing archive member.
            This is a broad query tool like `query_artifacts`: it never returns raw byte dumps or disassembly, and unsupported entity groups return warnings rather than failing unrelated formats.
            For WebAssembly core modules, keep using the existing Wasm-specific tools when the request maps to a focused Wasm surface.
        """
        input = QueryBinaryInput
        inputPattern = ToolInputPattern.BoundedBinaryQuery
        output = QueryBinaryResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readBinaryQuery"
    }

    tool("list_binary_functions") {
        summary = "List Wasm funcidx or native symbol-backed functions with focused fields."
        surfaces = productionCliSurface("list-binary-functions", ToolGroup.Binary, ToolOutputPolicy.ToolDefined)
        guidance = """
            Use this for stack, source, or function inventories after `summarize_binary` shows functions.
            Wasm results use unified `funcidx`; native results include symbol/source, names, import/thunk flags, section, address, offset, size, and range confidence.
        """
        input = BinaryFunctionPageInput
        inputPattern = ToolInputPattern.BinaryFunctionPage
        output = BinaryFunctionsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readBinaryFunctions"
    }

    tool("search_binary_functions") {
        summary = "Search Wasm funcidx or native symbol-backed functions by focused function fields."
        surfaces = productionCliSurface("search-binary-functions", ToolGroup.Binary, ToolOutputPolicy.ToolDefined)
        guidance = """
            Use this when you have a name, import, source, section, address, offset, or range-confidence term.
            Search is case-insensitive; all `query` terms must match one function record.
        """
        input = SearchBinaryFunctionsInput
        inputPattern = ToolInputPattern.BinaryFunctionSearch
        output = BinaryFunctionsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.searchBinaryFunctions"
    }
}
