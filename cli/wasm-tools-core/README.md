# Component WIT report workflow

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
