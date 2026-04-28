# Hexana VS Code Extension

VS Code extension for WebAssembly binary analysis. Opens `.wasm` files in a custom editor powered by Compose for Web, with a hex viewer, structural analysis panels, and integrated run support.

## Features

- **Custom binary editor** — opens `.wasm` files with a dedicated read-only editor instead of the default hex view.
- **Hex viewer** — virtual-scrolling hex dump with byte selection (click, shift-click, drag), keyboard navigation (arrow keys, shift+arrows), and text search.
- **Binary kind detection** — automatically identifies Core Wasm, Component Model, and generic binaries.
- **Analysis tabs** — tabbed panel with up to 11 views depending on binary type:
  - **Summary** — sections table and binary statistics.
  - **Exports** — exports with kind, name, index, and function signatures.
  - **Imports** — imports with kind, module, and name.
  - **Functions** — function index, name, and signature (core Wasm).
  - **Data** — data segments overview (core Wasm).
  - **Custom** — custom sections list (core Wasm).
  - **Top** — largest contributors by size.
  - **Monos** — monomorphisation analysis (core Wasm).
  - **Garbage** — unreferenced/dead code detection (core Wasm).
  - **Modules** — clickable nested module list with drill-down (component model).
  - **WAT** — WebAssembly Text representation using a wasm-based converter; opens in a native VS Code editor tab with syntax highlighting.
