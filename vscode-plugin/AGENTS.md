# vscode-plugin Module

VS Code extension for WebAssembly binary analysis with a Compose for Web custom editor.

## What This Module Contains

- `src/`: TypeScript source for the VS Code extension host (activation, custom editor provider, webview lifecycle).
- `test/`: VS Code Extension Tester acceptance tests. The runner installs the VSIX from `VSIX_PATH`, opens the
  fixture directory as the workspace, opens `WASM_FIXTURE`, and includes a packaged MCP stdio smoke against the installed
  extension's bundled `server/` distribution.
- `extension-lib/`: Kotlin/JS submodule that compiles shared extension-host logic (dependency resolution, binary analysis, shell utilities) into a single JS bundle consumed by the TypeScript host.
- `webview/`: Kotlin/JS submodule that compiles Compose for Web UI for the webview panel.
- `server/`: generated packaging output copied from `mcp/vscode-server:installDist`; it is bundled into the VSIX but
  not committed.
- `media/`: Static assets (icons, CSS) bundled into the extension.
- `package.json`: VS Code extension manifest with contribution points.
- `tsconfig.json`: TypeScript compiler configuration.

## Architecture

The extension uses VS Code's Custom Editor API (`CustomReadonlyEditorProvider`) to open `.wasm` files.

1. **Extension host** (TypeScript): Registers the custom editor, reads the binary file, posts the raw bytes into a webview, and handles run/compose commands. VS Code-specific logic (terminals, filesystem provider, tool execution) stays in TypeScript.
2. **Extension library** (Kotlin/JS): Provides binary analysis (`wasmParser`-backed), component dependency resolution, workspace root detection, and shell argument utilities. The TypeScript host imports the webpack-bundled JS output and calls `@JsExport` functions. Filesystem access is injected via a `FileSystemAccess` callback interface implemented in TypeScript.
3. **Webview** (Kotlin/JS + Compose for Web): Receives the binary via `postMessage`, wraps it in a `CommonByteBuffer`, constructs a `BinaryProvider`, and renders the hex viewer and analysis panels using Compose for Web.
4. **MCP server** (Kotlin/JVM): The extension registers a VS Code MCP server definition provider that launches the
   bundled `mcp/vscode-server` distribution over stdio.

All three layers reuse the existing KMP modules:
- `wasmParser` – binary decoding.
- `binaryProvider` – high-level provider abstraction and analysis.
- `plugins-shared` – shared run utilities, import stub generation, component resolution helpers.
- `encdec` – byte-buffer helpers.

## Build Commands

- Build the Kotlin extension library: `./gradlew :vscode-plugin:extension-lib:jsBrowserProductionWebpack`
- Build the Kotlin webview: `./gradlew :vscode-plugin:webview:jsBrowserProductionWebpack`
- Build all VS Code package assets, including the bundled MCP server: `./gradlew :vscode-plugin:assembleExtensionAssets`
- Compile the TypeScript extension: `cd vscode-plugin && npm run compile`
- Package the extension: `cd vscode-plugin && npx @vscode/vsce package`
- Build and run locally (no obfuscation): `cd vscode-plugin && ./build-and-run-locally.sh` (or `.bat` on Windows)
- Build VSIX ready for distribution: `cd vscode-plugin && ./build-release-package.sh` (or `.bat` on Windows)
- Publish to VS Code Marketplace and Open VSX: `cd vscode-plugin && ./publish.sh` (or `.bat` on Windows)
  - Requires `VSCE_PAT` and `OVSX_PAT` environment variables. Set `PUBLISH_TARGET` to `vscode`, `openvsx`, or `all` (default).

## Working Rules

- Keep the TypeScript host as thin as possible; all Wasm logic belongs in Kotlin.
- Binary analysis, dependency resolution, and utility functions belong in `extension-lib/` (Kotlin/JS), not duplicated in TypeScript.
- The webview communicates with the extension host exclusively via `postMessage` / `onDidReceiveMessage`.
- Follow the `org.jetbrains.hexana.vscode` package prefix for Kotlin sources in this module.
- Do not duplicate parsing or analysis logic that already exists in `wasmParser` or `binaryProvider`.
