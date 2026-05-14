---
title: Getting Started with Hexana for VS Code (0.0.2)
description: How to install the Hexana VS Code extension, open a WebAssembly file, and find the main views.
version: "0.0.2"
---

# Getting Started with Hexana for VS Code

This page covers installation, first launch, and the layout you will see when you open a `.wasm` file.

## How do I install Hexana for VS Code?

### From the Visual Studio Marketplace

1. Open VS Code.
2. Open the **Extensions** view (`Cmd/Ctrl+Shift+X`).
3. Search for `Hexana`.
4. Click **Install** on the entry by **JetBrains** (look for the verified-publisher badge).

The extension activates on `onStartupFinished`, so it is ready as soon as you launch VS Code.

### From Open VSX (Cursor, VSCodium, Code OSS)

1. Open the Extensions view in your editor.
2. Confirm the Open VSX marketplace is configured (default in Cursor and VSCodium).
3. Search `Hexana` and install the entry by `JetBrains`.

### From a `.vsix` file (manual)

1. Download `hexana-wasm-0.0.2.vsix` from the GitHub release.
2. In VS Code, run **Extensions: Install from VSIX…** from the command palette (`Cmd/Ctrl+Shift+P`).
3. Pick the downloaded file.

After install, Hexana registers itself as the **default editor** for `.wasm` files (`workbench.editorAssociations` is set automatically — you can override this in your `settings.json` if you prefer a different default).

## How do I open a WebAssembly file?

Any of the following will open the file in Hexana's editor:

- Drag a `.wasm` file into the VS Code window.
- Use **File → Open File…** and pick a `.wasm`.
- Click a `.wasm` in the **Explorer** sidebar — VS Code uses Hexana as the default editor.
- From the command palette, **File: Open File…** then pick a `.wasm`.

When Hexana opens a `.wasm`, the extension host reads the binary, detects whether it is a **Core Wasm module**, a **Component Model component**, or a **generic Wasm file**, and posts the raw bytes into a webview. The webview parses the binary in-place (no server round-trip) and renders the hex view plus the analysis panel.

## What do I see when I open a file?

Hexana replaces the default editor with a single panel split into three regions:

```
┌──────────────────────────────────────────────────────────────┐
│  Editor Toolbar                                              │
│  · file path · binary kind badge · file size · Run button     │
├─────────────────────────────────┬────────────────────────────┤
│  Hex Viewer                     │   Analysis Tabs            │
│  · virtual-scrolling hex dump   │   · Summary · Exports …    │
│  · click / shift-click / drag   │   · Imports · Functions    │
│  · keyboard nav                 │   · Data · Custom · Top    │
│  · text search                  │   · Monos · Garbage        │
│                                 │   · Modules · WAT          │
├─────────────────────────────────┴────────────────────────────┤
│  Selection Status Bar — current byte range / decoded values  │
└──────────────────────────────────────────────────────────────┘
```

The vertical divider between the hex viewer and the analysis panel is **resizable** — drag it to suit your screen.

### Editor toolbar

- **File path** — full workspace-relative path.
- **Binary kind badge** — `core`, `component`, or `wasm` (generic).
- **File size** — in bytes plus a human-readable form.
- **Run button** — present when Wasmtime is available; clicking it opens the run dialog (see [`run-support.md`](run-support.md)).

### Hex viewer

- **Virtual scrolling** — even multi-MB binaries scroll smoothly.
- **Selection**: click a byte, `Shift+Click` to extend, drag to range-select.
- **Keyboard**: arrow keys move the caret; `Shift+Arrow` extends selection.
- **Search** (`Cmd/Ctrl+F`): text search across the hex dump.

### Analysis tabs

Up to 11 tabs, surfaced depending on binary type. See [`analysis-tabs.md`](analysis-tabs.md) for the per-tab reference.

### Selection status bar

When you select bytes in the hex viewer, the status bar shows the current byte range and decoded interpretations.

## How do I run a `.wasm` file?

If you have **Wasmtime** installed, click **Run** in the editor toolbar.

1. Pick an export (for core modules) — or let Hexana resolve the entry point (for components).
2. Provide program arguments in the dialog.
3. The extension opens a terminal and invokes Wasmtime with the right flags.

If Wasmtime is not on `PATH`, set the path in **Settings → Hexana → Wasmtime Path** (`hexana.wasmtimePath`). See [`run-support.md`](run-support.md) for the full run reference, including Component Model composition with `wasm-tools` or `wac`.

## What about WAT files?

Hexana registers the `wat` language association for `.wat` files in 0.0.2 — VS Code gives them a language identifier so other extensions (WebAssembly syntax-highlighting bundles, e.g.) can target them. Hexana itself does **not** open `.wat` in a custom editor in 0.0.2; that surface is JetBrains-IDE-only today.

The **WAT tab** inside the `.wasm` editor *is* available — Hexana renders the WAT representation of the loaded binary in a native VS Code editor tab on demand. See [`analysis-tabs.md#wat`](analysis-tabs.md).

## Next steps

- Browse the [`features.md`](features.md) catalogue.
- For Component Model binaries, read [`component-model.md`](component-model.md) — dependency resolution and nested-module navigation are the main capabilities you'll want.
- If something doesn't work, check [`troubleshooting.md`](troubleshooting.md).
