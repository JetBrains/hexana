# Wasm runtime compatibility table

Warns when a Wasm module uses a spec proposal that a runtime the project targets cannot run,
and shows the full matrix with documentation links. Data comes from
[[sources/webassembly-features-json]]; targets come from a project file both editors share.

## Data flow

```
features.json (remote)                          plugins-shared/features/features.json
   │  conditional GET, ETag, TTL 24h                 (committed snapshot, offline floor)
   ├── idea-plugin  WasmFeatureCatalogService ──┐    → gen/BundledWasmFeatures.kt
   └── vscode-plugin src/wasmFeatureCatalog.ts ─┤
                                                ▼
                          plugins-shared: WasmFeatureCatalog.parse
                                                │
   .hexana/runtimes.json ──► RuntimeTargets ─────┤   (watched: VFS listener / FileSystemWatcher)
                                                ▼
                    WasmRuntimeCompatibility.evaluate(usedProposals)
                              │                          │
                    TableTabsContext                 badge warning state
                              ▼                          ▼
              "Compatibility" tab              ProposalBadges (IJ + web-ui)
```

Everything host-neutral is in `plugins-shared/src/commonMain/.../shared/features/`. Each host
owns only network I/O, file I/O, and its settings UI.

## Decisions worth remembering

- **`.hexana/runtimes.json`, not editor settings.** IntelliJ `@State` storage lands in
  `.idea/`, which VS Code cannot read; VS Code's `contributes.configuration` is per-user and
  invisible to IntelliJ. A project file is the only place both can meet. It is committed to
  version control by default (`.hexana` is not gitignored), which is the intent: a team
  declares its deployment targets once. Consequently parsing is lenient and serialization is
  byte-stable — see the rules in `plugins-shared/AGENTS.md`.
- **Runtime keys are upstream's own** (`"Chrome"`, `"Wasmtime"`, `"Node.js"`), so there is no
  second naming vocabulary to keep in sync. Note this is a *different* vocabulary from the
  run-configuration runtime ids in `WasmRuntimeResolver` (`"wasmtime"`, `"wamr"`,
  `"graalwasm"`, `"nodejs"`, …), which name executables rather than support profiles.
- **Conditional GET, not HEAD.** The request asked for HEAD probing; `If-None-Match` achieves
  the same "don't re-download unchanged data" in one round-trip instead of two.
- **`Unknown` never warns.** Upstream leaves many cells `null`; warning on them would put a
  marker on nearly every module. Only `Unsupported` and `FlagRequired` do. A row that is
  *nothing but* `Unknown` is not shown as a row of `?` either: it collapses to a single
  `No data` label and sorts below the rows that carry a verdict
  (`ProposalCompatibility.hasNoData`). No cell spanning exists in `TableTabDescriptor`, so
  the collapse is the first runtime column carrying the label and the rest rendering empty.
  The trailing `Other runtimes` cell is left alone — the declared targets can be silent
  while other runtimes still support the proposal, which is exactly what a reader then
  wants.
