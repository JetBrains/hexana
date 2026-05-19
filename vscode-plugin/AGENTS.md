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
- Run a mock GitHub Releases API for MCP update testing: `./gradlew :mcp:server:distZip && cd vscode-plugin && npm run mock:mcp-release`
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

## Working Rules

- Keep the TypeScript host as thin as possible; all Wasm logic belongs in Kotlin.
- Binary analysis, dependency resolution, and utility functions belong in `extension-lib/` (Kotlin/JS), not duplicated in TypeScript.
- The webview communicates with the extension host exclusively via `postMessage` / `onDidReceiveMessage`.
- Follow the `org.jetbrains.hexana.vscode` package prefix for Kotlin sources in this module.
- Do not duplicate parsing or analysis logic that already exists in `wasmParser` or `binaryProvider`.
