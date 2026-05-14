---
title: Getting Started with Hexana (0.9)
description: How to install the Hexana IntelliJ plugin, open a WebAssembly file, and find the main views.
version: "0.9"
---

# Getting Started with Hexana

This page covers installation, first launch, and the three main views you will see when you open a `.wasm` file.

## How do I install Hexana?

1. Open your IntelliJ-based IDE (IntelliJ IDEA 2024.1+, RustRover, WebStorm, CLion, PyCharm, Rider, or PhpStorm).
2. Open **Settings → Plugins → Marketplace**.
3. Search for `Hexana` and click **Install**.
4. Restart the IDE when prompted.

Alternative install paths:

- **From disk**: `Settings → Plugins → ⚙ → Install Plugin from Disk…` and pick the `.zip` produced by `./gradlew :idea-plugin:buildPlugin` (output at `idea-plugin/build/distributions/`).
- **Nightly channel**: add the `nightly` channel under `Settings → Plugins → ⚙ → Manage Plugin Repositories` to receive pre-release builds.

After install, Hexana registers four file types: `.wasm`, `.wat`, `.wit`, and a generic binary type covering `.bin`, `.elf`, and `.exe`.

## How do I open a WebAssembly file?

Any of the following will open the file in Hexana's editor:

- Drag a `.wasm` file into the IDE.
- Use **File → Open…** and pick a `.wasm` file.
- Right-click a `.wasm` in the **Project** tool window → **Open**.
- From an external file manager, double-click — the IDE associates `.wasm` with Hexana when it is the only registered handler.

The first time Hexana opens a `.wasm` file, it indexes the module's sections (imports, exports, functions, types, memory, globals, element and data segments, custom sections including DWARF) and logs the open event to FUS (`org.jetbrains.hexana` event group, event `wasm.file.opened`).

## What do I see when I open a file?

Hexana replaces the default editor with a multi-tab view. The tabs are:

| Tab | What it shows |
|---|---|
| **Module** | Header summary, magic bytes, version, section table, custom-section list. |
| **Imports** | All imported items grouped by kind (function, table, memory, global, tag). Click an entry to jump to its hex offset. |
| **Exports** | All exported names with the resolved target. The Goto Symbol search surfaces export names project-wide. |
| **Functions** | All functions with their type signature, local count, and code-section offset. |
| **Top** | Sortable, scrollable table of the largest functions / data segments / sections by byte size — Hexana's size-profiler surface. |
| **Hex** | Byte-level view with annotations for known sections, navigation by offset (`Cmd/Ctrl+L`), and structure popup (`Cmd/Ctrl+F12`). |
| **WAT** | Rendered WebAssembly Text format with offsets, syntax highlighting, search, and (since the editable-binary-documents arc) inline row editing. |

Above the tabs you will see an **information bar** with the file size (hover for a per-section breakdown), the module kind (core module vs. component), and **Run / Debug** buttons when a runtime is configured. When a file you open is a nested module inside a parent component, the bar also shows a back-reference to the containing module.

## How do I run a `.wasm` file?

If you have a WASM runtime configured, click **Run** in the information bar. If you have not yet pointed Hexana at a runtime:

1. Open **Settings → Build, Execution, Deployment → WASM Runtime**.
2. Pick a runtime — Wasmtime, WAMR, or a GraalVM installation that includes GraalWasm.
3. Return to the editor; the **Run** button is now active.

See [`run-and-debug.md`](run-and-debug.md) for details, including the experimental debugger.

## Where are the Hexana actions?

Out of the box, Hexana exposes four actions, all reachable via **Find Action** (`Cmd/Ctrl+Shift+A`):

| Action ID | Default trigger | Behaviour |
|---|---|---|
| `hexana.openInHexView` | Right-click in editor → **Reveal** | Opens the current selection in Hexana's hex view. |
| `hexana.showElementInHexView` | Right-click in editor → **Reveal** | Reveals the PSI element at the caret in the hex view. |
| `hexana.goToOffset` | Shares the **Go to Line** shortcut (`Cmd/Ctrl+L`) | Jumps to a byte offset in the hex view. |
| `hexana.showStructurePopup` | Shares the **File Structure** shortcut (`Cmd/Ctrl+F12`) | Opens the structure popup for the current binary. |

## How do I use Hexana from an AI assistant?

Hexana ships a Model Context Protocol server with 17 tools (e.g. `list_functions`, `list_imports`, `summarize_module`). AI assistants that speak MCP — Claude Desktop, Claude Code, Cursor, Continue — can call those tools to explore the loaded module. See [`mcp-tools.md`](mcp-tools.md) for the tool reference and connection instructions.

## Next steps

- Browse the [`features.md`](features.md) catalogue.
- If your file uses experimental WASM proposals (Threads, GC, Legacy EH, …), see [`run-and-debug.md`](run-and-debug.md) — Hexana detects proposal usage and passes the right `--wasm-features` to the runtime automatically.
- If something doesn't work, check [`troubleshooting.md`](troubleshooting.md).
