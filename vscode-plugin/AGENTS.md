# vscode-plugin Module

VS Code extension for WebAssembly binary analysis with a Compose for Web custom editor.

## What This Module Contains

- `src/`: TypeScript source for the VS Code extension host (activation, custom editor provider, webview lifecycle, MCP server download preparation and commands).
- `test/`: VS Code Extension Tester acceptance tests. The runner installs the VSIX from `VSIX_PATH`, opens the
  fixture directory as the workspace, and opens `WASM_FIXTURE`.
- `extension-lib/`: Kotlin/JS submodule that compiles shared extension-host logic (dependency resolution, binary analysis, shell utilities) into a single JS bundle consumed by the TypeScript host.
- `webview/`: Kotlin/JS submodule that compiles Compose for Web UI for the webview panel.
- `media/`: Static assets (icons, CSS) bundled into the extension.
- `package.json`: VS Code extension manifest with contribution points.
- `tsconfig.json`: TypeScript compiler configuration.

## Architecture

The extension uses VS Code's Custom Editor API (`CustomReadonlyEditorProvider`) to open `.wasm` files.

1. **Extension host** (TypeScript): Registers the custom editor, prepares the downloadable MCP server distribution in VS Code global storage, reads the binary file, posts the raw bytes into a webview, and handles run/compose commands. VS Code-specific logic (terminals, filesystem provider, tool execution) stays in TypeScript.
2. **Extension library** (Kotlin/JS): Provides binary analysis (`wasmParser`-backed), component dependency resolution, workspace root detection, and shell argument utilities. The TypeScript host imports the webpack-bundled JS output and calls `@JsExport` functions. Filesystem access is injected via a `FileSystemAccess` callback interface implemented in TypeScript.
3. **Webview** (Kotlin/JS + Compose for Web): Receives the binary via `postMessage`, wraps it in a `CommonByteBuffer`, constructs a `BinaryProvider`, and renders the hex viewer and analysis panels using Compose for Web.

All three layers reuse the existing KMP modules:
- `wasmParser` – binary decoding.
- `binaryProvider` – high-level provider abstraction and analysis.
- `plugins-shared` – shared run utilities, import stub generation, component resolution helpers.
- `encdec` – byte-buffer helpers.

## Build Commands

- Build the Kotlin extension library: `./gradlew :vscode-plugin:extension-lib:jsBrowserProductionWebpack`
- Build the Kotlin webview: `./gradlew :vscode-plugin:webview:jsBrowserProductionWebpack`
- Build all VS Code package assets: `./gradlew :vscode-plugin:assembleExtensionAssets`
- Compile the TypeScript extension: `cd vscode-plugin && npm run compile`
- Package the extension: `cd vscode-plugin && npx @vscode/vsce package`
- Build and run locally (no obfuscation): `cd vscode-plugin && ./build-and-run-locally.sh` (or `.bat` on Windows)
- Run a mock downloads-index API for MCP failure-path testing: `./gradlew :mcp:server:distZip && cd vscode-plugin && npm run mock:mcp-release`
- Build VSIX ready for distribution: `cd vscode-plugin && ./build-release-package.sh` (or `.bat` on Windows)
- Publish to VS Code Marketplace and Open VSX: `cd vscode-plugin && ./publish.sh` (or `.bat` on Windows)
  - Requires `VSCE_PAT` and `OVSX_PAT` environment variables. Set `PUBLISH_TARGET` to `vscode`, `openvsx`, or `all` (default).

## Table Tab Architecture

Table-based tabs (Data, Exports, Imports, Functions, Custom, Modules) use the shared `TableTabsProvider`
abstraction from `plugins-shared`. The webview-specific wiring lives in three files:

- `TableTabBridge.kt` — Converts `TableTabDescriptor` into Compose-for-Web HTML tables using
  `SearchableTable`/`PanelTableRow`. Also hosts `RunArrowButton`.
- `TableTabEnricher.kt` — Attaches VS Code-specific behaviour (run buttons, module-open actions) to
  table tab descriptors before the bridge renders them. Since VS Code dialogs are async composables
  (not blocking like Swing), the enricher uses callbacks (`onPendingWasmArgs`, `onPendingProgramArgs`,
  `onPendingComponentArgs`) so `HexanaPanel` can manage dialog state in the composable layer.
