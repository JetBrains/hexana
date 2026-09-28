# Wasm tools workflow core

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
