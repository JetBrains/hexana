package org.jetbrains.hexana.mcp.tools

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.mcp.query.readDataSections
import org.jetbrains.hexana.mcp.query.readDataSegmentsByRange
import org.jetbrains.hexana.mcp.query.readElementSegments
import org.jetbrains.hexana.mcp.query.readExportedFunctions
import org.jetbrains.hexana.mcp.query.readExports
import org.jetbrains.hexana.mcp.query.readFunctions
import org.jetbrains.hexana.mcp.query.readFunctionsByIndices
import org.jetbrains.hexana.mcp.query.readGlobals
import org.jetbrains.hexana.mcp.query.readGlobalsByIndices
import org.jetbrains.hexana.mcp.query.readImportFunctions
import org.jetbrains.hexana.mcp.query.readInstructionsForFunctions
import org.jetbrains.hexana.mcp.query.readLocalsForFunctions
import org.jetbrains.hexana.mcp.query.readMemory
import org.jetbrains.hexana.mcp.query.readMemoryByIndices
import org.jetbrains.hexana.mcp.query.readModuleSummary
import org.jetbrains.hexana.mcp.query.readTypes
import org.jetbrains.hexana.mcp.query.readTypesByIndices

private const val WASM_INPUT_VALIDATION =
    "Runtime validation requires exactly one of `path`, `bytesBase64`, or `bytes`."

val WasmInputClass = class_("WasmInput") {
    nullable("path", string())
        .describe("Path to a local `.wasm` file readable by the Hexana MCP server. $WASM_INPUT_VALIDATION")
    nullable("bytesBase64", string())
        .describe("Base64-encoded `.wasm` bytes. $WASM_INPUT_VALIDATION")
    nullable("bytes", list(of = int()))
        .describe("Raw `.wasm` bytes as integers in the range 0..255. $WASM_INPUT_VALIDATION")
    oneOf("path", "bytesBase64", "bytes")
}

private val ClosedIntRange = class_("ClosedIntRange") {
    int("from").describe("Inclusive zero-based lower bound.")
    int("to").describe("Inclusive zero-based upper bound.")
}

private val HalfOpenIntRange = class_("HalfOpenIntRange") {
    int("from").describe("Inclusive zero-based lower bound.")
    nullable("to", int()).describe("Optional exclusive upper bound.")
}

val ListFunctionsInput = WasmInputClass.with("ListFunctionsInput") {
    int("offset", default = 0)
        .describe("Offset into the unified function index space")
    int("limit", default = 200)
        .describe("Maximum number of entries to return")
}

private fun indicesInput(name: String, indicesDescription: String): ClassSpec =
    WasmInputClass.with(name) {
        list("indices", of = int())
            .describe(indicesDescription)
    }

val FunctionsForIndicesInput = indicesInput(
    "FunctionsForIndicesInput",
    "List of unified function indices (`funcidx`) to resolve.",
)
val GetGlobalsForIndicesInput = indicesInput(
    "GetGlobalsForIndicesInput",
    "List of global indices (`globalidx`) to return.",
)
val GetMemoryForIndicesInput = indicesInput(
    "GetMemoryForIndicesInput",
    "List of memory indices (`memidx`) to return.",
)
val GetTypesForIndicesInput = indicesInput(
    "GetTypesForIndicesInput",
    "List of type indices (`typeidx`) to return.",
)
val GetLocalsForFunctionsInput = indicesInput(
    "GetLocalsForFunctionsInput",
    "List of unified function indices (`funcidx`) whose locals should be returned.",
)
val GetInstructionsForFunctionsInput = indicesInput(
    "GetInstructionsForFunctionsInput",
    "List of unified function indices (`funcidx`) whose instructions should be returned.",
)

val ListExportedFunctionsInput = WasmInputClass.with("ListExportedFunctionsInput") {
    embed("entriesRange", of = ClosedIntRange)
        .describe("Closed zero-based export slice. Pass `entriesRange = { from, to }`; both bounds are inclusive.")
}

val ListDataInput = WasmInputClass.with("ListDataInput") {
    embed("entriesRange", of = ClosedIntRange)
        .describe("Closed zero-based data-segment slice. Requires `from` and `to`, both inclusive.")
}

val ListDataSegmentsInput = WasmInputClass.with("ListDataSegmentsInput") {
    embed("segmentRange", of = HalfOpenIntRange)
        .describe("Half-open zero-based data-segment slice. `from` is required; `to` is optional and treated as exclusive.")
    boolean("includeExports", default = false)
        .describe("Include exported symbols associated with the returned segments.")
    boolean("includeGlobals", default = true)
        .describe("Include globals referenced by segment offset expressions.")
    nullable("minByteLength", int())
        .describe("Optional minimum raw byte length for returned segments.")
}