- `HexanaPanel.kt` — Builds the tab list by mixing provider-generated table tabs with fixed
  (non-table) tabs, and manages dialog composable state for run actions triggered by the enricher.

Non-table tabs (Sections, Top, Monos, Garbage, WAT) remain hardcoded in `HexanaPanel.kt`.

When adding a new table-based tab, add it to the relevant `TableTabsProvider` in `plugins-shared`;
it will appear automatically in both IntelliJ and VS Code. To attach VS Code-specific actions,
update `TableTabEnricher.kt`.

## Diff View

- A diff is a `hexana-module:` URI with `diff=true&mode=hex|hexana` and the two source URIs; the host posts
  `load-diff` with both payloads and `mode`, and the webview renders `DiffModeView` (`web-ui`), a switch between
  `HexDiffView` (hex diff with sections and table-tab diffs) and `BinaryDiffView` (the `BinaryDiffProvider` diff).
- `hexana.compareFiles` opens on `hexana`. VS Code's own diffs of two Hexana binaries are redirected to `hex` by
  `registerCompareSelectedRedirect`: a text-diff tab reports its sides; a diff of two custom-editor files is reported as
  an *unknown* tab (VS Code's `mainThreadEditorTabs` has no custom-diff input kind), so it is paired by its
  `a ↔ b` label with the sides the editor just resolved (`diffTabPairing.ts`).
- The hex-diff model, fold layout, section pairing and table-tab diffing are shared with IntelliJ in
  `plugins-shared/.../shared/diff/` (`HexDiff`, `HexDiffSections`, `HexDiffStructure`, `TableTabDiff`).

## Working Rules

- Keep the TypeScript host as thin as possible; all Wasm logic belongs in Kotlin.
- Binary analysis, dependency resolution, and utility functions belong in `extension-lib/` (Kotlin/JS), not duplicated in TypeScript.
- The webview communicates with the extension host exclusively via `postMessage` / `onDidReceiveMessage`.
- Post every byte payload to the webview through `toWebviewBytes` in `src/scriptBinaryRead.ts`.
  `vscode.workspace.fs.readFile` returns a Node `Buffer`, and `Buffer.toJSON()` runs before VS Code's
  typed-array replacer, so a `Buffer` posted unchanged arrives as `{type:"Buffer",data:[...]}` with no
  numeric `length` and the webview cannot decode it. This is what made `open(path)` hang forever.
- A webview request that keeps a script suspended must be answered on every path, including failures.
  `readBinaryFile` posts exactly one `binary-file-content` per `read-binary-file`; a dropped reply is a
  script that never finishes.
- Follow the `org.jetbrains.hexana.vscode` package prefix for Kotlin sources in this module.
- Do not duplicate parsing or analysis logic that already exists in `wasmParser` or `binaryProvider`.
- Keep the generated webview JavaScript and Wasm printer embedded in `webview.html` and reconstructed as Blob URLs.
  VS Code 1.131 on Windows can corrupt multi-chunk files loaded through `asWebviewUri`; the detailed compatibility
  rationale and upstream fix are documented next to the bootstrap in `src/webviewContent.ts`.
- LLDB stack frames can contain absolute DWARF paths from another build machine. Preserve existing local files
  and synthetic LLDB locations, but resolve missing paths through unambiguous registered breakpoint sources.
  Only replace a source path with an existing local file, clearing `sourceReference` when doing so.
- Keep the original Wasmtime DWARF command-palette test alongside the strict source-remapping regression.
  Clean up debug sessions and breakpoints after each case so toggling a breakpoint cannot disable a prior one.
  Its Run and Debug palette interaction requires the exact command and makes at most three attempts for transient
  WebDriver failures, including clicks intercepted while the palette closes. Persistent failures must still fail
  the test. Do not retry debug assertions.
- The strict Wasmtime DWARF regression must verify local source navigation and parameter values after pausing; a visible
  toolbar or generic debug UI is not sufficient. Value assertions must not be swallowed by retry catches.
  Use the test utility `isSameFile` to compare the opened source with the fixture by filesystem identity
  (`statSync` with BigInt device/file IDs), not path casing or contents. Case sensitivity belongs to the
  filesystem/directory, not just the host OS.
  Unavailable file IDs must fail the assertion rather than make unrelated files compare equal.
  Compile the extension before running adapter unit regressions: `cd vscode-plugin && npm run compile`, then
  `cd test && npm run unit`.
