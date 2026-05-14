---
title: Hexana for VS Code — Architecture (0.0.2)
description: Three-layer architecture of the Hexana VS Code extension — TypeScript host, Kotlin/JS extension library, and Compose-for-Web webview.
version: "0.0.2"
audience: contributors
---

# Architecture

This page describes the architecture of the Hexana VS Code extension as of release 0.0.2. For users, [`features.md`](features.md) is the right starting point.

## Three-layer split

```
┌──────────────────────────────────────────────────────┐
│  VS Code Extension Host  (TypeScript)                │
│  · extension.ts ─► WasmEditorProvider.ts             │
│  · CustomReadonlyEditorProvider for *.wasm           │
│  · reads .wasm via VS Code FS, posts bytes to webview│
│  · handles run-export, show-wat, open-module msgs    │
│  · manages terminals, virtual FS, WAT content        │
└───────┬──────────────────────┬───────────────────────┘
        │ require()            │ postMessage / onDidReceiveMessage
        ▼                      ▼
┌──────────────────┐  ┌────────────────────────────────┐
│  Extension Lib   │  │  Webview                       │
│  (Kotlin/JS)     │  │  (Kotlin/JS + Compose for Web) │
│  · WasmAnalysis  │  │  · 11 analysis tabs            │
│  · DependencyRes │  │  · virtual-scrolling hex view  │
│  · ShellUtils    │  │  · resizable layout            │
│  · Analytics     │  │  · WAT printer (WASM-based)    │
└──────────────────┘  └────────────────────────────────┘
```

The split:

1. **Extension host** (TypeScript) — thin layer that talks to VS Code's APIs.
2. **Extension library** (Kotlin/JS) — pure analysis logic, no VS Code APIs.
3. **Webview** (Kotlin/JS + Compose-for-Web) — all UI rendering, including hex viewer and analysis tabs.

This is intentional: per `vscode-plugin/AGENTS.md`, **all WASM logic belongs in Kotlin**. The TypeScript host should be as thin as possible.

## Source layout

```
vscode-plugin/
├── src/                            # TypeScript extension host
│   ├── extension.ts                # activation + analytics consent + region check
│   ├── WasmEditorProvider.ts       # CustomReadonlyEditorProvider implementation
│   ├── webviewContent.ts           # HTML scaffold for the webview
│   └── extension-lib.d.ts          # TS types for the Kotlin/JS extension lib
├── extension-lib/                  # Kotlin/JS — extension host logic
│   └── src/jsMain/kotlin/org/jetbrains/hexana/vscode/lib/
│       ├── Main.kt                 # @JsExport API surface
│       ├── WasmAnalysis.kt         # binary parsing entry points
│       ├── DependencyResolver.kt   # component dependency resolution
│       ├── ShellUtils.kt           # shell arg parsing/escaping
│       └── Analytics.kt            # PostHog client
├── webview/                        # Kotlin/JS + Compose-for-Web — UI
│   └── src/jsMain/kotlin/org/jetbrains/hexana/vscode/
│       ├── Main.kt                 # webview entry point
│       ├── HexanaPanel.kt          # 11-tab parent panel
│       ├── HexViewerPanel.kt       # virtual-scroll hex dump
│       ├── PanelTable.kt           # sortable/searchable table
│       ├── (per-tab files: SummaryTab, ExportsTab, ImportsTab, FunctionsTab,
│       │    DataTab, CustomSectionsTab, SizeTab, MonosTab, GarbageTab,
│       │    ModulesTab, WatTab)
│       ├── WasmPrinterJs.kt        # WASM-based WAT printer
│       ├── FunctionArgsDialog.kt   # Run dialog
│       ├── EditorToolbar.kt        # file path / kind badge / Run button
│       ├── SelectionStatusBar.kt   # byte-range readout
│       ├── ResizeDivider.kt        # draggable layout divider
│       └── VscodeMessageBridge.kt  # postMessage abstraction
├── media/                          # static assets (icon.png, editor.css)
├── test/                           # Mocha integration tests
├── package.json                    # VS Code manifest
├── tsconfig.json
└── build-and-run-locally.{sh,bat}
```

## Inter-layer communication

### Extension host ↔ Extension library

Synchronous JS function calls. The TypeScript host requires the Kotlin/JS bundle once at activation:

```typescript
const extensionLib = require(
  "../extension-lib/build/kotlin-webpack/js/productionExecutable/extension-lib.js"
).hexanaExtensionLib.org.jetbrains.hexana.vscode.lib;
```

…and calls `@JsExport`-ed functions like `extensionLib.resolveAllDependencies(...)`, `extensionLib.isComponentBinary(...)`, `extensionLib.splitShellArgs(...)`, `extensionLib.shellEscape(...)`, `extensionLib.findWorkspaceRoot(...)`. Filesystem access is injected via a `FileSystemAccess` callback interface implemented in TypeScript so the Kotlin side stays VS-Code-agnostic.

### Extension host ↔ Webview

VS Code's standard webview messaging — `webview.postMessage(...)` from host to webview, `vscode.postMessage(...)` from webview to host, with `webview.onDidReceiveMessage(...)` on each side. Hexana wraps both in `VscodeMessageBridge` so the Kotlin side uses idiomatic Kotlin types.

