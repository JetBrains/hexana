---
title: Hexana 0.9 Release Notes
description: Pinned release notes for Hexana 0.9 (2026-05-07).
version: "0.9"
released: 2026-05-07
---

# Hexana 0.9 Release Notes

Released **2026-05-07**. Source of truth: [`idea-plugin/CHANGELOG.md`](../CHANGELOG.md) at commit `6a108799` on branch `release/0.9`.

## What's new in 0.9

### Added

- **Experimental WASM debugging.** Set breakpoints in the WAT view and step through execution using Wasmtime or WAMR. Requires LLVM 22.1 or newer; uses `lldb` under the hood. Works only with targets that are debuggable through `lldb` (typically Rust, C/C++, and Emscripten builds with debug symbols). See [`run-and-debug.md`](run-and-debug.md).
- **WAMR runtime support** — added as a third runtime option alongside Wasmtime and GraalVM. WAMR supports both run and debug.
- **Custom GraalVM home directory** — Settings → Build, Execution, Deployment → WASM Runtime now accepts a path to an external GraalVM installation that includes GraalWasm. Previously only the bundled Graal was used.
- **Information bar above the editor** showing the file size (hover for per-section breakdown), module kind (core vs. component), and Run / Debug buttons.
- **'Top' tab UX improvements** — column headers, sortable columns, and scrolling.
- **Nested-module back-reference** — when you open a `.wasm` that is a nested module inside a component, the information bar shows a link back to the containing module.
- **Java completion and inspections for Chicory** ([#22](https://github.com/JetBrains/hexana/issues/22)) — `com.dylibso.chicory.*` API. See [`java-integration.md`](java-integration.md).
- **Java completion and inspections for GraalWasm** ([#20](https://github.com/JetBrains/hexana/issues/20)) — `org.graalvm.polyglot.*` API. See [`java-integration.md`](java-integration.md).

## Recent prior releases (for context)

### 0.8.2 — 2026-04-30

Added:
- Legacy Exception Handling support ([#85](https://github.com/JetBrains/hexana/issues/85)) — `try`, `catch`, `throw`, `rethrow`, `delegate`, `catch_all`.
- WAT / MCP rendering for reference-types and bulk-memory instructions.

Fixed:
- Run configurations now work on Windows.
- WASM parser fixes for vector and table sections.
- Element segment type 6 reads the reference-type per WebAssembly 3.0 §5.5.12 (clarified 2026-04-24).
- Data race on shared `CommonByteBuffer` that caused sporadic `UnParsedOpcodeException`.

### 0.8.1 — 2026-04-23

- Minor bug fixes and improvements.

### 0.8 — 2026-04-21

Added:
- Code-Size Profiler for WebAssembly Binaries ([#70](https://github.com/JetBrains/hexana/issues/70)).
- DWARF section detection and parsing ([#49](https://github.com/JetBrains/hexana/issues/49)).
- Explorer integration ([#52](https://github.com/JetBrains/hexana/issues/52)).
- Source mapping via DWARF ([#48](https://github.com/JetBrains/hexana/issues/48)).
- JS interop — code completion and type inference for `instance.exports` ([#18](https://github.com/JetBrains/hexana/issues/18)).
- JS interop — code completion of namespaces and property names for imports ([#17](https://github.com/JetBrains/hexana/issues/17)).
- Run configurations for WASM — run with Wasmtime or GraalVM ([#69](https://github.com/JetBrains/hexana/issues/69)).

Changed:
- MCP tool descriptions optimised.

Fixed:
- [IJPL-242167](https://youtrack.jetbrains.com/issue/IJPL-242167) IAE: `WasmModuleKt.fromSections` via `WasmIndex`.
- [ClassCastException in `WitStaticFuncItemMixin`](https://github.com/JetBrains/hexana/issues/84).

### 0.7.1 — 2026-04-09

Fixed:
- [IJPL-242167](https://youtrack.jetbrains.com/issue/IJPL-242167) IAE: `WasmModuleKt.fromSections` via `WasmIndex`.
- WASM/WAT files via http (local debug scenario) ([#79](https://github.com/JetBrains/hexana/issues/79)).
- Unbalanced tree in WAT parsing ([#78](https://github.com/JetBrains/hexana/issues/78)).
- Empty ranges in WIT folding ([#80](https://github.com/JetBrains/hexana/issues/80)).
- Editor control in the WAT tab now handles IDE zoom ([#81](https://github.com/JetBrains/hexana/issues/81)).
- Text selection in both hex and text panels with arrow-key support ([#82](https://github.com/JetBrains/hexana/issues/82)).
- Big WAT files displayed in an editor with line numbers, selection, search, scrolling ([#83](https://github.com/JetBrains/hexana/issues/83)).

### 0.7 — 2026-03-31

Added:
- WAT offsets-based line numbers ([#35](https://github.com/JetBrains/hexana/issues/35)).
- Search in table view for imports / exports / functions ([#7](https://github.com/JetBrains/hexana/issues/7)).

Changed:
- WIT: basic editing experience (keyword completion, code formatting).
- Table UX improvements for imports/exports/functions: arrow-key navigation, scrolling, layout fixes.

Fixed:
- KDoc issues when Hexana is enabled ([#67](https://github.com/JetBrains/hexana/issues/67)).
- Shared limit support for memory type added.
- NIE on reading Go WASM module ([#77](https://github.com/JetBrains/hexana/issues/77)).

## Upgrading to 0.9

No breaking changes from 0.8.x.

- Run configurations created with 0.8 continue to work; you may want to set WAMR as your default runtime if you need debug support.
- If you have a custom GraalVM installation you previously couldn't use, configure it in **Settings → Build, Execution, Deployment → WASM Runtime**.
- The Java-side completion features activate automatically in IDEs that include the Java module.

## Known limitations in 0.9

- Debug requires LLVM 22.1 or newer and works with Wasmtime + WAMR only. GraalVM debug is not yet supported.
- Java integration covers Java sources only. Kotlin source support is not yet wired.
- Quick-fixes are not yet wired on the WebAssembly Java inspections or the WIT inspections.

## Source

- Branch: `release/0.9`.
- Tag: see `git tag | grep 0.9`.
- Commit: `6a108799` ([release]: Release 0.9, 2026-05-07).
- Authoritative changelog: [`idea-plugin/CHANGELOG.md`](../CHANGELOG.md).
