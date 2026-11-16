---
title: Hexana for VS Code — Release Notes
description: Release notes for the Hexana VS Code extension.
version: "0.13.0"
---

# Release Notes — Hexana for VS Code

This page tracks releases of the VS Code extension.

## 0.13.0

Released **2026-10-01**.

### Added

- **Hex diff.** The diff view gains a second panel next to the size and functions comparison. Both panels are switchable at the top of the diff view. The hex diff shows the two binaries side by side with changed bytes highlighted (amber modified, red removed, green added), long unchanged stretches collapsed into expandable "N unchanged rows" lines, previous / next difference buttons, and section bars tinted in colors that do not clash with the diff highlighting. One section bar covers both sides and marks which sections changed; clicking jumps to that section. The table tabs (Headers, Sections, Symbols, Imports, and others) are diffed row by row below the hex panels. Works for any pair of supported binaries; a pair of different kinds gets the byte comparison alone.
- **VS Code "Compare Selected" integration.** Right-clicking two binaries Hexana opens and choosing "Compare Selected" now opens Hexana's diff, starting on the hex diff panel. The "Hexana: Compare Files" command opens the diff starting on the size and functions panel, as before.
- **Unified "Hexana: Compare Files" command.** "Compare WASM" and "Compare Binary" are merged into one command that selects the comparison mode from the files' contents. Existing keybindings to the old command names keep working.
- **Functions diff for ELF, Mach-O, and COFF.** Comparing two ELF, Mach-O, or COFF files adds a "Functions" tab: functions are paired by name (renames are recognized by identical code), and selecting one shows the two versions' bytes side by side. PE images and archives keep the section size comparison only.
- **Component Model diff.** "Compare WASM" gains a "Dispatch" tab (per-signature `call_indirect` candidates that entered or left the table) and Component Model comparison: submodules are paired, and clicking one opens its own comparison with a "Back" link.
- **Searchable and sortable comparison tables.** All comparison tables support search and sort, and every tab loads in the background so a large binary does not freeze the view during comparison.

## 0.12.0

Released **2026-09-23**.

### Added

- **"Reopen Editor With" for any file.** "Hexana Binary Editor" is now offered for any file under "Reopen Editor With", so a binary with an extension Hexana does not claim (an Arrow stream saved as `.stream`, a firmware image as `.dat`) can be opened in it on request. Files Hexana claims keep opening automatically.
- **Apache Parquet and Apache Arrow IPC support.** Schema, Row Groups / Record Batches, Metadata, and Data tabs, matching the IntelliJ plugin.
- **Tar archive support.** `.tar` archives and gzipped archives (`.tar.gz`, `.tgz`) list entries, which can be opened in their own tab, including entries that are themselves gzipped.
- **Protobuf wire message support.** Opaque Protobuf wire messages (`.pb`) are decoded without a schema.
- **Arrow IPC streams (`.arrows`).** Arrow IPC streams open as streams; previously every `.arrow` file was read as an IPC file, so a stream showed no tabs.
- **Content-based opening.** Binaries whose name says nothing about their format open by content: an extensionless WebAssembly module, Parquet, or Arrow export gets its full editor instead of a hex dump.

## 0.11.0

Released **2026-09-16**.

### Added

- **DWARF tab group.** `.debug_abbrev`, `.debug_info`, and `.debug_line` are grouped under a single "DWARF" tab with search; clicking a row selects its bytes.
- **Navigable component dependency graph.** The Dependencies graph for WASM components is now navigable: double-click a module or nested component to open it, or an import/export to select its bytes.
- **Script tabs (revised API).** Format-agnostic `binary` API: `format`, `size`, `path`, `dir`, `structure`, `strings`, `sizeAnalysis`, `binary.tabs` / `binary.tab(name)`, plus `table(...)` / `row(...)` output. `open(path)` parses another file into the same members as `binary` so a script can compare two files; call `open` at the top level of the script rather than inside a function.
- **PCAP TLS decryption.** Support for decrypting TLS traffic in PCAP files based on a user-provided SSL key log file.
- **Smali tab.** A "Smali" tab for Android `.dex` files shows baksmali-style Dalvik bytecode with resolved operands; clicking a row selects the code unit in the hex view.
- **Android resource tables.** Basic support for viewing Android binary resource tables (`resources.arsc`).
- **Runtime compatibility check.** Declare target runtimes via "Hexana: Configure Target Runtimes" and see unsupported proposals in the editor header and a new "Compatibility" tab. Configuration is shared with the IntelliJ plugin via `.hexana/runtimes.json`.

## 0.10.0

Released **2026-08-26**.

### Added

