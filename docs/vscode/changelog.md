---
title: Hexana for VS Code — Release Notes
description: Release notes for the Hexana VS Code extension.
version: "0.0.2"
released: 2026-05
---

# Release Notes — Hexana for VS Code

This page tracks releases of the VS Code extension. The authoritative source is the commit history on the `release/vscode-0.0.2` and `release/vscode-0.0.1` branches in the repository.

## 0.0.2 — preview

Source branch: `release/vscode-0.0.2`.

### Added

- **Setting to specify a custom Wasmtime path** (`hexana.wasmtimePath`) — for cases where Wasmtime is not on `PATH` or you want to pin to a specific build.
- **Setting to disable statistics collection** (`hexana.enableStatistics`) — independent of VS Code's global telemetry toggle, both must be on for any event to be sent.
- **Consent notice on first activation** — required disclosure of the anonymous analytics path.
- **Submodule → parent backreference** in the editor toolbar — opening a nested module from a component shows a clickable link back to the containing component's editor tab.
- **Marketplace listing improvements** — removed the broken image link, fixed the license link in the README, marked the extension as `preview`, refined the displayName and category list.

### Changed

- Display name on the marketplace: `Hexana WebAssembly and Hex Viewer` (more searchable).
- Categories simplified — `Programming Languages`, `Visualization`.

### Fixed

- Various stability and UX polish across the analysis tabs.

## 0.0.1

Initial public preview release. Shipped:

- **Custom binary editor** registered for `*.wasm` files.
- **Hex viewer** with byte selection, keyboard navigation, and text search.
- **Binary-kind detection** for Core Wasm, Component Model, and generic Wasm.
- **11 analysis tabs**: Summary, Exports, Imports, Functions, Data, Custom, Top, Monos, Garbage, Modules, WAT — surfaced per binary kind.
- **Sortable + searchable tables** across all analysis surfaces.
- **WAT view** in a native VS Code editor tab via a WASM-based wat printer.
- **Run support** via Wasmtime — core modules with auto-generated import stubs, components with dependency resolution and `wasm-tools compose` / `wac plug` composition.
- **Component Model navigation** — nested modules openable in separate editor tabs via a virtual filesystem provider.
- **`.wat` language association** so other extensions can target WAT files.

## Versioning policy

The VS Code extension uses **`0.0.x` preview versioning** during the early life cycle to signal that contract changes (settings, editor URIs, etc.) may happen between releases. Versioning will shift to a stable cadence aligned with the JetBrains plugin once the surface stabilises (likely `0.1.0`+).

## Compatibility

| VS Code version | Compatible? |
|---|---|
| 1.85+ | ✓ |
| 1.84 and older | ✗ |

The extension targets the Custom Editor API + Webview API as those existed in 1.85 (December 2023). Older versions are missing required APIs.

## Distribution channels

- **Visual Studio Marketplace** — `marketplace.visualstudio.com/items?itemName=JetBrains.hexana-wasm`.
- **Open VSX** — `open-vsx.org/extension/JetBrains/hexana-wasm`.
- **GitHub Releases** — `.vsix` artefacts attached to each release tag.

## See also

- [`getting-started.md`](getting-started.md), [`features.md`](features.md).
- The JetBrains plugin's [changelog](../jetbrains/changelog-0.9.md).
