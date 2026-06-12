---
title: Hexana for VS Code — Release Notes
description: Release notes for the Hexana VS Code extension.
version: "0.3.0"
---

# Release Notes — Hexana for VS Code

This page tracks releases of the VS Code extension.

## 0.3.0

Released **2026-06-11**.

### Added

- **Node.js and browser runtimes.** The Run picker can now target **Node.js** and the **browser** in addition to Wasmtime, WAMR, and GraalVM. These are **run-only** in VS Code — debugging is not yet wired for the Node.js or browser paths.
- **JAR / ZIP support.** `.jar`, `.zip`, `.war`, and `.apk` archives open in the Hexana editor with the hex viewer on top and a sortable, searchable entry list below. Click an entry that is itself a recognised binary (`.wasm`, `.class`, a nested archive, a native binary) to open it in a new editor tab.

### Changed

- **More reliable Windows debugging.** Better breakpoint detection and overall stability for the `lldb`-backed Wasmtime / WAMR debug path on Windows.

## 0.2.0

Released **2026-05-27**.

### Added

- **Native binary support (experimental).** ELF, Mach-O, and PE binaries open in the Hexana custom editor by magic-byte detection. Common extensions (`.elf`, `.so`, `.dylib`, `.bundle`, `.exe`, `.dll`, `.sys`) are matched first, but extensionless binaries with matching magic also open through Hexana. The hex viewer is identical to the `.wasm` case; the structure tab surfaces format-specific information (sections / segments / dynamic symbols for ELF, load commands for Mach-O, sections / exports / imports for PE).

### Changed

- **Improved discovery of `lldb-dap` on Linux.** The debug path now picks up `lldb-dap` from a wider set of paths and version-suffixed names, reducing the cases where a user has LLVM 22.1+ installed but Hexana fails to find it.
- **MCP server improvements.** Various correctness and ergonomics improvements to the bundled MCP tools.

## 0.1.0

Released **2026-05-20**.

First release in the `0.1.x` line.

### Added

- **Experimental WASM debugging.** Set breakpoints, step, and inspect local variables when running a module through Wasmtime or WAMR. Requires **LLVM 22.1 or newer**; uses `lldb` under the hood. Works only with targets debuggable through `lldb` (typically Rust, C/C++, and Emscripten builds with debug symbols). Supports debugging nested modules inside Component Model binaries (Wasmtime only). Breakpoints resolve across multiple source languages.
- **Runtime picker for Run / Debug.** Choose between **Wasmtime**, **WAMR**, and **GraalVM** when launching a module. The Run / Debug dialog is always shown so you can pick the runtime and tweak arguments before execution.
- **WAMR runtime support** for both running and debugging modules.
- **GraalVM runtime support** with built-in detection of an installed GraalVM. Native-access warnings suppressed during execution.
- **MCP server integration.** Hexana now ships a Model Context Protocol server that AI tools (Claude Desktop, Claude Code, Codex, etc.) can connect to for inspecting the currently open WASM file. The server is **downloaded on demand** from GitHub Releases on first use; subsequent launches re-use the cached install. Stale download locks are detected and recovered automatically.
    - **`Hexana: Reinstall MCP Server` command** — re-runs the download to recover from a corrupted install or pin to a new release tag.
    - **`hexana.mcp.javaHome` setting** — point Hexana at a specific Java runtime when `JAVA_HOME` / `PATH` do not surface a suitable JDK. Java 21 or newer is required for the MCP server to start.
- **Submodule → parent backreference.** When you open a nested module inside a component, the editor toolbar shows a clickable link back to the containing component's editor tab. (Shared with the JetBrains plugin.)

### Changed

- **VS Code requirement raised to `^1.102.0`** to take advantage of the platform's stable MCP-server-registration API.
- **Run / Debug dialog is now always shown** before launching a module — previously it was sometimes auto-dismissed on simple cases.
- **Run-configuration logic deduplicated** between the VS Code extension and the JetBrains plugin; both products now share the same Kotlin core for command-line construction.
- **Display name** on the marketplace remains `Hexana WebAssembly and Hex Viewer`; homepage URL points at `https://jetbrains.github.io/hexana/`.

### Fixed

- WAMR debugging stability fixes.
- Path resolution for debug sessions (mapping source paths to compiled units).
- Breakpoint resolution for nested-module scenarios.
- Sorting-arrow rendering glitch in the analysis tables.
- Various UI-test flakes; timeouts increased and editor-ready waits tightened.

## 0.0.2

### Added

- **Setting to specify a custom Wasmtime path** (`hexana.wasmtimePath`) — for cases where Wasmtime is not on `PATH` or you want to pin to a specific build.
- **Setting to disable statistics collection** (`hexana.enableStatistics`) — independent of VS Code's global telemetry toggle; both must be on for any event to be sent.
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

Contract changes (settings, editor URIs, MCP transport) may still happen between minor versions while the extension stabilises; we will call them out explicitly in this changelog. The `0.0.x` line is closed.

## Compatibility

| VS Code version | Compatible? | Notes |
|---|---|---|
| 1.102+ | ✓ | Required for 0.1.0 and later (MCP-server registration API). |
| 1.85 – 1.101 | ✓ for 0.0.x only | 0.0.1 and 0.0.2 still work on 1.85+. |
| 1.84 and older | ✗ | Missing Custom Editor + Webview APIs. |

VS Code forks (Cursor, Code OSS / VSCodium, Windsurf, Continue.dev) are supported as long as they expose the equivalent VS Code APIs at the listed version. Some terminal- or filesystem-provider-dependent features may behave differently across forks.

### Runtime requirements

- **[wasmtime](https://wasmtime.dev/)** (optional) — needed for Run / Debug via Wasmtime.
- **[WAMR](https://github.com/bytecodealliance/wasm-micro-runtime)** (optional) — needed for Run / Debug via WAMR.
- **[GraalVM](https://www.graalvm.org/) with GraalWasm** (optional) — needed for Run via GraalVM.
- **[wasm-tools](https://github.com/bytecodealliance/wasm-tools)** or **[wac](https://github.com/bytecodealliance/wac)** (optional) — used for component composition when running Component Model binaries with unresolved imports.
- **Java 21 or later** (optional) — needed for the Hexana MCP server. The server is downloaded on demand; set `hexana.mcp.javaHome` if Java is not available through `JAVA_HOME` or `PATH`.
- **LLVM 22.1 or newer** (optional) — required for the experimental WASM debugging path.

## Distribution channels

- **Visual Studio Marketplace** — `marketplace.visualstudio.com/items?itemName=JetBrains.hexana-wasm`.
- **Open VSX** — `open-vsx.org/extension/JetBrains/hexana-wasm`.
- **GitHub Releases** — `.vsix` artefacts attached to each release tag.

## See also

- [`getting-started.md`](getting-started.md), [`features.md`](features.md), [`run-support.md`](run-support.md), [`settings.md`](settings.md).
- The JetBrains plugin's [changelog](../jetbrains/changelog-0.10.md).