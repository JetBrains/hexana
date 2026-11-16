---
title: Hexana — JetBrains Marketplace Listing Draft (0.9)
description: Draft long-form description, metadata, search keywords, and screenshot plan for the Hexana listing on plugins.jetbrains.com.
version: "0.9"
audience: marketplace-listing-owner
target-surface: plugins.jetbrains.com web console (Markdown editor)
---

# Marketplace Listing Draft — Hexana 0.9

This file is the source-of-truth Markdown for the Hexana listing on `plugins.jetbrains.com`. The web-console description editor accepts Markdown; paste the **Long-form description** section below into it. The HTML-only `<description>` CDATA in `plugin.xml` is a separate surface and is **not** updated by this file.

> **Audience routing**: paste-target is the marketplace web console. For `plugin.xml` content and `<change-notes>`, route to the `marketplace-listing-expert` agent. For per-feature documentation, see the sibling files in this directory.

---

## 1. Short description (≤ 170 chars)

Used by the IDE Plugin Manager card and as the Twitter / OpenGraph description fallback.

> WebAssembly and binary analysis for IntelliJ-based IDEs — `.wasm` viewer, WAT and WIT support, MCP tools for AI assistants, run and debug on Wasmtime, WAMR, GraalVM.

**Char count**: 168.

---

## 2. Long-form description (paste into web console)

```markdown
# Hexana — WebAssembly & Binary Analysis for JetBrains IDEs

**Hexana** is an IntelliJ Platform plugin by JetBrains for inspecting, editing, and running WebAssembly binaries. Open any `.wasm` file and Hexana replaces the default editor with a structured, multi-tab view: module summary, imports, exports, functions, type signatures, size profiler, byte-level hex, and rendered WAT. Plus WIT language support, an MCP server for AI assistants, and run / debug configurations across Wasmtime, WAMR, and GraalVM.

## What you can do with Hexana

- **Open `.wasm` files as a structured document** — sections, imports, exports, functions, types, memories, globals, element segments, and data segments are all browsable as sortable, searchable tables.
- **Read the binary as WAT** — Hexana renders WebAssembly Text with offsets, syntax highlighting, search, and clickable hex cross-references. Reference-types, bulk-memory, legacy exception handling, and GC instructions are all supported.
- **Edit `.wit` files with full IDE support** — parser, semantic highlighter, brace matcher, code folding, breadcrumbs, formatter, keyword and `@gate` completion, five inspections, find-usages, rename, Goto Symbol, and cross-file resolve through a dedicated component index.
- **Run and debug WebAssembly modules** — Hexana ships a `WasmRunConfigurationType` with first-class support for **Wasmtime**, **WAMR**, and **GraalVM** (custom installations or built-in). Experimental debugging works with Wasmtime and WAMR (requires LLVM 22.1+); set breakpoints in the WAT view, step through code, inspect locals via DWARF.
- **Detect WASM proposals automatically** — Threads, SIMD, GC, Tail Call, Exception Handling (including Legacy EH), Reference Types, Bulk Memory, Multi-Value, and Component Model usage is detected from the binary and converted into the correct `--wasm-features` flags per runtime.
- **Drive Hexana from your AI assistant** — Hexana exposes **17 MCP tools** (`summarize_module`, `list_imports`, `list_exports`, `list_functions`, `get_instructions_for_functions`, `list_data`, and more) through the platform Model Context Protocol server. Connect Claude Desktop, Claude Code, Cursor, or Continue to ask questions about the loaded module in natural language.
- **Get warned at write time — JS, TS, and Java** — JavaScript and TypeScript code that calls `WebAssembly.instantiate(...)` gets imports completion in the second argument and real type inference on `.instance.exports.<name>`, driven by Hexana parsing the referenced `.wasm`. Java code that uses **GraalWasm** (`org.graalvm.polyglot`) or **Chicory** (`com.dylibso.chicory`) gets string-literal completion for export and import names plus four inspections that flag mismatched names, argument counts, and argument types.
- **Inspect DWARF debug info** — Hexana parses DWARF v4 and v5 sections embedded in `.wasm` custom sections (`.debug_str`, `.debug_abbrev`, `.debug_info`, `.debug_line`) and uses them to map between WAT, source files, and the debugger.
- **Profile binary size** — the **Top** tab lists the largest functions, data segments, and sections by byte size, sortable and scrollable, so you can see where your binary's bytes actually go.

## Who it's for

- Developers shipping WebAssembly from **Rust**, **C/C++**, **Kotlin/WASM**, **Emscripten**, **Go**, or **Zig** — Hexana shows you what your toolchain actually produced.
- Engineers building **Component Model** components, where WIT support is essential.
- Reverse engineers and security researchers inspecting unfamiliar `.wasm` payloads.
- **Web and Node.js teams** loading WASM modules via `WebAssembly.instantiate` — Hexana makes `instance.exports` actually typed.
- Java teams using **GraalWasm** or **Chicory** who want IDE feedback on their host-language interface.
- AI-augmented developer workflows that need a programmable view into WASM binaries via MCP.

## Supported IDEs

IntelliJ IDEA 2024.1+, RustRover, WebStorm, CLion, PyCharm, Rider, PhpStorm — anywhere on IntelliJ Platform 2024.1+ that bundles or installs the **MCP server** plugin.

A companion **VS Code** extension ships separately on the Visual Studio Marketplace and Open VSX.

## Getting started

1. Install Hexana from the IDE's **Marketplace** tab.
2. Open any `.wasm` file. Hexana indexes it and presents the multi-tab editor.
3. To run, open **Settings → Build, Execution, Deployment → WASM Runtime** and point Hexana at a runtime (Wasmtime, WAMR, or GraalVM). Then click **Run** in the editor's information bar.
4. To explore via AI, connect your MCP-speaking assistant to the IDE's bundled MCP server. Hexana's 17 tools become available the moment a `.wasm` is loaded.

## What's new in 0.9 (2026-05-07)

- **Experimental WASM debugging** with Wasmtime and WAMR (requires LLVM 22.1+, uses `lldb`).
- **WAMR runtime support** for run and debug.
- **Custom GraalVM home directory** option.
- **Information bar** with file size, module kind, and Run / Debug buttons.
- **Top tab** UX improvements — column headers, sorting, scrolling.
- **Java integration**: completion and inspections for [Chicory](https://github.com/JetBrains/hexana/issues/22) and [GraalWasm](https://github.com/JetBrains/hexana/issues/20).
- **Nested-module backreference** for components.

See the full changelog under **Versions** for prior releases.

## Links

- [Repository](https://github.com/JetBrains/hexana)
- [Issue tracker](https://github.com/JetBrains/hexana/issues)
- [Documentation](https://github.com/JetBrains/hexana/tree/master/idea-plugin/doc)
- [VS Code companion](https://marketplace.visualstudio.com/items?itemName=JetBrains.hexana)
```

