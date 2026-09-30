# Binary inspections

One model for "what is wrong with this binary", rendered by every host: the Problems tab
(IntelliJ, VS Code, webapp worker), the toolbar count badge (IntelliJ and VS Code), and the
`hexana-cli inspect-binary` command / `inspect_binary` standalone MCP tool.

## Data flow

```
BinaryProvider ──► BinaryInspectionRegistry.inspections ──┐
                     WasmParseProblemsInspection          │   inspectBinary(provider, InspectionContext)
                     NativeParseDiagnosticsInspection     ├──► List<BinaryProblem>  (severity, then offset)
                     WasmRuntimeCompatibilityInspection   │
host-only problems (IntelliJ script checks) ──────────────┘
        │
        ├── BinaryProblemsTableTabsProvider ──► "Problems" tab   (navigation: TableTabsContext.problemNavigator)
        ├── ProblemsBadge (FileDescriptionBar.kt / EditorToolbar.kt) ──► "2 errors, 1 warning"
        ├── webapp ParserWorkerMain (Wasm route) ──► Problems table + worker diagnostics
        └── mcp/core readBinaryInspections ──► InspectBinaryResult ──► CLI pretty / table / json / sarif
```

Everything host-neutral is in `plugins-shared/.../shared/inspections/` and
`shared/ProblemsTableTab.kt`.

## What was migrated, and from where

| Before | Now |
|---|---|
| Wasm `ParseProblem` → a Wasm/component-only Problems tab in `WasmTableTabs.kt` / `WasmComponentTableTabs.kt` | `WasmParseProblemsInspection` (`wasm.parse`) |
| ELF/Mach-O/PE `NativeParserDiagnostic`s — computed by the whole-file parsers, read by nothing but `binary.raw` | `NativeParseDiagnosticsInspection` (`native.parse`), via `NativeBinaryProvider.parseDiagnostics` |
| Runtime-compatibility warning state — only the yellow proposal badge | `WasmRuntimeCompatibilityInspection` (`wasm.runtime-compatibility`); the problems badge is the one icon and count; since 2026-09-30 an affected proposal badge (or the collapsed one) is warning-*coloured* with no icon, its tooltip puts ⚠ on the proposal and the failing runtimes, and the failing runtime's cell in the Compatibility tab is tinted (`CellValue.tint = RowTint.Warning`) |
| Script `CheckFinding`s appended to the Wasm Problems tab by an IntelliJ enricher | `CheckFinding.toBinaryProblem()` → `hostProblems` |

The IntelliJ and web-ui Problems enrichers (`enrichWasmProblemsTab`, `enrichProblemsTab`) and
`FindingProblemsRow` are gone; navigation is a `ProblemNavigator` each host puts in its
`TableTabsContext`.

## Decisions worth remembering

- **Severity of Wasm parse problems.** Truncated sections and corrupt entries are errors — the
  binary format is violated. Unknown opcodes, type bytes and instance tags are warnings: they are
  usually a proposal Hexana does not decode yet, which a runtime may accept.
- **Unresolved native records are not reported.** The parsers' `knownTypes` sets list the
  records Hexana *decodes*, not the ones the format defines, so `LC_BUILD_VERSION`, `LC_MAIN`,
  `.gnu.hash`, `PT_GNU_STACK` all come back "unknown" — a false note on every real binary. Found
  by running the CLI over `parsing/native-fixtures/test-data`. Revisit only if the known sets
  become the format's full vocabulary.
- **Runtime incompatibility is a warning**, as the proposals badge showed it before, so `inspect-binary` exits 0 on
  it. Only errors make it exit 1 (the `run-script` convention for ERROR findings).
- **CLI verdicts come from the bundled catalog snapshot** (`WasmFeatureCatalog.bundled`), never
  the network, so a CI run is reproducible. Targets come from `--runtimes <runtimes.json>`; with
  none, the inspection is skipped and the pretty output says so.
- **`inspect_binary` is in the standalone *advanced* MCP profile**, not default. The default
  profile is also the IDEA toolset, and its `BinaryOnly` input pattern cannot carry
  `runtimeTargetsPath`; putting it there would have silently dropped the argument in IntelliJ.