- **Wasmtime gdbstub debugging (wasmtime 44+).** The module runs under `wasmtime run -g` and LLDB attaches through the wasm process plugin. DWARF is read directly from the wasm binary, removing the JIT-loader and trap-advance workarounds of the native path. When the resolved wasmtime is older than 44, the extension also checks wasmtime's own installer location (`~/.wasmtime/bin`), and only then falls back to native debugging with a visible warning.
- **Source-level stepping in wasm debug sessions (nodejs and browser runtimes).** A step now repeats V8's per-instruction step until the DWARF source line or stack depth changes, instead of pausing on every instruction of the same line. Frames without DWARF info keep single-instruction steps.
- **Disassembly View integration.** "Open Disassembly View" while paused in a nodejs or browser wasm debug session shows one WAT-style instruction per row with module-absolute byte offsets as addresses and interleaved DWARF source locations. While the view is focused, stepping switches to per-instruction granularity.
- **"Wasm" debugger view.** "Hexana: Open Wasm View" in the bottom panel (next to the Debug Console) mirrors the IntelliJ Wasm tab: WAT-style disassembly of the paused function with the current instruction highlighted on the left, and V8's operand stack (slot, type, value) on the right. While the view is visible each step moves one wasm instruction; otherwise stepping stays source-line based. nodejs and browser wasm sessions only. The view also works for wasmtime and WAMR debug sessions ("Hexana: Open WebAssembly Disassembly"): under the gdbstub (wasmtime 44+, WAMR) the paused instruction is highlighted exactly from the frame's program counter.
- **Operand stack for wasmtime and WAMR.** Slot types and origins come from static reconstruction of the paused function; concrete values are read from the debug stub via the wasmtime 44+ gdbstub stack accessor, or for WAMR via its locals/globals packets for slots that provably mirror a local or global.
- **Component Model wasm view.** The "Wasm" debugger view resolves disassembly, current-instruction highlight, and operand stack against the embedded core module the debug stub reports, instead of assuming a single core module.
- **Managed wasi-sdk downloads.** "Hexana: Download wasi-sdk..." lists releases from GitHub (sizes shown, sha256-verified, cancellable) and installs versions side by side into the extension's global storage. "Hexana: wasi-sdk Status" shows the active version with update and version-switch actions. The SDK takes effect only via an explicit "Use as LLDB path" button that writes `hexana.lldbPath`.
- **Problems tab for WASM binaries.** Lists regions the parser could not decode (unknown opcodes, unsupported proposal constructs) with location, offset, and message; clicking a row jumps to the function in the WAT tab.
- **Problems tab for WASM Component binaries.** Core-instance entries the parser cannot decode are listed with offset and message instead of breaking the component dependency graph.
- **Conditional breakpoints (nodejs runtime).** Conditions are evaluated by V8; expressions referencing wasm-level names work directly.
- **Inlined call frames.** Inlined frames in the debug stack trace, reconstructed from DWARF and marked "(inlined)"; variables and evaluation stay on the physical frame.
- **Linear-memory variable decoding.** Variables stored in linear memory (structs, arrays, enums; typical for unoptimized builds) are located via DWARF, read from the module's memory, and decoded per type in the Variables view when debugging with nodejs.
- **Basic PCAP / PCAPng support.** View packets and open HTTP response bodies in a new tab.
- **Basic tar archive support.** View tar entries directly in Hexana.
- **Gzip support.** Compressed files of supported kinds (such as `.wasm.gz`) can be opened directly.

### Fixed

- **GCC-built ELF DWARF line resolution.** The "Debug Info" tab now resolves source line rows for GCC-built ELF binaries (Zephyr and similar firmware, DWARF4 and DWARF5 alike). GCC emits DWARF v2/v3 line-program headers even in `-gdwarf-4/5` builds; the line parser previously rejected them. Address-to-line resolution now matches `addr2line`.
- **Custom-descriptors proposal parsing.** Parsing support for the custom-descriptors proposal type encodings (exact reference types, descriptor/describes clauses). WasmGC modules using them previously failed to parse.
- **Partial decode retained on undecodable instruction.** Function bodies with a single undecodable instruction now keep everything decoded up to that point instead of turning into one unparsed blob.
- **Wrong file on pause fixed for shared source names.** Fixed the wrong file opening on pause or breakpoints when several crates share a source file name; DWARF paths now match by longest path suffix instead of base name.

## 0.9.0

Released **2026-08-05**.

### Added

- **Size diagram ("Top" tab) for wasm and native binaries.** Shows the largest-by-size entities.
- **Nested component support.** Nested components of a WASM Component open in their own view from the "Components" tab, like embedded core modules. Opening works recursively for components inside components.

### Changed

- **Improved large-table performance.** Optimized rendering of large table views.

## 0.8.0

