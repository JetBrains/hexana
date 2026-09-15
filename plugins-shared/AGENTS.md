# plugins-shared Module

Read the root `AGENTS.md` first, then this file.

## What This Module Contains

Shared, pure-Kotlin utility logic used by both `idea-plugin` and `vscode-plugin:webview`.  
This module has **no UI framework dependency** — no Compose Desktop, no Compose HTML, no IntelliJ Platform APIs.

The module provides:

- **Component description formatting** (`ComponentDescriptionFormat.kt`) — string representations for component sort indices and extern descriptions.
- **Wasm type formatting** (`WasmTypeFormat.kt`) — value type names, export/import kind labels, signature resolution and formatting.
- **Component resolve utilities** (`ComponentResolveUtil.kt`) — WASI CLI run detection, component function export detection, invoke name resolution, component type utilities.
- **WAT text generation** (`WatTextGenerator.kt`) — structural WAT-like text output from parsed core Wasm and component-model binaries.
- **Wasm run utilities** (`WasmRunUtil.kt`) — runtime import detection, component import/export name extraction, binary type detection, version-name splitting.
- **Wasm dependency resolver** (`WasmDependencyResolver.kt`) — platform-independent filesystem-based component dependency resolution with `WasmFileSystem` abstraction; used by both `idea-plugin` (JVM `java.io.File` adapter) and `vscode-plugin:extension-lib` (Node.js adapter).
- **Wasm runtime resolver** (`WasmRuntimeResolver.kt`) — platform-agnostic runtime executable resolution (maps runtime identifiers to executable paths) and unified runtime command-line dispatcher; used by both `idea-plugin` and `vscode-plugin:extension-lib` to avoid duplicating the runtime → executable mapping logic.
- **Wasm component composer** (`WasmComponentComposer.kt`) — platform-agnostic component composition orchestration using `wasm-tools compose` / `wac plug` with iterative multi-pass support; defines `ProcessRunner`, `ToolResolver`, and `ComposerFileAccess` abstractions that each platform implements. Used by both `idea-plugin` (JVM `ProcessBuilder` adapter) and `vscode-plugin:extension-lib` (Node.js `child_process` adapter).
- **Import stub generation** (`WasmImportStubGenerator.kt`, `WasmBinaryWriter.kt`) — generates stub Wasm modules for missing imports.
- **Table tab descriptors** (`TabDescriptor.kt`) — UI-free `TableTabDescriptor`, `ColumnDescriptor`, `CellValue`, `ColumnWidth`, `RowActionDescriptor`, `RowContextMenuItemDescriptor`, and `TableTabsProvider` abstraction for table-based editor tabs; consumed by both `idea-plugin` and `vscode-plugin` to render Data, Exports, Imports, Functions, Custom, and Modules tabs without duplicating format-specific logic. The descriptors carry optional action hooks (per-row action buttons, context menu items, custom row click handlers) that the consuming platform attaches via an enrichment step before rendering.
- **Wasm table tabs** (`WasmTableTabs.kt`, `WasmComponentTableTabs.kt`) — `TableTabsProvider` implementations for core Wasm and component-model Wasm binaries.
- **Native table tabs** (`NativeTableTabs.kt`, `NativeTableTabFormatting.kt`) — `TableTabsProvider` implementation and shared formatting helpers for ELF (Headers/Sections/Segments/Symbols/Functions/Relocations/Imports/Exports/Strings), Mach-O (Headers/Load Commands/Segments/Sections/Symbols/Dynamic Symbols/Dylibs/Relocations/Strings), Fat Mach-O (Universal/Slices), PE (Headers/Sections/Imports/Exports/Relocations/Debug/Symbols/Strings), COFF (Symbols/Strings), and archive (Strings) structure tabs.
- **LLDB output parsing** (`LldbOutputParser.kt`) — pure parsers for LLDB stop events, expression results, frame locations, function names, and WAMR debug support detection; used by both `idea-plugin` and `vscode-plugin:extension-lib`.
- **DWARF path matching** (`DwarfPathMatcher.kt`) — platform-agnostic utilities for matching DWARF debug-info file paths against IDE/editor breakpoint paths, compilation-unit path heuristics, crate name extraction, and CU hash suffix stripping; used by both `idea-plugin` and `vscode-plugin:extension-lib`.
- **DWARF outline** (`DwarfOutline.kt`) — the row model behind the `.debug_abbrev`, `.debug_info` and
  `.debug_line` editor tabs: `DwarfOutlineNode` (stable path-derived id, label, hex byte range, deferred
  children), `dwarfOutlineSections` (a cheap section probe that parses nothing, so naming a tab is free),
  the three per-section builders, and `dwarfSearchIndex` for whole-tree find-and-jump. The label strings are
  the contract — both the IntelliJ Jewel tree and the VS Code virtualized tree render exactly these, and
  `DwarfOutlineTest` pins them. Children providers are pure and un-memoized; hosts cache.
- **CDP source location resolver** (`CdpSourceLocationResolver.kt`) — platform-agnostic DWARF-based source file + line ↔ Wasm byte offset resolver for CDP debug adapters; used by both `idea-plugin` and `vscode-plugin` to translate breakpoint positions and stack frame locations.
- **Runtime compatibility** (`features/`) — the WebAssembly feature catalog and the runtime
  compatibility model. `WasmFeatureCatalog.kt` parses WebAssembly's `features.json` (the
  four-way `status` union from its schema, plus the `[status, note]` wrapper) leniently, so
  an upstream schema addition degrades rather than breaking an installed Hexana;
  `WasmFeatureVersions.kt` compares the dotted-numeric version strings that file uses;
  `RuntimeTargets.kt` owns the `.hexana/runtimes.json` format and the `HEXANA_PROJECT_DIR`
  constant; `FeatureCatalogRefresh.kt` holds the pure TTL/fallback decisions each host's
  fetcher applies; `WasmRuntimeCompatibility.kt` turns detected `WasmProposal`s plus declared
  targets into per-runtime verdicts. `WasmCompatibilityTableTab.kt` builds the Compatibility
  tab from those verdicts. Every proposal maps to an upstream feature key through
  `WasmProposal.featureId`, pinned in both directions by `WasmFeatureCatalogTest`.