- **Tools are not compatibility targets.** Upstream keeps assemblers, optimizers and
  language servers (`Binaryen`, `watr`, `wasm-language-tools`, and Hexana's own entry) in
  the same `browsers` object as the browsers, distinguished only by `category: "Tools"`.
  "Does Binaryen support GC" is not a question about where a module runs, so
  `WasmFeatureCatalog.executionRuntimes` drops them and both the supporting-runtimes
  columns and the settings UI's runtime list read that instead of `runtimes`. Matched on the
  category's `queryKey` (`"tools"`), with the display name as the fallback for a catalog that
  omits the `categories` array. `runtimes` itself stays complete, so a target already
  declared against a tool keeps resolving to real data instead of turning into `?`. An entry
  filed under Tools *and* a runtime category (`Owi`) is kept and grouped where it runs.
- **VS Code fetches host-side.** The webview CSP would block
  `raw.githubusercontent.com`, and fetching in the extension host means no new network origin
  is exposed to webview content at all.
- **The snapshot is a Kotlin constant, not a resource.** `wasmJs` has no convenient resource
  loader; a generated `const` reads identically on every KMP target.
- **The browser webapp is deliberately excluded.** It has no project directory, so
  `Host.canEditRuntimeTargets` is false there and the badges stay purely informational.

## Proposal detection

`WasmProposal` (in `wasmParser`) carries a `featureId` matching the upstream key, pinned in
both directions by `WasmFeatureCatalogTest`. 22 entries; six were added for this feature,
all detectable from data the parser already had or nearly had:

| Proposal | Signal |
|---|---|
| `ExtendedConst` | a non-MVP opcode in a global/table/data/element initializer |
| `BranchHinting` | the `metadata.code.branch_hint` custom section, by presence only |
| `JsStringBuiltins` | an import from `wasm:js-string` (or `wasm:text-encoder`/`-decoder`) |
| `ExtendedNameSection` | a name subsection with id ≥ 3 (labels, globals, tags, fields, …) |
| `CustomDescriptors` | a `WasmExactHeapType` in a type record or a code local |
| `CustomPageSizes` | limits flag bit 3; `page_size_log2` was previously read and discarded |

**Detectability is not sufficient reason to detect.** `componentModel` was implemented and
then removed: a component binary trivially *is* one, but `features.json` records support for
it against only two entries, so its table row read `?` in every column a project would
target — and its badge repeated the "Component Model" file-type label sitting next to it. The
test to apply to a candidate proposal is whether the upstream matrix has enough data for the
row to say anything.

Other proposals not attempted, with reasons recorded in `WasmProposal`'s KDoc: `compactImportSection`,
`typeImports`, `sharedEverythingThreads` hit the parsers' `error("unknown …")` paths;
`stackSwitching`, `wideArithmetic`, `halfPrecision`, `stringref` need opcode families the
parser does not decode (their WAT tokens *do* exist in `idea-plugin/.../wat/psi/WatToken.kt`,
which is a useful spec reference if they are ever added); `bigInt`, `jspi`, `esmIntegration`,
`customAnnotationSyntaxInTheTextFormat` have no binary encoding at all.

## Traps

- **The webapp worker allow-list.** `ParserWorkerPresentationProtocol` used to hardcode a
  copy of the 16 proposal names; an id outside it fails `isValidToolbar`, which discards the
  *entire* presentation, not just the badge. It is now derived from `WasmProposal.entries`
  so it cannot drift. `PARSER_WORKER_PROTOCOL_VERSION` went 9 → 10 with that change.
- **`TableTabsProvider.tableTabs(provider, context)` defaults to dropping the context.** A
  provider that overrides only the context-free `tableTabs` never sees `TableTabsContext`, so
  it cannot read anything a host supplies there. The default `singleTab` resolves through the
  same overload, so a provider needing context must override that variant.
- **`ProposalBadges` exists twice** — `idea-plugin/.../ui/FileDescriptionBar.kt` and
  `web-ui/.../vscode/EditorToolbar.kt`. Changing one silently diverges the two products.
- **The web UI's row-action column used to be nested inside the ID column**, so a tab with
  `showIdColumn = false` silently lost its row action. Decoupled in
  `web-ui/.../TableTabBridge.kt` (`showAction` alongside `showId`, and a `headerOffset` that
  counts them separately); the IntelliJ table always kept them independent.
- **`WasmLimits` has hand-written `equals`/`hashCode`**, and `WasmFixedI32Limits` overrides
  them again. A new field must be threaded through both.
- **`vscode-plugin/media/editor.css` is the only dialog stylesheet**; the webapp copies it via
  `webapp/build.gradle.kts`, so one edit covers both products.
- **A save cannot rely on its own VFS event to refresh the UI.** By the time it arrives the
  cache already matches disk, so the "did anything change" check sees nothing and applying the
  settings page would never move the badge. `RuntimeTargetsTracker.recordSave` announces
  directly; the event that follows is then correctly a no-op.
- **Only the Compatibility tab is re-resolved on a targets change**, not every panel. Whether
  the tab exists depends on the project having a base path and the module using proposals —
  both fixed for an editor — so only its columns change, and a full re-resolve would re-run
  the Size/Garbage/Functions analyses for nothing.
- **The web UI's row-action column used to be nested inside the ID column**, so a tab with
  `showIdColumn = false` silently lost its row action. Decoupled in
  `web-ui/.../TableTabBridge.kt` (`showAction` alongside `showId`, and a `headerOffset` that
  counts them separately); the IntelliJ table always kept them independent.

## Key files

- `plugins-shared/.../features/` — `WasmFeatureCatalog`, `WasmFeatureVersions`,
  `RuntimeTargets`, `FeatureCatalogRefresh`, `WasmRuntimeCompatibility`
- `plugins-shared/.../shared/TabDescriptor.kt` — `prologueActions: List<PrologueActionDescriptor>`
  lets a tab's prologue carry clickable links. The Compatibility tab offers two: the host's
  runtime settings, instead of naming `.hexana/runtimes.json` in the prose (falling back to
  naming the path when the host has no settings page), and `webassembly.org/features` as the
  source of every verdict in the table — a reader who finds one surprising has to be able to
  check it, and the page is more use than the JSON the plugin downloads
  (`WASM_FEATURES_PAGE_URL`, distinct from `WASM_FEATURES_URL`). A link's label carries the
  whole meaning because neither host can put a link inside a paragraph. IntelliJ renders it as a Jewel `Link` on its
  own line — Compose `Text` cannot hold an inline link without an `AnnotatedString`, and a
  `Row` would stop the paragraph wrapping — and the web UI inline at the end of the
  paragraph. Also `RowActionKind` (`Run` / `Documentation`)
  lets a row action say what it does without naming an icon; IntelliJ maps it to an
  `AllIcons` key (`Toolwindows.Documentation`) and the web UI to a glyph (`↗`, link-coloured,
  because the webview loads no icon font). `Run` is the default, which is what every action
  was before kinds existed.
- `plugins-shared/.../shared/WasmCompatibilityTableTab.kt` — the tab, registered in
  `WasmTableTabs.kt` and injected through `TableTabsContext`. The supporting-runtimes column
  has two forms, because its role changes with the configuration:
  - nothing declared → a **leading** `Supported in` column over the whole catalog. This is
    what makes the tab worth opening before anything is configured, since there are no
    per-runtime columns to read instead. Layout: `Proposal | Phase | Supported in`.
  - targets declared → their own columns lead and a **trailing** `Other runtimes` column
    lists the supporting runtimes *without* a column, so the two never repeat. Layout:
    `Proposal | Phase | <each target> | Other runtimes`.

  Both read `Firefox 120+, Wasmtime 47+` — bare name when upstream records support without a
  version, `(flag)` when flag-gated, omitted when unsupported, em dash when the list is
  empty. `ProposalCompatibility.supportedIn` is always every runtime in
  `executionRuntimes` (i.e. the catalog minus its tools); `otherRuntimes` is that minus the
  declared ids. The column sorts on how many runtimes are listed rather than
  on the text, so ascending puts the narrowly supported proposals first.
- `idea-plugin/.../features/` — `WasmFeatureCatalogService` (+`Store`),
  `RuntimeTargetsService` (+`Store`), `WasmRuntimeCompatibilityConfigurable`
  (the repo's first `projectConfigurable`). `RuntimeTargetsTracker` holds the
  change-announcement policy as a pure object, because a `BasePlatformTestCase`
  fixture's `project.basePath` is not a real directory and cannot be written to —
  the VFS wiring stays in the service, untested, and the judgement is unit-tested.
  `runtimeTargetsEventMatches` matches the settings file's exact path or an ancestor
  directory (a checkout reports `.hexana/` itself), never any file merely named
  `runtimes.json`.
- The scripting `.d.ts` files (`idea-plugin/src/main/resources/hexana-scripting-raw.d.ts`,
  `vscode-plugin/hexana-scripting-raw.d.ts`) spell `usedProposals` out as a union of the
  enum names, so adding or removing a `WasmProposal` means rerunning
  `./gradlew :mcp:scripting:generateHexanaScriptingDts`.
- `vscode-plugin/src/wasmFeatureCatalog.ts`, and `save-runtime-targets` /
  `runtime-targets-loaded` / `feature-catalog-loaded` /
  `open-runtime-targets-dialog` in `WasmEditorProvider.ts`
- `web-ui/.../vscode/RuntimeTargetsDialog.kt` — the VS Code settings UI
