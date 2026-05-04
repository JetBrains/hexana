package org.jetbrains.hexana.mcp.tools

private const val ARTIFACT_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.artifacts"

private val QueryArtifactsInput = WasmInputClass.with("QueryArtifactsInput") {
    nullable("baselinePath", of = string())
        .describe("Optional baseline `.wasm` path used by diff-style operations.")
        .maxLength(4096)
    string("operation", default = "overview")
        .describe("Query operation: `overview`, `find`, `diff`, `trace`, `triage`, `public_surface`, `memory_contract`, `dispatch_table_diff`, or `host_call_diff`.")
        .maxLength(32)
    nullable("entityTypes", of = list(of = string()))
        .describe("Optional entity filter. Supported values include `export`, `import`, `memory`, `global`, `function`, and `element`.")
        .maxItems(20)
    nullable("query", of = list(of = string()))
        .describe("Optional case-insensitive terms used to filter matched entities or trace roots.")
        .maxItems(20)
    nullable("startName", of = string())
        .describe("Optional function, import, export, or debug-name term used by `operation = trace` or `operation = host_call_diff`.")
        .maxLength(256)
    nullable("startFuncidx", of = int())
        .describe("Optional unified function index used by `operation = trace` or `operation = host_call_diff`.")
        .minimum(0)
    nullable("relations", of = list(of = string()))
        .describe("Optional trace relation filter: `direct_call`, `direct_imported_call`, `indirect_call`, or `memory_write`.")
        .maxItems(20)
    int("limit", default = 25)
        .describe("Maximum records to consider before byte budgeting. The implementation clamps values above 100.")
        .minimum(1)
        .maximum(100)
    string("detail", default = "summary")
        .describe("Detail level: `summary` for compact output or `evidence` for extra offsets and identifiers.")
        .maxLength(32)
    int("maxBytes", default = 24_000)
        .describe("Approximate maximum JSON response bytes. The implementation clamps values above 60000.")
        .minimum(2_000)
        .maximum(60_000)
}

private val ArtifactModuleResultClass = class_("ArtifactModuleResult", kotlinPackage = ARTIFACT_MODEL_PACKAGE) {
    string("source").describe("Module role: `current` or `baseline`.")
    int("functions").describe("Function count.")
    int("imports").describe("Total import count across supported import kinds.")
    int("exports").describe("Export count.")
    int("memories").describe("Memory count.")
    int("globals").describe("Global count.")
    int("tables").describe("Table count.")
    int("types").describe("Type count.")
    int("moduleSize").describe("Module size in bytes.")
}

private val ArtifactSignatureResultClass = class_("ArtifactSignatureResult", kotlinPackage = ARTIFACT_MODEL_PACKAGE) {
    list("params", of = string()).describe("Parameter value types.")
    list("results", of = string()).describe("Result value types.")
}

private val ArtifactAttributeResultClass = class_("ArtifactAttributeResult", kotlinPackage = ARTIFACT_MODEL_PACKAGE) {
    string("name").describe("Attribute name.")
    string("value").describe("Attribute value rendered as a compact string.")
}

private val ArtifactRecordResultClass = class_("ArtifactRecordResult", kotlinPackage = ARTIFACT_MODEL_PACKAGE) {
    string("id").describe("Stable record identifier for this query result.")
    string("entityType").describe("Entity type such as `export`, `import`, `memory`, `global`, `function`, `call`, or `instruction`.")
    string("status").describe("Record status, for example `current`, `added`, `removed`, or `changed`.")
    string("source").describe("Module role that owns the record: `current` or `baseline`.")
    string("summary").describe("Compact human-readable summary.")
    nullable("name", of = string()).describe("Entity name when one exists.")
    nullable("kind", of = string()).describe("Entity kind or operation kind.")
    nullable("index", of = int()).describe("Primary numeric index when one exists.")
    nullable("relation", of = string()).describe("Relation name for trace records.")
    nullable("targetId", of = string()).describe("Related target record id for trace records.")
    nullable("signature", of = ArtifactSignatureResultClass).describe("Function signature when applicable.")
    list("attributes", of = ArtifactAttributeResultClass).describe("Bounded key-value evidence for this record.")
}

private val ArtifactOmittedResultClass = class_("ArtifactOmittedResult", kotlinPackage = ARTIFACT_MODEL_PACKAGE) {
    int("count").describe("Number of records omitted by response bounds.")
    string("reason").describe("Why records were omitted.")
}

private val QueryArtifactsResultClass = class_("QueryArtifactsResult", kotlinPackage = ARTIFACT_MODEL_PACKAGE) {
    string("operation").describe("Executed operation.")
    string("detail").describe("Executed detail level.")
    list("modules", of = ArtifactModuleResultClass).describe("Current and optional baseline module summaries.")
    list("records", of = ArtifactRecordResultClass).describe("Bounded query records.")
    nullable("omitted", of = ArtifactOmittedResultClass).describe("Omission summary when the result exceeded `limit` or `maxBytes`.")
    list("warnings", of = string()).describe("Non-fatal query warnings.")
}

val ArtifactQueryTools = tools {
    tool("query_artifacts") {
        summary = "Query WebAssembly artifact entities, diffs, and function relations with bounded output."
        guidance = """
            Use this as the default Hexana MCP entry point instead of low-level list tools.
            `overview` returns module counts, `find` locates matching entities, `diff` compares current and baseline modules, `trace` follows function calls and memory-write relations from a named or indexed function, and `triage` returns the first production-useful summary for public surface, API, memory, or element-table changes.
            Use focused audit operations when the task asks for that production fact: `public_surface` for exported ABI surface, `memory_contract` for memory declarations and baseline limit changes, `dispatch_table_diff` for element-table slot target changes, and `host_call_diff` for direct imported-call regressions from `startName` or `startFuncidx`.
            Keep `limit` and `maxBytes` small, use `entityTypes`, `query`, `startName`, or `startFuncidx` to avoid broad scans, and request `detail=evidence` only for the few records that need offsets or instruction text.
        """
        input = QueryArtifactsInput
        inputPattern = ToolInputPattern.ArtifactQuery
        output = QueryArtifactsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readArtifactQuery"
    }
}