- **Scripting API** (`scripting/`) — the single definition of the custom script tab surface. `ScriptValue.kt` is a
  host-neutral lazy value tree; `ScriptBinaryApi.kt` builds the `binary` global from a `BinaryProvider` (reusing
  `fileTypeDescription`, `structureRanges`, and `TableTabsProviderRegistry` so `binary.tab(name)` reads the same tables
  the UIs render); `ScriptOutput.kt` owns `print`/`table`/`row`; `ScriptGlobals.kt` assembles every global a host binds and defines
  `open(path)` over the host-supplied `ScriptBinaryOpener`; `ScriptOutputBlocks.kt` segments a script's output
  lines back into the text and table blocks a script tab renders, and filters them; `ScriptApiReference.kt` is the
  source of truth for the documentation. `ScriptValueJs.kt` (jsMain) binds the tree into plain JS objects for the
  browser hosts; the GraalJS adapter lives in `mcp:scripting`.

## Scripting API Rules

The point of `scripting/` is that the IntelliJ plugin, the VS Code webview, the webapp worker, and the `run-script` CLI
all bind one definition. Before this existed each host hand-maintained its own wrappers and exposed a different `binary`.

- Add or change a script member in `ScriptBinaryApi.kt` only, then document it in `ScriptApiReference.kt`. Never add one
  in a host adapter.
- Hosts bind exactly what `scriptGlobals` returns, never a global of their own, so a global cannot exist in one host and
  be missing in another. A global that needs something only the host can do takes a capability parameter, the way
  `open(path)` takes `ScriptBinaryOpener` — and it stays bound even where that capability is absent, failing with a
  message that says where it does work. An unbound name is worse than a failing one: in a browser `open` would silently
  resolve to `window.open`.
- `ScriptApiReferenceTest` compares the documented globals against `scriptGlobals`, so a new global fails the build until
  it is documented, exactly as a new `binary` member does.
- `ScriptApiReferenceTest` asserts the documented shapes match what `scriptBinaryApi` exposes, in both directions. A new
  member fails the build until it is documented — that is intentional.
- Do not claim a curated name that already exists on a `BinaryProvider` (`sections`, `functions`, `imports`, `exports`,
  `header`, `symbols`). Those resolve through the fall-through to the format-specific provider, and shadowing one would
  silently change its shape for existing scripts.
- Keep `Obj` members and `Arr` elements lazy. A `Functions` table can have hundreds of thousands of rows.
- Output layout belongs in `ScriptOutputBlocks.kt`, not in a host renderer. Segmentation is derived from the line
  stream because that stream is all some hosts receive (the webapp worker posts lines over a message channel; the CLI
  and MCP tool return lines as their documented result shape), so deriving it keeps every host laying output out the
  same way. Host renderers decide only how a text or table block *looks*.

## KMP Targets

- JVM (consumed by `idea-plugin` and `mcp:scripting`)
- JS (consumed by `vscode-plugin:webview`, `web-ui`, and the `webapp` parser worker)
- WasmJS

`src/jsMain` holds the JS-only scripting binder; everything else is `commonMain`.

## Dependencies

- `:wasmParser`
- `:binaryProvider`
- `:encdec`
- `:debugInfo:dwarf`

## Runtime Compatibility Rules

- `.hexana/runtimes.json` is read and written by both the IntelliJ plugin and the VS Code
  extension, and is committed to version control. That is the whole point of it: a project
  declares its target runtimes once. So `parseRuntimeTargets` stays lenient (a hand-edited or
  half-merged file must degrade to "nothing declared", never surface an error on file open)
  and `serializeRuntimeTargets` stays byte-stable (re-saving an unchanged selection must not
  dirty the file).
- `features/features.json` is a committed snapshot of upstream, compiled into a checked-in
  Kotlin constant because `wasmJs` has no convenient resource loader. It is the offline
  fallback only — at runtime each host prefers its own ETag-validated cache. Refresh it with
  `./gradlew :plugins-shared:updateBundledWasmFeatures` in the same change that replaces the
  JSON; `verifyBundledWasmFeaturesUpToDate` runs as part of `check`.
- Adding a `WasmProposal` requires a `featureId` that exists in that snapshot. It also
  requires updating nothing in the webapp worker protocol — its allow-list is derived from
  `WasmProposal.entries` precisely so it cannot drift.

## Rules

- Keep all code platform-agnostic in `commonMain`. Do not add platform-specific source sets unless absolutely necessary.
- Do not add any UI framework dependencies (Compose, Jewel, IntelliJ Platform, HTML DOM).
- All production code uses the `org.jetbrains.hexana.shared` package prefix.
- When adding new shared logic, prefer pure functions over stateful classes.
- Both `idea-plugin` and `vscode-plugin:webview` maintain thin delegation wrappers for backward compatibility — update those when adding or changing public API here.

## Testing

- Run shared module tests: `./gradlew :plugins-shared:build`
- After changes, also run: `./gradlew :idea-plugin:test` and `./gradlew :vscode-plugin:webview:compileKotlinJs`