// Imports

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

// Exports --------------------------------------------------------------------

val ExportResultClass = class_("ExportResult") {
    string("name").describe("Export field name.")
    long("externIndex").describe("Index into the exported address space (funcidx, tableidx, memidx, or globalidx).")
    byte("kind").describe("WebAssembly export kind byte (0=func, 1=table, 2=memory, 3=global).")
}

val ListExportsResultClass = class_("ListExportsResult") {
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

val ListExportedFunctionsResultClass = class_("ListExportedFunctionsResult") {
    int("from").describe("Inclusive lower bound that was requested.")
    int("to").describe("Inclusive upper bound that was requested.")
    list("returned", of = ExportedFunctionResultClass).describe("Entries inside the requested range.")
    int("total").describe("Total number of exported functions in the module.")
    boolean("truncate").describe("True when the range was clipped to the module's export count.")
    nullable("nextOffset", int()).describe("Next offset to continue pagination; null when the range was exhausted.")
}

// Functions

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
    nullable("signature", of = FunctionSignatureResultClass).describe("Parsed signature when available.")
    nullable("importInfo", of = FunctionImportResultClass).serialName("import")
        .describe("Import descriptor when the function is imported.")
    list("exportedAs", of = string()).describe("Export names pointing at this function.")
    string("definedIn").describe("Which section the function lives in.")
    nullable("codeOffset", long()).describe("Byte offset of the function body in the module.")
    nullable("codeSize", long()).describe("Size of the function body in bytes.")
    nullable("instructionCount", long()).describe("Number of decoded instructions in the body.")
}

val FunctionsResultClass = class_("FunctionsResult") {
    list("functions", of = FunctionSummaryResultClass)
        .describe("Resolved function entries.")
}

val FunctionInstructionsResultClass = class_("FunctionInstructionsResult") {
    int("funcidx").describe("Funcidx whose body was decoded.")
    list("instructions", of = string()).describe("Decoded instruction text, one entry per instruction.")
}

val GetInstructionsForFunctionsResultClass = class_("GetInstructionsForFunctionsResult") {
    list("functions", of = FunctionInstructionsResultClass)
        .describe("Per-function decoded instructions.")
}

val LocalDeclarationResultClass = class_("LocalDeclarationResult") {
    int("local").describe("Local slot index.")
    string("type").describe("Local type (`i32`, `i64`, `f32`, `f64`, etc.).")
}

val FunctionLocalsResultClass = class_("FunctionLocalsResult") {
    int("funcidx").describe("Funcidx whose locals were resolved.")
    list("locals", of = LocalDeclarationResultClass).describe("Grouped local declarations for the function.")
}

val GetLocalsForFunctionsResultClass = class_("GetLocalsForFunctionsResult") {
    list("functions", of = FunctionLocalsResultClass)
        .describe("Per-function local declarations.")
}

// Globals

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

// Memory

val MemoryLimitsResultClass = class_("MemoryLimitsResult") {
    string("addressType").describe("Address space type (`i32` or `i64`).")
    long("min").describe("Minimum number of pages.")
    nullable("max", long()).describe("Maximum number of pages, when the memory is bounded.")
    boolean("hasMax").describe("True when an explicit upper bound was declared.")
    string("range").describe("Human-readable range such as `1..ε`.")
    string("flag").describe("Raw limits flag byte as hex (`0x00`, `0x01`, etc.).")
}

val MemoryResultClass = class_("MemoryResult") {
    int("memoryIdx").describe("Index in the unified memory index space.")
    embed("limit", of = MemoryLimitsResultClass).describe("Page limits for the memory.")
    string("definedIn").describe("Section the memory is declared in.")
}

val ListMemoryResultClass = class_("ListMemoryResult") {
    list("memories", of = MemoryResultClass).describe("Imported and defined memories.")
}

// Types

val TypeFieldResultClass = class_("TypeFieldResult") {
    string("type").describe("Field storage type.")
    boolean("mutable").describe("True when the field is mutable.")
}

val TypeResultClass = class_("TypeResult") {
    string("kind").describe("Type kind (`func`, `struct`, `array`, etc.).")
    int("typeidx").describe("Type index.")
    nullable("elementType", of = TypeFieldResultClass).describe("Array element type, when applicable.")
    nullable("superTypes", of = list(of = uint())).describe("Supertype indices for subtyping, when applicable.")
    nullable("fields", of = list(of = TypeFieldResultClass)).describe("Struct fields, when applicable.")
    nullable("entries", of = list(of = string())).describe("Entry signatures, when applicable.")
    nullable("params", of = list(of = string())).describe("Function parameter types, when applicable.")
    nullable("retTypes", of = list(of = string())).describe("Function return types, when applicable.")
}

