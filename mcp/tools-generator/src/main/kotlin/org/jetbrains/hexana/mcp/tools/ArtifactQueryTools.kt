package org.jetbrains.hexana.mcp.tools

private const val ARTIFACT_MODEL_PACKAGE = "org.jetbrains.hexana.mcp.model.artifacts"

private val QueryArtifactsInput = WasmInputClass.with("QueryArtifactsInput") {
    nullable("baselinePath", of = string())
        .describe("Optional baseline `.wasm` path used by diff-style operations.")
        .maxLength(4096)
    nullable("bindingPath", of = string())
        .describe("Optional stale binding source file used by `abi_binding_regeneration`; common Python files with MODULE, IMPORTS, and EXPORTS literals are parsed.")
        .maxLength(4096)
    string("operation", default = "overview")
        .describe("Query operation. Default `overview` returns module counts. Prefer focused operations such as `api_diff`, `public_surface`, `abi_surface`, `abi_binding_regeneration`, `import_surface`, `memory_contract`, `global_state_policy`, `data_payload_audit`, `local_budget`, `entrypoint_effects`, `crash_frames`, `dispatch_table_diff`, and `host_call_diff` when they match the requested artifact fact; `api_diff` also reports public type indices and current reference-type import ABI risks. Use generic `find`, `diff`, `trace`, or fallback `triage` only for unmatched facts or missing details.")
        .maxLength(32)
    nullable("entityTypes", of = list(of = string()))
        .describe("Optional entity filter. Supported values include `export`, `import`, `memory`, `global`, `function`, and `element`.")
        .maxItems(20)
    nullable("query", of = list(of = string()))
        .describe("Optional case-insensitive terms used to filter matched entities or trace roots; for `crash_frames`, read the crash report first and pass all numeric stack funcidx values as strings in one call, not the word `funcidx`; for `data_payload_audit`, read the policy first and pass forbidden payload strings exactly as they appear in policy files.")
        .maxItems(20)
    nullable("startName", of = string())
        .describe("Optional function, import, export, or debug-name term used by `operation = trace` or `operation = host_call_diff`.")
        .maxLength(256)
    nullable("startFuncidx", of = int())
        .describe("Optional unified function index used by `operation = trace` or `operation = host_call_diff`.")
        .minimum(0)
    nullable("relations", of = list(of = string()))
        .describe("Optional relation/effect filter: `direct_call`, `direct_imported_call`, `indirect_call`, or `memory_write`. For `entrypoint_effects`, omit it to scan both `direct_imported_call` and `memory_write` in one call.")
        .maxItems(20)
    int("limit", default = 25)
        .describe("Maximum records to consider before byte budgeting. The implementation clamps values above 100.")
        .minimum(1)
        .maximum(100)
    string("detail", default = "summary")
        .describe("Detail level: `summary` for compact output or `evidence` for extra offsets and identifiers.")
        .maxLength(32)
    int("maxBytes", default = 24_000)
        .describe("Approximate maximum JSON response bytes. Start small; the implementation clamps values above 60000.")
        .minimum(1)
        .maximum(60_000)
}

private val PlanAbiBindingRegenerationInput = WasmInputClass.with("PlanAbiBindingRegenerationInput") {
    string("bindingPath")
        .describe("Path to a stale binding source file with MODULE, IMPORTS, and EXPORTS literals.")
        .maxLength(4096)
}

