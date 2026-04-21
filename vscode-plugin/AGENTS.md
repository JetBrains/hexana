# vscode-plugin Module

VS Code extension for WebAssembly binary analysis with a Compose for Web custom editor.

## What This Module Contains

- `src/`: TypeScript source for the VS Code extension host (activation, custom editor provider, webview lifecycle).
- `webview/`: Kotlin/JS submodule that compiles Compose for Web UI for the webview panel.
- `media/`: Static assets (icons, CSS) bundled into the extension.
- `package.json`: VS Code extension manifest with contribution points.
- `tsconfig.json`: TypeScript compiler configuration.

## Architecture

The extension uses VS Code's Custom Editor API (`CustomReadonlyEditorProvider`) to open `.wasm` files.

1. **Extension host** (TypeScript): Registers the custom editor, reads the binary file, and posts the raw bytes into a webview.
2. **Webview** (Kotlin/JS + Compose for Web): Receives the binary via `postMessage`, wraps it in a `CommonByteBuffer`, constructs a `BinaryProvider`, and renders the hex viewer and analysis panels using Compose for Web.

The Kotlin webview reuses the existing KMP modules:
- `wasmParser` – binary decoding.
- `binaryProvider` – high-level provider abstraction and analysis.
- `encdec` – byte-buffer helpers.

## Build Commands

- Build the Kotlin webview: `./gradlew :vscode-plugin:webview:jsBrowserProductionWebpack`
- Compile the TypeScript extension: `cd vscode-plugin && npm run compile`
- Package the extension: `cd vscode-plugin && npx @vscode/vsce package`

## Working Rules

- Keep the TypeScript host as thin as possible; all Wasm logic belongs in Kotlin.
- The webview communicates with the extension host exclusively via `postMessage` / `onDidReceiveMessage`.
- Follow the `org.jetbrains.hexana.vscode` package prefix for Kotlin sources in this module.
- Do not duplicate parsing or analysis logic that already exists in `wasmParser` or `binaryProvider`.