val TypesResultClass = class_("TypesResult") {
    list("types", of = TypeResultClass).describe("Declared type entries.")
}

// Elements

val ElementSegmentResultClass = class_("ElementSegmentResult") {
    int("elementidx").describe("Element segment index.")
    int("kind").describe("Raw element segment kind byte.")
    string("mode").describe("Segment mode (`active`, `passive`, `declarative`).")
    nullable("tableIdx", int()).describe("Target table index for active segments.")
    nullable("offsetExpr", of = list(of = string())).describe("Offset expression tokens for active segments.")
    string("elementType").describe("Element reference type.")
    nullable("elements", of = list(of = string())).describe("Element expressions or funcidx list.")
}

val ListElementSegmentsResultClass = class_("ListElementSegmentsResult") {
    list("segments", of = ElementSegmentResultClass).describe("Element segments declared by the module.")
}

// Data

val DataEntryResultClass = class_("DataEntryResult") {
    int("kind").describe("Raw data-entry kind byte.")
    int("index").describe("Data-entry index within the returned slice.")
    long("offset").describe("Byte offset of the entry in the module.")
    bytes("data")
        .describe("Raw data payload (serialised as Base64).")
        .withSerializer("org.jetbrains.hexana.mcp.serialization.Base64ByteArraySerializer")
    nullable("memoryIndex", long()).describe("Target memory index for active entries.")
    nullable("expression", string()).describe("Offset expression for active entries.")
}

val ListDataSectionsResultClass = class_("ListDataSectionsResult") {
    int("from").describe("Inclusive lower bound that was requested.")
    int("to").describe("Inclusive upper bound that was requested.")
    list("returned", of = DataEntryResultClass).describe("Entries inside the requested range.")
    int("totalSegments").serialName("total_segments")
        .describe("Total number of data-section entries in the module.")
    boolean("truncate").describe("True when the range was clipped.")
    nullable("nextOffset", int()).describe("Next offset to continue pagination; null when exhausted.")
}

val DataSegmentMemoryLimitsResultClass = class_("DataSegmentMemoryLimitsResult") {
    string("addressType").describe("Address space type.")
    long("min").describe("Minimum number of pages.")
    nullable("max", long()).describe("Maximum number of pages, when bounded.")
    boolean("hasMax").describe("True when an explicit upper bound was declared.")
    string("range").describe("Human-readable range.")
    string("flag").describe("Raw limits flag byte as hex.")
}

val DataSegmentMemoryResultClass = class_("DataSegmentMemoryResult") {
    int("memidx").describe("Memory index.")
    embed("limits", of = DataSegmentMemoryLimitsResultClass).describe("Page limits for the memory.")
    string("definedIn").describe("Section the memory is declared in.")
}

val DataSegmentGlobalResultClass = class_("DataSegmentGlobalResult") {
    int("globalidx").describe("Global index.")
    nullable("module", string()).describe("Import module name when the global is imported.")
    nullable("name", string()).describe("Import or exported name when applicable.")
    string("type").describe("Global value type.")
    boolean("mutable").describe("True when the global is mutable.")
    nullable("initExpr", string()).describe("Initialization expression in WAT syntax.")
    string("definedIn").describe("Section the global is declared in.")
    list("exportedAs", of = string()).describe("Export names pointing at this global.")
}

val DataSegmentResultClass = class_("DataSegmentResult") {
    int("dataidx").describe("Data segment index.")
    string("mode").describe("Segment mode (`active`, `passive`).")
    nullable("memory", int()).describe("Target memory index for active segments.")
    nullable("offsetExpr", string()).describe("Offset expression for active segments.")
    int("byteLength").describe("Byte length of the segment payload.")
    nullable("hexPreview", string()).describe("Hex-encoded preview of the first bytes.")
    nullable("utf8Preview", string()).describe("Best-effort UTF-8 preview of the segment content.")
    boolean("rawAvailable").describe("True when the raw bytes are available via `list_data`.")
}

val DataSegmentsSummaryResultClass = class_("DataSegmentsSummaryResult") {
    int("totalSegments").describe("Total segment count.")
    int("activeSegments").describe("Count of active segments.")
    int("passiveSegments").describe("Count of passive segments.")
    int("memories").describe("Number of memories referenced by segments.")
}