Message types include:

| Direction | Type | Payload |
|---|---|---|
| Host → Webview | `binary-data` | Raw bytes of the loaded `.wasm`. |
| Host → Webview | `kind-badge` | Detected binary kind. |
| Webview → Host | `run-export` | Export name + arguments to invoke through Wasmtime. |
| Webview → Host | `show-wat` | Request WAT rendering in a native VS Code editor tab. |
| Webview → Host | `open-module` | Nested-module URI to open in a new editor tab. |
| Webview → Host | `analytics-event` | Event name + payload, routed to the PostHog client in `extension-lib`. |

The bytes themselves go through `postMessage` once at editor open — no streaming. For multi-MB binaries this is the dominant startup cost; the webview parses incrementally afterwards.

## Shared KMP modules

All three layers reuse existing Kotlin Multiplatform modules from the Hexana monorepo:

| Module | Used by | Provides |
|---|---|---|
| `wasmParser` | Both Kotlin layers | Binary decoding for core Wasm + Component Model. |
| `binaryProvider` | Both Kotlin layers | High-level analysis (imports, exports, functions, size, etc.). |
| `plugins-shared` | Both Kotlin layers | Shared run utilities, import stub generation, component resolution helpers. |
| `encdec` | Both Kotlin layers | `CommonByteBuffer` and byte-buffer helpers. |
| `graalwasm:wat2wasm`, `graalwasm:wasmprinter` | Webview | WAT printer (WASM-based, compiled from wabt). |

This is the same module set the JetBrains plugin pulls in — every fix to the parser or analyser benefits both products simultaneously.

## Build pipeline

`vscode-plugin/build-and-run-locally.sh` (or `.bat` on Windows) is the canonical local build path. It runs:

1. `./gradlew :vscode-plugin:extension-lib:jsBrowserProductionWebpack` — Kotlin/JS → single JS bundle at `extension-lib/build/kotlin-webpack/js/productionExecutable/extension-lib.js`.
2. `./gradlew :vscode-plugin:webview:jsBrowserProductionWebpack` — Compose-for-Web → single JS bundle at `webview/build/kotlin-webpack/js/productionExecutable/webview.js`.
3. `cd vscode-plugin && npm run compile` — TypeScript host → `out/extension.js`.
4. Optionally `npm run obfuscate` — JavaScript Obfuscator pass over both Kotlin/JS bundles and the TypeScript output. Used only for release packaging.
5. Optionally `npx @vscode/vsce package` — wraps the result into a `.vsix`.

For a release-ready `.vsix`, use `build-release-package.sh` (obfuscation on). For development with sourcemaps and no obfuscation, use `build-and-run-locally.sh`.

## Publishing

`publish.sh` (or `.bat`) wraps both marketplace upload paths:

- **Visual Studio Marketplace** via `@vscode/vsce` — requires `VSCE_PAT` (a Personal Access Token from a Microsoft account associated with the `JetBrains` publisher).
- **Open VSX** via `ovsx` — requires `OVSX_PAT`.

Control with the `PUBLISH_TARGET` env var: `vscode`, `openvsx`, or `all` (default).

## Threading and concurrency

VS Code webviews run on their own thread (a separate process, in fact, sandboxed). The extension host runs on Node.js's event loop. There are no shared mutable structures between host and webview — every interaction goes through `postMessage`, which is serialised and async by construction.

Inside the Kotlin/JS layers, everything is single-threaded (the JS event loop). The cache in `WasmBinaryDataCacheService` is a plain `Map` keyed by URI string — no synchronisation needed.

## Testing

`vscode-plugin/test/` contains Mocha-based integration tests:

- Use `@vscode/test-electron` to spin up a VS Code instance with the extension loaded.
- Open fixture `.wasm` files from `vscode-plugin/test/fixtures/`.
- Assert on the extension's behaviour through VS Code's API.
- Mocha reports through a TeamCity reporter when running in CI.

Run locally: `cd vscode-plugin/test && npm install && npm test`.

## Compose-for-Web considerations

The webview UI is **Compose for Web**, not React or vanilla DOM. This is a deliberate choice:

- The JetBrains plugin uses Compose-for-Desktop; sharing UI primitives (panels, tables) between desktop and web means one codebase for both products' analysis tabs.
- Compose Multiplatform's Web target compiles to a single JS bundle that runs in the webview.
- Some VS Code-native UI conventions (theming, focus rings) need explicit handling — `editor.css` carries the bridge styles.

A consequence: the webview is heavier than a pure-React equivalent (the Compose runtime adds ~200 KB before gzip). For inspection-style tools this is acceptable; for very small extensions it would not be.

## See also

- [`features.md`](features.md), [`run-support.md`](run-support.md), [`component-model.md`](component-model.md), [`troubleshooting.md`](troubleshooting.md).
- The repo-level [JetBrains plugin architecture](../jetbrains/architecture.md) for the IntelliJ-side companion.
- `vscode-plugin/AGENTS.md` for the per-module agent-facing project context.
