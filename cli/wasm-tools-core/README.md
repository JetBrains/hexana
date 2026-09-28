# Wasm tools workflow core

This module owns the versioned report envelope and neutral workflow composers. It accepts inputs that an eventual CLI frontend has already selected and bounded; it does not resolve paths, scan directories, or register commands.

## Component WIT report

`ComponentWitReport.compose` builds the `component-wit-report` body for the
shared `WasmToolsReportRenderer`. The caller supplies one already selected and
bounded `WasmInput`, its report input description, and optional dependency paths.
The installed CLI command and option parsing belong to later product slices.

The composer obtains component surface, layer, and flat type graph through
`HexanaAnalysisService.componentWitStructure`. A valid core module yields an
empty component surface and `not_applicable` type and dependency stages.
Malformed input is refused by the service instead of being described as a core
module. The WIT object always has `status: not_available`: binary structure
does not establish source WIT, package, or world names.

With no dependency paths, the stage is `not_requested` and compatibility is
never called. Supplied paths are normalized and passed only to
`checkComponentCompatibilityExplicit`. Every path receives an accepted or
rejected outcome in `compatibility.accepted_providers` or
`compatibility.rejected_dependencies`. Paths and outcomes are trimmed together;
rejections and compatibility issues produce findings.
Directory scan count must be zero. No project root or discovery option is
available through this workflow.

The report renderer applies `max_items` and the default 60,000 byte output
limit. Each type item and all its graph nodes form one removable unit, so
trimming cannot leave dangling node links. Tests cover stage applicability,
explicit outcomes, no legacy calls, type graph order, truncation, and the WIT
boundary.

## Validate and explain

`validateAndExplain` accepts bytes from an already selected path or stdin source.
It composes parser-backed structural evidence from `mcp:core` into the versioned
`validate-and-explain` report and passes that report to `WasmToolsReportRenderer`.
The caller owns source selection, byte limits, product option parsing, and
output writing.

The workflow reports `semantic_validation: "not_performed"` for every identified
core module and component. Structural parse success is never treated as a
WebAssembly validation verdict. Unidentified headers return an input refusal
without a report. Identified partial inputs retain section scan, decode, and
unresolved-section diagnostics and exit 1 through the shared envelope. The
`structural_parse` object keeps the original `status`, `section_scan_failures`,
and `parse_problems` fields. Unresolved sections contribute to diagnostics and
`summary.unresolved_section_count` without adding a workflow field.

Enabled features, required proposals, and runtime compatibility use only the
core-provider services. Components mark those stages `not_applicable` and do
not inspect supplied runtime targets. A core module without targets reports
`not_requested`; requested targets are evaluated in caller order, then sorted
by runtime name in the report. On a partial core parse, positive feature and
proposal evidence remains visible with a `completed` extraction stage and an
explicit report limitation about its possible incompleteness. Requested runtime
targets remain listed with a `not_applicable` stage and no compatibility verdict
because incomplete evidence cannot establish compatibility. A report limitation
states why evaluation was withheld. Compatibility for clean parses is the
existing conservative proposal matrix, not a deployment execution guarantee.

If malformed section contents also prevent core feature extraction, the partial
structural report remains available. Feature fields are null or empty and their
stage is `not_applicable`; a diagnostic and limitation explain why extraction
was unavailable. This is a missing-evidence result, not a workflow failure.

Run `./gradlew :mcp:core:test :cli:wasm-tools-core:test` for this workflow.

## Metadata audit workflow

`MetadataAuditWorkflow.run` uses `mcp:core` to parse one core module or component, reuse the 96-byte custom-section preview, decode bounded `producers` and `target_features` occurrences, and obtain core-only enabled-feature evidence. The report preserves encoded occurrence, field, and value order. It materializes at most 500 custom-section inventory items before rendering; `max_items` may lower that limit. Each custom section and its typed occurrence form one renderer item, so report limits remove them together. Within retained inventory, typed decoding stops after 60,000 payload bytes per metadata type. The affected typed status becomes `partial` when an occurrence is skipped by either limit, with a resource-limit diagnostic and finding. `truncation` records omitted inventory items and section-scan diagnostics.

For a recognized core module with malformed non-custom sections, the workflow reports provider parse problems as `section_decode` diagnostics. If feature extraction fails, `enabled_features.stage` becomes `partial` with null feature values and a diagnostic. Unresolved sections also make the report partial. Provider parse problems are capped at 1,000; reaching the cap adds a resource-limit diagnostic and a limitation because further omissions cannot be counted exactly. The report exits 1 when partial. The `enabled_features.stage` value `completed` means the core detector ran; report completeness carries the separate evidence-quality status required by the version 1 schema.

The dependency boundary permits `mcp:core` and its parser/provider dependencies, while excluding product, transport, server, and IDE layers. The accepted MCP-25 report schema remains the contract. Run `./gradlew :mcp:core:test :cli:wasm-tools-core:test` after changes to the workflow.