**Word count of pasted block**: ~530. Within the marketplace's comfortable range for the long description (~300–1000).

---

## 3. Marketplace metadata

### Plugin name
**Current**: `Hexana`.
**Recommendation**: keep `Hexana` as the formal name. The brand-name SEO problem (`Hexana` does not contain "WASM" or "WebAssembly") is real but solvable through tags, search keywords, and the description rather than a rename. If a rename ever becomes acceptable, the strongest candidates are:
- `Hexana — WebAssembly`
- `Hexana: WASM & Binary Analysis`

Both keep the brand and pick up the high-volume capability terms. Confirm with `marketplace-listing-expert` before changing — JB marketplace search ranks `<name>` heavily, so a rename has a measurable upside.

### Vendor
`JetBrains`. Already correct in `plugin.xml`. Keep — the JetBrains-verified vendor badge significantly improves install conversion.

### Categories (pick 1 primary, up to 2 secondary)

JetBrains Marketplace categories ([reference](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html#plugin-categories)):

| Slot | Recommendation | Rationale |
|---|---|---|
| **Primary** | `Tools Integration` | The dominant frame — Hexana integrates a WASM toolchain (runtimes, MCP server, parsers) into the IDE. Best discovery match for "wasm tools intellij" intent. |
| **Secondary 1** | `Editor` | The hex view, WAT renderer, and WIT language make Hexana a content editor as much as a tool. |
| **Secondary 2** | `Code Tools` | Catches users browsing for static analysis / inspections (the WIT and Java inspections live here). |

Avoid: `Frameworks`, `Build`, `VCS Integration` — wrong frame for the listing.

### Tags (up to 15; JB Marketplace search-relevant)

Order matters for display; first 5–7 carry the most weight.

1. `webassembly`
2. `wasm`
3. `wat`
4. `wit`
5. `binary-analysis`
6. `hex-editor`
7. `dwarf`
8. `component-model`
9. `mcp` *(Model Context Protocol — emerging high-intent term)*
10. `ai-tools`
11. `debugger`
12. `disassembler`
13. `reverse-engineering`
14. `graalwasm`
15. `chicory`

### Search keywords (free-text field in the web console)

Helps the marketplace's internal search but **not visible to users**, so be liberal with synonyms without polluting the visible description.

```
webassembly, wasm, .wasm, web assembly, wat, .wat, wit, .wit,
component model, binary editor, hex editor, hex viewer, hex view,
binary analysis, reverse engineering, decompile wasm, disassemble wasm,
dwarf, debug info, debug symbols,
wasm debugger, wasm debug, wasm debugging, debug wasm in intellij,
wasm runtime, wasmtime, wamr, graalwasm, graal wasm, graal vm wasm,
emscripten, rust wasm, cargo wasm, zig wasm, c++ wasm, go wasm, kotlin wasm,
mcp, model context protocol, ai tools wasm, ai-assisted wasm,
chicory, dylibso chicory,
intellij wasm plugin, vscode wasm extension,
threads, simd, gc, exception handling, tail call, reference types,
bulk memory, component-model, multi-value, custom page sizes,
profile wasm size, wasm size profiler, wasm bloat,
elf, pe, coff, llvm-objdump,
hexana, jetbrains hexana
```

### Plugin icon

**Current state (per the `marketplace-listing-expert` agent notes)**: `pluginIcon.svg` / `pluginIcon_dark.svg` are not present at `idea-plugin/src/main/resources/META-INF/`. Without these, the marketplace falls back to a generic puzzle-piece icon — a top-3 reason users skip a listing.

**Action**: ship a 40×40 SVG icon at `pluginIcon.svg` plus a dark variant at `pluginIcon_dark.svg` before the next release. Route to `marketplace-listing-expert` for the asset itself. Suggested visual direction: monospace `0a` (the WASM magic-byte signature) framed in a hex digit; works at 40×40 and 80×80.

### Repository / Documentation URLs

Wire these into the listing's metadata fields:

| Field | URL |
|---|---|
| **Source code** | `https://github.com/JetBrains/hexana` |
| **Issue tracker** | `https://github.com/JetBrains/hexana/issues` |
| **Documentation** | `https://github.com/JetBrains/hexana/tree/master/idea-plugin/doc` |
| **Discussion / forum** | *(optional; only if a forum exists)* |

### Pricing

**Free**. Confirm in the web console.

### Compatibility

- **Since-build**: read from `gradle.properties` → `defaultSinceBuild`. As of 0.9 this targets IntelliJ Platform 2024.1+ (`241.*`). Confirm before publishing.
- **Until-build**: leave open (`242.*`+) unless verifying for a specific newer build introduces incompatibilities.
- **Product family**: IntelliJ IDEA, RustRover, WebStorm, CLion, PyCharm, Rider, PhpStorm — all IntelliJ Platform IDEs.

---

## 4. Screenshot plan + captions

The marketplace lets you upload up to ~10 screenshots, displayed as a carousel near the top of the listing. **First two matter most** — users see them in search-result previews and on the listing without scrolling.

Recommended order (most marketing-effective first):

### Screenshot 1 — the multi-tab `.wasm` editor

**What to capture**: Hexana editor with a real-world `.wasm` file open (Skiko works well — large, recognisable to Kotlin/Compose developers). Show the information bar at top (file size, module kind, Run button), tab strip (Module / Imports / Exports / Functions / Top / Hex / WAT), and the **Functions** tab body with the sortable table and search field visible.

**Caption**: *Open any `.wasm` file as a structured, searchable document — sections, imports, exports, functions, types, memory, globals, and data segments.*

### Screenshot 2 — WAT rendering with hex cross-reference

**What to capture**: WAT tab open on the same file, syntax-highlighted, line offsets visible. In a side-by-side or annotated view, the **Hex** tab below or adjacent showing the corresponding byte range. Click-through arrow optional.

**Caption**: *Hexana renders WebAssembly Text with offsets, search, and clickable hex cross-references. Reference-types, bulk-memory, Legacy EH, and GC instructions are all rendered.*

### Screenshot 3 — WIT language support

**What to capture**: A `.wit` file from a real Component-Model project. Show: semantic highlighting, breadcrumb at top, an inspection warning in the gutter (e.g. missing-semicolon WARNING), Goto Symbol popup open on a WIT symbol. Demonstrates "real language support, not a viewer".

**Caption**: *Full IDE support for `.wit` files — parser, semantic highlighting, code folding, formatter, keyword completion, 5 inspections, Find Usages, Rename, and cross-file resolve.*

### Screenshot 4 — Run / Debug configuration

**What to capture**: The IDE's Run / Debug toolbar with a WASM run configuration selected, plus the WAT view in debug mode with a breakpoint set and the **Variables** panel showing locals via DWARF. Wasmtime or WAMR selected in the configuration.

**Caption**: *Run and debug WebAssembly modules on Wasmtime, WAMR, or GraalVM — set breakpoints in the WAT view, step through code, inspect locals through DWARF.*

### Screenshot 5 — Top tab (size profiler)

**What to capture**: The **Top** tab open on a non-trivial `.wasm` file, sorted by size descending, showing the largest functions and data segments.

**Caption**: *See where your binary's bytes actually go. The Top tab profiles function and data-segment sizes for quick "why is my `.wasm` 8 MB?" answers.*

### Screenshot 6 — MCP-driven exploration

**What to capture**: Claude Desktop (or Claude Code) in the foreground asking a natural-language question about a loaded `.wasm`, with the response showing one of Hexana's MCP tools (e.g. `list_exports`) being called. The IDE in the background with the same `.wasm` open. Demonstrates the AI integration story concretely.

**Caption**: *17 Model Context Protocol tools let AI assistants explore a loaded WASM binary — `list_functions`, `summarize_module`, `get_instructions_for_functions`, and more.*

### Optional Screenshot 7 — Java integration

**What to capture**: A Java source file using `Instance.builder(...)` (Chicory) or `Context.eval(...)` + `invokeMember(...)` (GraalWasm), with Hexana's completion popup open inside the export-name string literal, AND an inspection warning highlighting a typo in another export name elsewhere in the file.

**Caption**: *Java callers of GraalWasm or Chicory get string-literal completion and four inspections that catch mismatched export names, argument counts, and types — at write time, not at run time.*

### Capture conventions

- **Resolution**: 1920 × 1200 minimum (the marketplace upscales smaller images). 2880 × 1800 retina is ideal.
- **IDE theme**: capture both **Darcula** and **Light** versions of screenshots 1 and 2; let the marketplace adapt. Single-theme is acceptable for the remaining shots.
- **Font**: JetBrains Mono. The IDE's default editor font.
- **Window chrome**: macOS chrome reads as the most JetBrains-native; Windows or Linux acceptable if your assets are already captured there.
- **Annotations**: minimal. The marketplace renders images as-is — over-annotated screenshots read as marketing-deck slides.

---

## 5. Optional follow-ups (out of scope but adjacent)

These are recommendations that didn't fit the requested surfaces but are worth queueing before the 0.9 listing goes live:

- **Plugin icon SVG**: per Section 3, ship `pluginIcon.svg` + `pluginIcon_dark.svg`. Route to `marketplace-listing-expert`.
- **`<change-notes>` plumbing**: wire `org.jetbrains.changelog` Gradle plugin to render `<change-notes>` from `idea-plugin/CHANGELOG.md` automatically; the listing's *Versions* tab on the marketplace reads this.
- **Short video / GIF**: a 30-second screen recording showing "open `.wasm` → see WAT → set breakpoint → run on Wasmtime → step through" lifts conversion meaningfully. Marketplace supports a single embedded video.
- **AEO landing pages**: the comparison pages flagged by `aeo-expert` (Hexana vs. wabt, vs. wasm-tools, vs. Binary Ninja) live on a docs site, not the marketplace, but a link from the marketplace listing to those pages helps both surfaces.

---

## Source

This file is the marketplace-listing draft for **release 0.9** built from branch `release/0.9` commit `6a108799`. When 0.10 ships, copy and revise this file rather than overwriting — the diff between listing versions is itself a useful artifact.