Released **2026-07-29**.

### Added

- **Semantic section highlighting and navigation.** Sections in WASM and native binary files are now highlighted by kind, and you can jump between them directly from the editor.
- **WASM Component dependency diagram.** Component Model binaries gain a **Dependencies** tab — a graph view showing the dependency relationships among the modules and linkages inside a component. The tab is available for component binaries only.
- **"Save as" for nested modules and archive entries.** Nested WASM modules and entries inside JAR / ZIP archives can now be saved out to a separate file with the **Save as** action.

## 0.7.0

Released **2026-07-22**.

### Added

- **WASM file diff.** A new action compares two WASM files — a **Size Impact** view with the total size delta and per-section, per-entity byte changes, an **Entities** view, and, for functions, a WAT body diff for the selected function.
- **Native binary diff.** A separate action compares two native binary files (ELF, Mach-O, PE), reporting size changes per section.
- **Data inspector panel.** Selecting a byte in the hex viewer now populates a **data inspector** panel showing different representations of the selected byte.

### Fixed

- **Archive and nested-module content restored after reopen / restart.** Archive files and nested WASM modules now properly restore their content when an editor tab is reopened or VS Code is restarted.

## 0.6.1

Released **2026-07-15**.

### Added

- **Virtualized WAT for Component Model binaries.** Component-model binaries now use the inline virtualized WAT view for their embedded core modules — the same virtual-scrolling renderer introduced for core modules in 0.5.0. When a component contains multiple core modules, a selector at the top of the WAT panel lets you switch between them.

### Changed

- **Faster WASM table tabs.** Improved performance of the table-based analysis tabs, especially the Functions tab on large modules.
- **MCP server packaging update.** The bundled MCP server packaging now uses the split CLI release-product layout.

## 0.6.0

Released **2026-07-08**.

### Added

- **Protocol Buffers descriptor sets.** Compiled `.desc` and `.protoset` files now open in Hexana's custom editor with five dedicated tabs: **Files**, **Messages**, **Fields**, **Enums**, and **Services**.
- **Wazero runtime.** WASM run configurations now include **wazero** as a target for core WebAssembly modules (module execution only — individual function invocation is not supported on the wazero path).
- **Scriptable info tabs.** Custom JS-powered tabs can be added alongside the built-in analysis tabs. Click **"+"** in the tab bar to open a dialog with instructions and an API reference, then write a script that uses `print` to emit content and `binary` to access the parsed binary. Output formatted as CSV or TSV is rendered as a table.
- **Feedback / report-issue button.** A dedicated button makes it easier to share feedback or file a bug without leaving the editor.

### Changed

- **Unified table control with built-in search.** All analysis tables now use the same table component, giving every tab consistent behaviour including a proper built-in search field.
- **Improved table rendering performance.** Table rendering is faster across all analysis tabs.
- **MCP server download from the JetBrains downloads index.** On supported platforms the MCP server is now fetched from the JetBrains downloads index using platform-native artifacts by default. The extension no longer silently switches to GitHub when the downloads metadata is unavailable.

## 0.5.0

Released **2026-06-24**.

### Added

- **Virtualized WAT view.** The WAT tab now uses virtual scrolling for faster section navigation in large modules. Previously the full text was rendered at once; 0.5.0 renders only the visible portion.
- **Java `.class` and Android DEX file viewing.** The custom editor now opens `.class` and `.dex` files directly by file-type detection, in addition to recognising them as entries inside JAR/ZIP archives (0.3.0). Files open in the hex viewer.
- **ZIP64 archive support.** Large archives using the ZIP64 format now open in the Hexana editor.

### Changed

- **NodeJS debugging stability.** Improved reliability of the CDP-backed debug path for Node.js, introduced in 0.4.0.

## 0.4.0

Released **2026-06-16**.

### Added

- **In-place byte editing in the hex viewer.** You can now edit individual bytes directly in the hex viewer — select a byte, type a new value — and write the result back to the original file with the **overwrite-file** action. Editing is **overwrite-only: the file size never changes** (you cannot insert or remove bytes). Earlier versions were inspection-only.
- **Debugging on the Node.js and browser runtimes.** Node.js and the browser — **run-only** since 0.3.0 — are now debuggable. Hexana drives them over the **Chrome DevTools Protocol (CDP)**: Node.js is launched with `--inspect-brk`, Chrome with `--remote-debugging-port`, and Hexana translates DAP ↔ CDP so VS Code's debugger can set breakpoints, step, and inspect state. This complements the existing `lldb`-backed Wasmtime / WAMR debug path (LLVM 22.1+). GraalVM remains run-only.

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
- The JetBrains plugin's [changelog](../jetbrains/changelog-0.11.md).