# Hexana VS Code Extension (Draft)

VS Code extension that opens `.wasm` files in a custom editor powered by Compose for Web.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  VS Code Extension Host  (TypeScript)                │
│                                                      │
│  extension.ts ─► WasmEditorProvider.ts               │
│      registers CustomReadonlyEditorProvider           │
│      reads .wasm file ─► posts bytes to webview       │
└────────────────────┬─────────────────────────────────┘
                     │  postMessage / onDidReceiveMessage
┌────────────────────▼─────────────────────────────────┐
│  Webview  (Kotlin/JS + Compose for Web)              │
│                                                      │
│  Main.kt ─► VscodeMessageBridge.kt                   │
│      receives bytes, wraps in CommonByteBuffer        │
│      creates BinaryProvider (wasmParser + encdec)     │
│                                                      │
│  EditorToolbar.kt      ─  file name, size, type badge│
│  HexViewerPanel.kt     ─  virtual-scroll hex dump    │
│  SelectionStatusBar.kt ─  cursor / selection info     │
│  SectionPanel.kt       ─  section table + click-to-  │
│                           highlight in hex viewer     │
└──────────────────────────────────────────────────────┘
```

The Kotlin webview reuses existing KMP modules:
- **wasmParser** – binary decoding for core Wasm and component model.
- **binaryProvider** – high-level analysis (imports, exports, functions, size, etc.).
- **encdec** – `CommonByteBuffer` abstraction.

## Project Structure

```
vscode-plugin/
├── AGENTS.md              # module-specific guidelines
├── README.md              # this file
├── package.json           # VS Code extension manifest
├── tsconfig.json          # TypeScript configuration
├── .gitignore
├── media/
│   └── editor.css         # webview stylesheet (uses VS Code CSS variables)
├── src/                   # TypeScript extension host
│   ├── extension.ts       # activate / deactivate
│   ├── WasmEditorProvider.ts   # CustomReadonlyEditorProvider
│   └── webviewContent.ts  # HTML builder for the webview
└── webview/               # Kotlin/JS Compose for Web submodule
    ├── build.gradle.kts   # Gradle build (JS target, webpack)
    └── src/jsMain/kotlin/org/jetbrains/hexana/vscode/
        ├── Main.kt                # entry point, renderComposable
        ├── VscodeMessageBridge.kt # postMessage bridge
        ├── EditorToolbar.kt       # toolbar composable
        ├── HexViewerPanel.kt      # virtual-scroll hex viewer
        ├── SelectionStatusBar.kt  # cursor / selection status bar
        └── SectionPanel.kt        # section list composable
```

## Building

### Prerequisites

- JDK 21 (for Gradle / Kotlin compiler)
- Node.js ≥ 18 + npm (for the TypeScript extension host)

### Steps

1. **Build the Kotlin/JS webview bundle:**

   ```bash
   ./gradlew :vscode-plugin:webview:jsBrowserProductionWebpack
   ```

   This produces `vscode-plugin/webview/build/dist/js/productionExecutable/webview.js`.

2. **Install npm dependencies and compile TypeScript:**

   ```bash
   cd vscode-plugin
   npm install
   npm run compile
   ```

3. **Package the extension (optional):**

   ```bash
   cd vscode-plugin
   npx @vscode/vsce package
   ```

   This creates a `.vsix` file you can install via `code --install-extension hexana-wasm-*.vsix`.

## Development

- Run `npm run watch` in `vscode-plugin/` for incremental TypeScript builds.
- Re-run the Gradle webpack task after changing Kotlin sources.
- Press F5 in VS Code (with this folder open) to launch an Extension Development Host — configure a `.vscode/launch.json` if needed.

## Status

This is a **draft scaffold**. Key items remaining:

- [x] Virtual scrolling for the hex viewer (renders only visible rows + overscan).
- [x] Byte selection via click, shift-click, and drag.
- [x] Keyboard navigation (arrow keys, shift+arrows for selection).
- [x] Selection synchronisation — clicking a section row highlights its bytes.
- [x] Status bar showing cursor offset and selection range.
- [ ] Full section detail views (imports, exports, functions, WAT disassembly).
- [ ] Theme integration (map VS Code CSS variables into Compose styles).
- [ ] Search within the hex view.
- [ ] Tests for the Kotlin webview composables.