- **Searchable and sortable tables** — all analysis tables support column sorting and text search.
- **Resizable panels** — drag the divider between the hex viewer and the analysis panel to adjust layout.
- **Run configurations** — run Wasm modules directly from the editor toolbar via [wasmtime](https://wasmtime.dev/), with a dialog for program arguments. Supports both core modules (with automatic import stub generation and `--preload`) and component model binaries (with dependency resolution and composition via `wasm-tools compose` or `wac plug`).
- **Component dependency resolution** — automatically discovers and resolves component imports by scanning workspace directories for matching `.wasm` files, including transitive dependencies.
- **Nested module navigation** — open individual modules from a component binary in separate editor tabs using a virtual filesystem provider.
- **WAT language association** — registers `.wat` file extension for syntax highlighting.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│  VS Code Extension Host  (TypeScript)                │
│                                                      │
│  extension.ts ─► WasmEditorProvider.ts               │
│      registers CustomReadonlyEditorProvider           │
│      reads .wasm file ─► posts bytes to webview       │
│      handles run-export, show-wat, open-module msgs  │
│      manages terminals, virtual FS, WAT content      │
│                                                      │
│  extension-lib.d.ts                                  │
│      TypeScript type declarations for the Kotlin/JS  │
│      extension library API                           │
└───────┬──────────────────────┬────────────────────────┘
        │ require()            │ postMessage / onDidReceiveMessage
┌───────▼──────────┐  ┌───────▼────────────────────────┐
│  Extension Lib   │  │  Webview                       │
│  (Kotlin/JS)     │  │  (Kotlin/JS + Compose for Web) │
│                  │  │                                │
│  WasmAnalysis    │  │  Main.kt                       │
│  DependencyRes.  │  │    ─► WasmEditorContent        │
│  ShellUtils      │  │                                │
│                  │  │  EditorToolbar.kt  ─ file info, │
│  @JsExport API:  │  │      run button, kind badge    │
│  · resolveAll    │  │  HexViewerPanel.kt ─ virtual-  │
│    Dependencies  │  │      scroll hex dump + search  │
│  · isComponent   │  │  SelectionStatusBar.kt         │
│    Binary        │  │  ResizeDivider.kt              │
│  · splitShellArgs│  │  HexanaPanel.kt ─ 11 tabs     │
│  · shellEscape   │  │  PanelTable.kt ─ sortable,    │
│  · findWorkspace │  │      searchable tables         │
│    Root          │  │  WasmPrinterJs.kt ─ wasm-based │
│                  │  │      WAT converter             │
│                  │  │  FunctionArgsDialog.kt         │
│                  │  │  VscodeMessageBridge.kt        │
└──────────────────┘  └────────────────────────────────┘
```

The extension is organised into three layers:

1. **Extension host** (TypeScript) — Registers the custom editor, reads binary files, posts raw bytes into the webview, and handles VS Code-specific operations: terminal management for run configurations, virtual filesystem for nested modules, and WAT content provider for Monaco integration.
2. **Extension library** (Kotlin/JS) — Pure analysis logic compiled to a single JS bundle and imported by the TypeScript host. Provides binary analysis (`wasmParser`-backed), component dependency resolution, workspace root detection, and shell argument utilities via `@JsExport` functions.
3. **Webview** (Kotlin/JS + Compose for Web) — Receives the binary via `postMessage`, wraps it in a `CommonByteBuffer`, constructs a `BinaryProvider`, and renders the hex viewer and all analysis panels using Compose for Web.

All three layers reuse existing Kotlin Multiplatform modules:
- **wasmParser** — binary decoding for core Wasm and component model.
- **binaryProvider** — high-level analysis (imports, exports, functions, size, etc.).
- **plugins-shared** — shared run utilities, import stub generation, component resolution helpers.
- **encdec** — `CommonByteBuffer` abstraction.

## Project Structure

```
vscode-plugin/
├── AGENTS.md                  # module-specific guidelines
├── README.md                  # this file
├── package.json               # VS Code extension manifest
├── tsconfig.json              # TypeScript configuration
├── build-and-run-locally.sh   # one-step build + install script
├── .gitignore
├── media/
│   └── editor.css             # webview stylesheet (uses VS Code CSS variables)
├── src/                       # TypeScript extension host
│   ├── extension.ts           # activate / deactivate
│   ├── WasmEditorProvider.ts  # CustomReadonlyEditorProvider, run logic,
│   │                          #   nested module FS, WAT content provider
│   ├── webviewContent.ts      # HTML builder for the webview
│   └── extension-lib.d.ts     # TypeScript type declarations for extension-lib
├── extension-lib/             # Kotlin/JS extension library submodule
│   └── src/jsMain/kotlin/org/jetbrains/hexana/vscode/lib/
│       ├── Main.kt                # library entry point
│       ├── DependencyResolver.kt  # component dependency resolution
│       ├── ShellUtils.kt         # shell argument splitting and escaping
│       └── WasmAnalysis.kt       # binary type detection, import/export parsing
└── webview/                   # Kotlin/JS Compose for Web submodule
    ├── build.gradle.kts       # Gradle build (JS target, webpack)
    └── src/jsMain/kotlin/org/jetbrains/hexana/vscode/
        ├── Main.kt                # entry point, renderComposable
        ├── VscodeMessageBridge.kt # postMessage bridge
        ├── EditorToolbar.kt       # toolbar: file name, size, kind badge, run button
        ├── HexViewerPanel.kt      # virtual-scroll hex viewer with search
        ├── SelectionStatusBar.kt  # cursor / selection status bar
        ├── ResizeDivider.kt       # draggable panel resize divider
        ├── HexanaPanel.kt         # tabbed panel container (11 tabs)
        ├── SummaryTab.kt          # summary: sections table + statistics
        ├── ExportsTab.kt          # exports table with signatures
        ├── ImportsTab.kt          # imports table
        ├── FunctionsTab.kt        # functions table (core Wasm)
        ├── DataTab.kt             # data segments (core Wasm)
        ├── CustomSectionsTab.kt   # custom sections (core Wasm)
        ├── SizeTab.kt             # top contributors by size
        ├── MonosTab.kt            # monomorphisation analysis (core Wasm)
        ├── GarbageTab.kt          # dead code detection (core Wasm)
        ├── ModulesTab.kt          # nested modules (component model)
        ├── WatTab.kt              # WAT text representation
        ├── WasmPrinterJs.kt       # wasm-based WAT converter
        ├── FunctionArgsDialog.kt  # run configuration dialog
        ├── PanelTable.kt          # shared sortable/searchable table composables
        └── SectionPanel.kt        # section list with hex highlight on click
```

## Building

### Prerequisites

- JDK 21 (for Gradle / Kotlin compiler)
- Node.js ≥ 18 + npm (for the TypeScript extension host)

### Quick Build

Use the provided script to build everything, package, and install in one step:

```bash
cd vscode-plugin
./build-and-run-locally.sh
```

### Manual Steps

1. **Build the Kotlin/JS extension library:**

   ```bash
   ./gradlew :vscode-plugin:extension-lib:jsBrowserProductionWebpack
   ```

2. **Build the Kotlin/JS webview bundle:**

   ```bash
   ./gradlew :vscode-plugin:webview:jsBrowserProductionWebpack
   ```

3. **Install npm dependencies and compile TypeScript:**

   ```bash
   cd vscode-plugin
   npm install
   npm run compile
   ```

4. **Package the extension (optional):**

   ```bash
   cd vscode-plugin
   npx @vscode/vsce package --allow-missing-repository
   ```

   This creates a `.vsix` file you can install via `code --install-extension hexana-wasm-*.vsix`.

## Development

- Run `npm run watch` in `vscode-plugin/` for incremental TypeScript builds.
- Re-run the Gradle webpack tasks after changing Kotlin sources in either `extension-lib/` or `webview/`.
- Press F5 in VS Code (with this folder open) to launch an Extension Development Host.

### Runtime Dependencies (optional)

- [wasmtime](https://wasmtime.dev/) — required for the **Run** button to execute Wasm modules.
- [wasm-tools](https://github.com/bytecodealliance/wasm-tools) or [wac](https://github.com/bytecodealliance/wac) — used for component composition when running component model binaries with unresolved imports.