/**
 * Placeholder class kept for forward-compat with the MCP wire shape: `ListDataSegmentsResult.linkingMetadata`
 * will eventually carry linker/CG-produced section metadata (e.g. DWARF, wasm-ld link info). For now we expose
 * the nullable field so clients can parse payloads without flinching when the field is absent.
 */
val DataSegmentLinkingMetadataResultClass = class_("DataSegmentLinkingMetadataResult") {
    // Intentionally empty — schema holds no fields yet; the class exists to reserve the name.
}

val ListDataSegmentsResultClass = class_("ListDataSegmentsResult") {
    list("memory", of = DataSegmentMemoryResultClass).describe("Memories referenced by the segments.")
    list("globals", of = DataSegmentGlobalResultClass).describe("Globals referenced by segment offset expressions.")
    list("segments", of = DataSegmentResultClass).describe("Data segment metadata.")
    nullable("linkingMetadata", of = DataSegmentLinkingMetadataResultClass).describe("Optional linker/CG metadata block.")
    list("exportedSymbols", of = string()).describe("Exported symbol names, when `includeExports` was requested.")
    embed("summary", of = DataSegmentsSummaryResultClass).describe("Counts across the returned segments.")
}

// Summary

val SummarizeModuleImportsClass = class_("SummarizeModuleImports") {
    int("functions").describe("Number of imported functions.")
    int("memories").describe("Number of imported memories.")
    int("tables").describe("Number of imported tables.")
    int("globals").describe("Number of imported globals.")
}

val SummarizeModuleResultClass = class_("SummarizeModuleResult") {
    int("functions").describe("Total function count (imports + defined).")
    int("exports").describe("Number of exports declared by the module.")
    int("memories").describe("Number of memories declared by the module.")
    int("globals").describe("Number of globals declared by the module.")
    int("tables").describe("Number of tables declared by the module.")
    int("types").describe("Number of type entries declared by the module.")
    embed("imports", of = SummarizeModuleImportsClass).describe("Import breakdown by kind.")
    int("moduleSize").describe("Size of the module binary in bytes.")
}

// ==============================================================================
// Tool catalog.
// ==============================================================================

