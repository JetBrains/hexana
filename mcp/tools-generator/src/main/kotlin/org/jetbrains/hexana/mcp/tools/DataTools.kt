package org.jetbrains.hexana.mcp.tools

private const val MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.data"

private val HalfOpenIntRange = class_("HalfOpenIntRange") {
    int("from").describe("Inclusive zero-based lower bound.").minimum(0)
    nullable("to", int()).describe("Optional exclusive upper bound.").minimum(0)
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
        .minimum(0)
}

val AuditDataPayloadPolicyInput = WasmInputClass.with("AuditDataPayloadPolicyInput") {
    string("policyPath").describe("Path to a payload policy JSON file with `policy_id` and `forbidden_payloads` entries.")
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

val ListDataSectionsResultClass = class_("ListDataSectionsResult", kotlinPackage = MODEL_PACKAGE) {
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

val ListDataSegmentsResultClass = class_("ListDataSegmentsResult", kotlinPackage = MODEL_PACKAGE) {
    list("memory", of = DataSegmentMemoryResultClass).describe("Memories referenced by the segments.")
    list("globals", of = DataSegmentGlobalResultClass).describe("Globals referenced by segment offset expressions.")
    list("segments", of = DataSegmentResultClass).describe("Data segment metadata.")
    nullable("linkingMetadata", of = DataSegmentLinkingMetadataResultClass).describe("Optional linker/CG metadata block.")
    list("exportedSymbols", of = string()).describe("Exported symbol names, when `includeExports` was requested.")
    embed("summary", of = DataSegmentsSummaryResultClass).describe("Counts across the returned segments.")
}

val DataPayloadPolicyMatchResultClass = class_("DataPayloadPolicyMatchResult") {
    string("policyEntryId").describe("Policy entry id for the matched forbidden payload.")
    string("forbiddenString").describe("Forbidden payload string confirmed in raw data bytes.")
    string("severity").describe("Severity copied from the policy entry.")
    int("segmentIndex").describe("Zero-based data segment index.")
    string("mode").describe("Segment mode (`active`, `passive`).")
    nullable("memory", int()).describe("Target memory index for active segments.")
    nullable("offsetExpr", string()).describe("Offset expression for active segments.")
    int("byteLength").describe("Byte length of the matching segment payload.")
    boolean("rawBytesConfirmed").describe("True because the raw segment bytes contain the forbidden string.")
}

val DataPayloadPolicyMinimalFixResultClass = class_("DataPayloadPolicyMinimalFixResult") {
    list("removeOrReplaceSegments", of = int()).describe("Data segment indices that contain forbidden payload strings.")
}

val DataPayloadPolicyReportMinimalFixResultClass = class_("DataPayloadPolicyReportMinimalFixResult") {
    list("removeOrReplaceSegments", of = int()).serialName("remove_or_replace_segments")
        .describe("Data segment indices that contain forbidden payload strings.")
}

val DataPayloadPolicyReportMatchResultClass = class_("DataPayloadPolicyReportMatchResult") {
    string("policyEntryId").serialName("policy_entry_id").describe("Policy entry id for the matched forbidden payload.")
    string("forbiddenString").serialName("forbidden_string").describe("Forbidden payload string confirmed in raw data bytes.")
    string("severity").describe("Severity copied from the policy entry.")
    int("segmentIndex").serialName("segment_index").describe("Zero-based data segment index.")
    string("mode").describe("Segment mode (`active`, `passive`).")
    nullable("memory", int()).describe("Target memory index for active segments.")
    nullable("offsetExpr", string()).serialName("offset_expr").describe("Offset expression for active segments.")
    int("byteLength").serialName("byte_length").describe("Byte length of the matching segment payload.")
    boolean("rawBytesConfirmed").serialName("raw_bytes_confirmed")
        .describe("True because the raw segment bytes contain the forbidden string.")
}

val DataPayloadPolicyRecommendedReportResultClass = class_("DataPayloadPolicyRecommendedReportResult") {
    string("policyId").serialName("policy_id").describe("Policy id copied from the policy file.")
    nullable("module", string()).describe("Wasm module path echoed from input `path`, when available.")
    int("scannedSegments").serialName("scanned_segments").describe("Total number of scanned data segments.")
    list("matches", of = DataPayloadPolicyReportMatchResultClass).describe("Report-ready policy matches.")
    list("cleanSegments", of = int()).serialName("clean_segments").describe("Data segment indices with no forbidden payload strings.")
    embed("minimalFix", of = DataPayloadPolicyReportMinimalFixResultClass).serialName("minimal_fix")
        .describe("Minimal segment-level remediation summary.")
}

val AuditDataPayloadPolicyResultClass = class_("AuditDataPayloadPolicyResult", kotlinPackage = MODEL_PACKAGE) {
    string("policyId").describe("Policy id copied from the policy file.")
    nullable("module", string()).describe("Wasm module path echoed from input `path`, when available.")
    nullable("policyPath", string()).describe("Payload policy path echoed from input `policyPath`, when available.")
    int("scannedSegments").describe("Total number of scanned data segments.")
    list("matches", of = DataPayloadPolicyMatchResultClass).describe("Policy matches confirmed against raw segment bytes.")
    list("cleanSegments", of = int()).describe("Data segment indices with no forbidden payload strings.")
    embed("minimalFix", of = DataPayloadPolicyMinimalFixResultClass).describe("Minimal segment-level remediation summary.")
    embed("recommendedReport", of = DataPayloadPolicyRecommendedReportResultClass)
        .describe("Recommended policy audit report object with stable JSON field names.")
}

internal val DataTools: List<ToolSpec> = tools {
    tool("list_data") {
        summary = "Return raw data-section entries for a closed data-segment range."
        surfaces = advancedHiddenCliSurface()
        guidance = """
            Range `entriesRange` is closed: both `from` and `to` are inclusive zero-based segment indices.
            This is the high-volume raw-bytes view; the `data` field is base64-encoded, and `list_data_segments` is usually cheaper when previews and metadata are sufficient.
        """
        input = ListDataInput
        inputPattern = ToolInputPattern.ClosedRange(
            parameterName = "entriesRange",
        )
        output = ListDataSectionsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readDataSections"
    }

    tool("list_data_segments") {
        summary = "Return data-segment metadata with range slicing and optional export/global context."
        surfaces = productionCliSurface("list-data-segments", ToolGroup.Data, ToolOutputPolicy.ToolDefined)
        guidance = """
            Range `segmentRange` is half-open: `from` is inclusive, `to` is exclusive (omit `to` to read to the end).
            Prefer this over `list_data` when previews, sizes, and offset context are enough.
        """
        input = ListDataSegmentsInput
        inputPattern = ToolInputPattern.DataSegments
        output = ListDataSegmentsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readDataSegmentsByRange"
    }

    tool("audit_data_payload_policy") {
        summary = "Audit data segments against a payload policy JSON file and return report-ready matches."
        surfaces = productionCliSurface("audit-data-payload-policy", ToolGroup.Data, ToolOutputPolicy.ToolDefined)
        guidance = """
            Use this as the shortest path for embedded payload audits that have a Wasm module and `policyPath`.
            It reads forbidden strings from the policy, confirms matches against raw data-segment bytes, and returns `recommendedReport` as a report-ready object.
        """
        input = AuditDataPayloadPolicyInput
        inputPattern = ToolInputPattern.DataPayloadPolicyAudit
        output = AuditDataPayloadPolicyResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.auditDataPayloadPolicy"
    }
}
