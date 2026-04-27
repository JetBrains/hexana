package org.jetbrains.hexana.mcp.tools

private val HalfOpenIntRange = class_("HalfOpenIntRange") {
    int("from").describe("Inclusive zero-based lower bound.")
    nullable("to", int()).describe("Optional exclusive upper bound.")
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

val DataEntryResultClass = class_("DataEntryResult") {
    int("kind").describe("Raw data-entry kind byte.")
    int("index").describe("Data-entry index within the returned slice.")
    long("offset").describe("Byte offset of the entry in the module.")
    bytes("data")
        .describe("Raw data payload (serialised as Base64).")
        .withSerializer("org.jetbrains.hexana.mcp.model.data.Base64ByteArraySerializer")
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
}

val ListDataSegmentsResultClass = class_("ListDataSegmentsResult") {
    list("memory", of = DataSegmentMemoryResultClass).describe("Memories referenced by the segments.")
    list("globals", of = DataSegmentGlobalResultClass).describe("Globals referenced by segment offset expressions.")
    list("segments", of = DataSegmentResultClass).describe("Data segment metadata.")
    nullable("linkingMetadata", of = DataSegmentLinkingMetadataResultClass).describe("Optional linker/CG metadata block.")
    list("exportedSymbols", of = string()).describe("Exported symbol names, when `includeExports` was requested.")
    embed("summary", of = DataSegmentsSummaryResultClass).describe("Counts across the returned segments.")
}

internal val DataTools: List<ToolSpec> = tools {
    tool("list_data") {
        summary = "Return raw data-section entries for a closed data-segment range."
        guidance = """
            Range `entriesRange` is closed: both `from` and `to` are inclusive zero-based segment indices.
            This is the high-volume raw-bytes view; the `data` field is base64-encoded, and `list_data_segments` is usually cheaper when previews and metadata are sufficient.
        """
        input = ListDataInput
        output = ListDataSectionsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readDataSections"
    }

    tool("list_data_segments") {
        summary = "Return data-segment metadata with range slicing and optional export/global context."
        guidance = """
            Range `segmentRange` is half-open: `from` is inclusive, `to` is exclusive (omit `to` to read to the end).
            Prefer this over `list_data` when previews, sizes, and offset context are enough.
        """
        input = ListDataSegmentsInput
        output = ListDataSegmentsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readDataSegmentsByRange"
    }
}