val HexanaTools: List<ToolSpec> = tools {

    tool("summarize_module") {
        summary = "Summarize a WebAssembly module — counts of imports, exports, memories, globals, functions, types."
        guidance = """
            Summarize a WebAssembly (.wasm) module with high-level counts for its major sections and declarations.
            Includes the nested import breakdown and total module size, making it a low-cost overview before calling heavier tools.
            For Wasm analysis tasks, this is usually the best first MCP call before choosing more specific import, export, or instruction tools.
            After this overview, prefer `list_imports` for import/link analysis, `list_exported_functions` for export analysis, and `get_instructions_for_functions` for body analysis.
        """
        input = WasmInputClass
        output = SummarizeModuleResultClass
        handler = CommonByteBuffer::readModuleSummary
    }

    tool("list_imports") {
        summary = "List imported functions declared by a `.wasm` module."
        guidance = """
            List imported functions declared by a WebAssembly (.wasm) module.
            This is the primary MCP tool for import inventories, link-mismatch analysis, and mapping direct host calls to imported functions.
            Returns only function imports from the unified function index space, including the `funcidx` values needed to map direct `call` targets back to imports.
            For direct host-call analysis, pair these `funcidx` values with `get_instructions_for_functions` to resolve `call N` back to `module.name` imports.
        """
        input = WasmInputClass
        output = ListImportsResultClass
        handler = CommonByteBuffer::readImportFunctions
    }

    tool("list_exports") {
        summary = "List exports declared by a `.wasm` module."
        guidance = """
            List exports declared by a WebAssembly (.wasm) module.
            Includes exported functions, tables, memories, and globals; `kind` uses the standard tags 0=function, 1=table, 2=memory, 3=global.
            This is the light name-and-kind view of exports.
            Use this when you only need exported names, kinds, or raw external indices.
            If you need function signatures or origins, skip directly to `list_exported_functions`.
        """
        input = WasmInputClass
        output = ListExportsResultClass
        handler = CommonByteBuffer::readExports
    }

    tool("list_globals") {
        summary = "List imported and defined globals from a `.wasm` module."
        guidance = """
            List imported and defined globals declared by a WebAssembly (.wasm) module.
            Returns each global with its value type, mutability, and normalized initializer expression.
        """
        input = WasmInputClass
        output = ListGlobalsResultClass
        handler = CommonByteBuffer::readGlobals
    }

    tool("list_types") {
        summary = "List type entries declared by a `.wasm` module."
        guidance = """
            List type entries declared by a WebAssembly (.wasm) module.
            Includes function types and GC-related composite types when present.
        """
        input = WasmInputClass
        output = TypesResultClass
        handler = CommonByteBuffer::readTypes
    }

    tool("list_memory") {
        summary = "List imported and defined memories from a `.wasm` module."
        guidance = """
            List imported and defined memories declared by a WebAssembly (.wasm) module.
            Returns memory indices together with decoded WebAssembly limits.
        """
        input = WasmInputClass
        output = ListMemoryResultClass
        handler = CommonByteBuffer::readMemory
    }

    tool("list_element_segments") {
        summary = "List element segments declared by a `.wasm` module."
        guidance = """
            List element segments declared by a WebAssembly (.wasm) module.
            Covers active, passive, and declarative segments, including function-index and expression-based initializers.
        """
        input = WasmInputClass
        output = ListElementSegmentsResultClass
        handler = CommonByteBuffer::readElementSegments
    }

    tool("list_functions") {
        summary = "List imported and defined functions with offset/limit pagination."
        guidance = """
            List imported and defined functions from a WebAssembly (.wasm) module using offset/limit pagination.
            The offset is in the unified function index space, so imports appear before defined bodies.
            Use this when you need one mixed index space over both imports and defined bodies; for import-only analysis, prefer `list_imports`.
            Use this to map or inspect `funcidx` ranges before targeted body lookup with `get_instructions_for_functions`.
        """
        input = ListFunctionsInput
        output = FunctionsResultClass
        handler = CommonByteBuffer::readFunctions
    }

    tool("functions_for_indices") {
        summary = "Resolve function entries for the given unified function indices."
        guidance = """
            Resolve imported and defined function entries for the requested unified function indices.
            Use this as a targeted follow-up when another tool already gave you the exact `funcidx` values.
            Use this for targeted lookup when you already know the `funcidx` values you need.
        """
        input = FunctionsForIndicesInput
        output = FunctionsResultClass
        handler = CommonByteBuffer::readFunctionsByIndices
    }

    tool("get_globals_for_indices") {
        summary = "Return globals for the requested indices from the unified global index space."
        guidance = """
            Return only the requested globals from the unified global index space.
            Use this for targeted analysis when `list_globals` would return more data than needed.
        """
        input = GetGlobalsForIndicesInput
        output = ListGlobalsResultClass
        handler = CommonByteBuffer::readGlobalsByIndices
    }

    tool("get_memory_for_indices") {
        summary = "Return memories for the requested indices from the unified memory index space."
        guidance = """
            Return only the requested memories from the unified memory index space.
            Useful for multi-memory or wasm64 modules when only a subset matters.
        """
        input = GetMemoryForIndicesInput
        output = ListMemoryResultClass
        handler = CommonByteBuffer::readMemoryByIndices
    }

    tool("get_types_for_indices") {
        summary = "Return type entries for the requested indices from the type index space."
        guidance = """
            Return only the requested type entries from the type index space.
            Use this when you need the types referenced by specific functions, globals, or tables.
        """
        input = GetTypesForIndicesInput
        output = TypesResultClass
        handler = CommonByteBuffer::readTypesByIndices
    }

    tool("get_locals_for_functions") {
        summary = "Return grouped local declarations for the requested function indices."
        guidance = """
            Return grouped local declarations for the requested unified function indices.
            Imported functions return empty local lists because they have no body.
        """
        input = GetLocalsForFunctionsInput
        output = GetLocalsForFunctionsResultClass
        handler = CommonByteBuffer::readLocalsForFunctions
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
        output = GetInstructionsForFunctionsResultClass
        handler = CommonByteBuffer::readInstructionsForFunctions
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
        output = ListExportedFunctionsResultClass
        handler = CommonByteBuffer::readExportedFunctions
    }

    tool("list_data") {
        summary = "Return raw data-section entries for a closed data-segment range."
        guidance = """
            Return raw data-section entries for a closed data-segment range.
            This is the high-volume raw-bytes view; the `data` field is base64-encoded, and `list_data_segments` is usually cheaper when previews and metadata are sufficient.
        """
        input = ListDataInput
        output = ListDataSectionsResultClass
        handler = CommonByteBuffer::readDataSections
    }

    tool("list_data_segments") {
        summary = "Return data-segment metadata with range slicing and optional export/global context."
        guidance = """
            Return data-segment metadata with range slicing and optional export/global context.
            Prefer this over `list_data` when previews, sizes, and offset context are enough.
        """
        input = ListDataSegmentsInput
        output = ListDataSegmentsResultClass
        handler = CommonByteBuffer::readDataSegmentsByRange
    }
}