- **The badge hides notes.** Info-level problems appear in the tab only.
- **A runtime-compatibility row opens the Compatibility tab**, not the hex view: it has no
  byte range. `BinaryProblem.relatedTabName` names the tab, and a host that sets
  `ProblemNavigator.selectTab` gets both the row click and a "Show in Compatibility tab" menu
  item; a host that does not keeps calling `open`.
- **VS Code has no script findings to merge**: `defineCheck` needs the GraalJS runtime, which the
  webview does not have. `hostProblems` is how a host that does run them adds them.
- **The webapp worker route shows no badge** (`onOpenProblemsTab = null` when a worker
  presentation is present): counting would parse on the UI thread the worker exists to spare.

## Traps

- **`--runtimes` is hand-parsed** in `cli/product/.../HexanaProductCli.kt`, like `--script`:
  the generated `HexanaCliInputOptionSpec` lists option names but not their target fields. It
  is gated by `requireDeclaredOption`, so it is rejected on commands whose metadata does not
  declare it, and it is added to `--help` by hand.
- **The Problems tab can appear after the editor opened.** A declared runtime or a script run
  can create problems for a clean module; IntelliJ's `refreshTab` in `BinaryEditorView.kt`
  re-resolves it on `RuntimeTargetsService.changes`, `WasmFeatureCatalogService.changes` and
  `ScriptFindingsStore.changes`, and `replacePlaceholder` inserts it when absent. Before this,
  script findings on a module with no parse problems were never shown at all, and a finished
  run did not refresh the tab.
- **Problem ranges are untrusted.** A script can anchor a finding anywhere (negative, past EOF,
  zero-length, above `Int.MAX_VALUE`). The data keeps what was reported (CLI/SARIF show it as
  is); every host navigates through `ProblemByteRange.selectionIn(fileSize)`, which clips to the
  file, widens a point to one byte, and returns `null` outside it. Never `toInt()` a range for
  selection directly.
- **SARIF fingerprints must be unique within a run.** The base hash (code, range, funcidx,
  location) collides for position-less problems such as two proposal warnings; repeats get
  `:<occurrence>` appended. The SARIF `tool.driver.version` comes from
  `StandardCliRequest.toolVersion` (`HEXANA_CLI_VERSION` in `hexana-cli`).
- **`ScriptFindingsStore.findingsFor(path)` compares with `FileUtil.pathsEqual`.** The editor
  passes a NIO path (`\` on Windows); `VirtualFile.path` always uses `/`.
- **The VS Code badge is counted in `BinaryEditorContent`, not the toolbar**, from
  `liveProvider` in a `LaunchedEffect`: the toolbar's `provider` is the one the file opened
  with, which a reparse after an edit replaces, and inspecting during composition blocks the
  first frame.
- **Approval pins.** Adding an inspection tool touched `HexanaToolsTest` inventories,
  `HexanaToolCompatibilityTest` fingerprints, `OutputSchemaConformanceTest`'s serializer list,
  `HexanaMcpServerSchemaTest` profile counts, and the fake executors in `HexanaProductCliTest`.

## Key files

- `plugins-shared/.../shared/inspections/` — `BinaryProblem.kt`, `BinaryInspection.kt`
  (registry, `inspectBinary`, `mayHaveProblems`), the three inspections, `ScriptCheckProblems.kt`
- `plugins-shared/.../shared/ProblemsTableTab.kt` — tab provider, `ProblemNavigator`
- `idea-plugin/.../ui/panel/BinaryProblemsSupport.kt` — navigator, `ideInspectionContext`
- `idea-plugin/.../ui/FileDescriptionBar.kt`, `web-ui/.../vscode/EditorToolbar.kt` — `ProblemsBadge`
- `mcp/core/.../query/BinaryInspectionQuery.kt`, `model/inspections/InspectBinaryModels.kt`
- `cli/core/.../InspectBinaryCliRuntime.kt` — pretty / table / SARIF rendering, exit code