private val ArtifactModuleResultClass = class_("ArtifactModuleResult", kotlinPackage = ARTIFACT_MODEL_PACKAGE) {
    string("source").describe("Module role: `current` or `baseline`.")
    nullable("path", of = string()).describe("Input path label for this module when the caller provided `path` or `baselinePath`.")
    nullable("artifactPath", of = string()).describe("Canonical artifact path echoed from `path` or `baselinePath` for report-ready module identity.")
    nullable("artifactName", of = string()).describe("Display basename derived from `artifactPath`; use `artifactPath` when paths can collide.")
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
    nullable("artifactPath", of = string()).describe("Canonical artifact path that owns this record; prefer it over display names or basenames in reports.")
    nullable("artifactName", of = string()).describe("Display basename for the owning artifact; use `artifactPath` when paths can collide.")
    nullable("locationKind", of = string()).describe("Structured location kind, for example `import`, when the record points at a specific ABI location.")
    nullable("module", of = string()).describe("Structured module name for import or module-scoped ABI records.")
    nullable("typeidx", of = int()).describe("Structured type index when the record points at a WebAssembly function type.")
    nullable("locationDisplay", of = string()).describe("Stable human-readable location string derived from the structured location fields.")
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

private val AbiBindingSignatureClass = class_("AbiBindingSignature", kotlinPackage = ARTIFACT_MODEL_PACKAGE) {
    list("params", of = string()).describe("Parameter value types.")
    list("results", of = string()).describe("Result value types.")
}

private val AbiBindingModuleIdentityClass = class_("AbiBindingModuleIdentity", kotlinPackage = ARTIFACT_MODEL_PACKAGE) {
    nullable("path", of = string()).describe("Exact current WebAssembly module path echoed from input `path`, when available.")
    nullable("bindingPath", of = string()).describe("Exact stale binding file path echoed from input `bindingPath`, when available.")
    nullable("staleModule", of = string()).describe("MODULE value parsed from the stale binding file.")
    string("replacementModule").describe("MODULE value used in regenerated bindings.")
}

private val AbiBindingMismatchResultClass = class_("AbiBindingMismatchResult", kotlinPackage = ARTIFACT_MODEL_PACKAGE) {
    string("symbol").describe("Binding symbol prefixed with `IMPORTS.` or `EXPORTS.`.")
    nullable("expected", of = AbiBindingSignatureClass)
        .describe("Current WebAssembly signature, or null when a stale binding should be removed.")
    nullable("actual", of = AbiBindingSignatureClass)
        .describe("Stale binding signature, or null when the binding is missing.")
}

private val AbiReplacementBindingsResultClass = class_(
    "AbiReplacementBindingsResult",
    kotlinPackage = ARTIFACT_MODEL_PACKAGE,
) {
    string("module").serialName("MODULE").describe("MODULE value for regenerated bindings.")
    map("imports", of = AbiBindingSignatureClass).serialName("IMPORTS")
        .describe("Regenerated IMPORTS signature map keyed by qualified import name.")
    map("exports", of = AbiBindingSignatureClass).serialName("EXPORTS")
        .describe("Regenerated EXPORTS signature map keyed by export name.")
}

private val PlanAbiBindingRegenerationResultClass = class_(
    "PlanAbiBindingRegenerationResult",
    kotlinPackage = ARTIFACT_MODEL_PACKAGE,
) {
    embed("module", of = AbiBindingModuleIdentityClass).describe("Module and binding file identity.")
    map("imports", of = AbiBindingSignatureClass)
        .describe("Current WebAssembly function import signature map keyed by qualified import name in stable sorted order.")
    map("exports", of = AbiBindingSignatureClass)
        .describe("Current WebAssembly function export signature map keyed by export name in stable sorted order.")
    map("staleImports", of = AbiBindingSignatureClass)
        .describe("Stale binding IMPORTS signature map parsed from `bindingPath` in stable sorted order.")
    map("staleExports", of = AbiBindingSignatureClass)
        .describe("Stale binding EXPORTS signature map parsed from `bindingPath` in stable sorted order.")
    list("bindingMismatches", of = AbiBindingMismatchResultClass)
        .describe("Binding mismatches in stable symbol order using `expected` for current WebAssembly and `actual` for stale bindings.")
    embed("replacementBindings", of = AbiReplacementBindingsResultClass)
        .describe("Drop-in replacement binding maps derived from the current WebAssembly ABI.")
    list("warnings", of = string()).describe("Non-fatal binding parse warnings.")
}

val ArtifactQueryTools = tools {
    tool("plan_abi_binding_regeneration") {
        summary = "Plan report-ready ABI binding regeneration from current Wasm plus a stale binding file."
        surfaces = publicMcpbProductionCliSurface("plan-abi-binding-regeneration", ToolGroup.ArtifactQuery, ToolOutputPolicy.ToolDefined)
        guidance = """
            Use this as the shortest path for regenerating host bindings when a current module and stale `bindingPath` are available.
            Compare the current WebAssembly function import/export ABI against a stale binding file and return a report-ready regeneration plan with exact input path echoes.
            The result includes stable current import and export signature maps, stale binding maps, binding mismatches, and ready-to-copy replacement bindings.
            Mismatch `expected` values are current WebAssembly signatures, or null when a stale binding should be removed; `actual` values are stale binding signatures, or null when the binding is missing.
        """
        input = PlanAbiBindingRegenerationInput
        inputPattern = ToolInputPattern.WasmWithBinding
        output = PlanAbiBindingRegenerationResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.planAbiBindingRegeneration"
    }

    tool("query_artifacts") {
        summary = "Query WebAssembly artifact entities, diffs, and function relations with bounded output."
        surfaces = advancedHiddenCliSurface()
        guidance = """
            Use this as the default Hexana MCP entry point for WebAssembly artifact facts.
            `overview` returns module counts; skip it when the request already names a focused production fact.
            Prefer dedicated focused tools before this broad query when their inputs are available: `plan_abi_binding_regeneration` for stale binding regeneration, `check_memory_contract` for baseline-plus-policy memory audits, and `audit_global_state_policy` for baseline-plus-policy global-state audits.
            Choose a focused operation first when the requested artifact fact matches one: `api_diff` for old/new public function ABI diffs, changed signatures, public type indices, and current reference-type import ABI risks; `public_surface` for single-module exported ABI surface; `abi_surface` for compact function imports plus exports in one module; `abi_binding_regeneration` for current Wasm import/export signature maps, stale Python binding maps from `bindingPath`, mismatch JSON, and `replacementBindingsJson`; `import_surface` for host manifest/link checks, including compact `functionImportsJson` and exported function candidates on the summary; `memory_contract` for memory declarations and baseline limit changes; `global_state_policy` for mutable imported globals and new exported globals, including `targetedGlobalIndices`, `mutableImportedGlobalsJson`, `newPublicGlobalsJson`, and allowed public global exports on the summary; `data_payload_audit` for raw data segment payload string confirmation after reading policy strings; `local_budget` for exported-function declared local counts; `entrypoint_effects` for exported local functions whose own body directly writes memory or directly calls host imports, including policy-ready direct-host-entry JSON and transitive-host exclusions; `crash_frames` for crash reports after reading all numeric stack funcidx values into `query`; `dispatch_table_diff` for element-table slot target changes; and `host_call_diff` for direct imported-call regressions from `startName` or `startFuncidx`.
            For `data_payload_audit`, read the policy first and pass all forbidden strings in one call; an empty `query` only returns a small reminder. For `crash_frames`, read the crash report first and pass all stack funcidx values in one call; avoid probing a single frame and then repeating.
            For `abi_binding_regeneration`, pass the stale binding file as `bindingPath` when one exists. Its mismatch records and `bindingMismatchesJson` use `expected` for the current Wasm signature, or null when a stale binding should be removed; `actual` is the stale binding signature, or null when the binding is missing.
            Use generic `find`, `diff`, or `trace` only when no focused operation matches, or when a focused response is missing a required output field. Use `triage` only for ambiguous requests where no production fact is named.
            Stop after a focused operation when its response contains the required names, signatures, type indices, `functionImportsJson`, `functionExportsJson`, `replacementBindingsJson`, `bindingMismatchesJson`, memory limits, `targetedGlobalIndices`, `newPublicGlobalsJson`, `mutableImportedGlobalsJson`, matched data segments, local counts, entrypoint direct memory writes, `directHostEntryPointsJson`, `excludedTransitiveExportsJson`, frame metadata, table slots, or call relations. Do not follow `api_diff` with generic `find` or `diff`, `abi_surface`, `abi_binding_regeneration`, or `import_surface` with generic `find`, `global_state_policy` with generic `list_globals` or `list_exports`, `data_payload_audit` with generic `find`, `local_budget` with per-function `trace`, `entrypoint_effects` with per-export `trace`, `host_call_diff` with generic `trace`, or `crash_frames` with per-frame `trace`, unless a required output field is absent.
            If an output field asks for a module path, use the exact artifact path or the `modules[].path` value echoed from input `path`/`baselinePath`; do not write `local`, `current`, or `baseline` into path fields. For binding regeneration replacement objects, use `replacementBindingsJson` directly.
            Keep `limit` and `maxBytes` small; for `abi_surface`, `abi_binding_regeneration`, or `import_surface`, use `limit=1` when summary JSON arrays are sufficient. Use `entityTypes`, `query`, `startName`, or `startFuncidx` to avoid broad scans, and request `detail=evidence` only for the few records that need offsets, locals, element lists, or instruction text.
        """
        input = QueryArtifactsInput
        inputPattern = ToolInputPattern.ArtifactQuery
        output = QueryArtifactsResultClass
        handlerFqn = "org.jetbrains.hexana.mcp.query.readArtifactQuery"
    }
}
