---
title: Hexana Feature Reference (0.9)
description: Complete capability reference for the Hexana IntelliJ plugin, grouped by surface.
version: "0.9"
---

# Hexana Feature Reference

This page enumerates every user-visible capability Hexana 0.9 ships. Capabilities are grouped by *surface* (file type or interaction point), not by chronological release. For per-release changes, see [`changelog-0.9.md`](changelog-0.9.md).

## WebAssembly module viewer

When Hexana opens a `.wasm` file, it presents a structured editor with the following tabs.

### Module overview tab
- Magic bytes (`\0asm`) and binary format version display.
- Section table with id, size, and offset for every section.
- Custom-section list, including DWARF (`.debug_*`) and name sections.
- Detection of the module kind: core WebAssembly module vs. Component Model component.
- Backreference to the containing module when the opened file is a nested module inside a component.

### Imports tab
- Imports grouped by kind (function, table, memory, global, tag).
- Resolved type signature per imported function.
- Click an entry to jump to its hex offset in the **Hex** tab.
- Search-as-you-type filter across import names.

### Exports tab
- Exports listed with the resolved target index and kind.
- Goto Symbol contributes export names project-wide.
- Click an entry to jump to its hex offset.

### Functions tab
- One row per defined function with index, type signature, local count, and code-section offset.
- Searchable, sortable, keyboard-navigable.

### Top tab — size profiler
- Sortable table of the largest functions, data segments, and sections by byte size.
- Headers, sorting, and scrolling polished in 0.9.
- Click a row to navigate to the corresponding hex range.

### Hex tab
- Byte-level view with section annotations.
- Text and hex panels with selection synchronised between them.
- Arrow-key navigation.
- Incremental search across the binary, including raw byte patterns.
- `hexana.goToOffset` (shares `Cmd/Ctrl+L`) jumps to a byte offset.
- `hexana.showStructurePopup` (shares `Cmd/Ctrl+F12`) opens a structure outline.
- Clickable hex offsets in the WAT tab navigate here.

### WAT tab
- Rendered WebAssembly Text format with offsets-based line numbers.
- Syntax highlighting and brace matching.
- Search and scrolling in the editor surface.
- IDE zoom respected.
- Reference-types and bulk-memory instructions rendered (added 0.8.2).
- Legacy Exception Handling (`try`, `catch`, `throw`, `rethrow`, `delegate`, `catch_all`) supported (0.8.2).

### Information bar
- File size (hover for per-section breakdown).
- Module kind (core / component).
- Run and Debug buttons when a runtime is configured.
- Backreference link to a parent component when applicable.

## WIT language support

Hexana registers a full `wit` Language with the IntelliJ Platform. The complete WIT feature surface is documented in [`wit-language.md`](wit-language.md). Headlines:

- Lexer, parser, and PSI for the WIT grammar.
- Syntax highlighting and semantic keyword highlighting.
- Brace matching, code folding, breadcrumbs.
- Code formatter and line-wrap strategy.
- Keyword completion and `@gate` completion.
- 5 inspections: empty definition, world name uniqueness, missing semicolon, gate validation, use-declaration missing names.
- Find Usages with a dedicated handler factory.
- Rename validation.
- Goto Symbol contributes WIT declarations.
- Documentation provider for WIT elements.
- Built-in WIT type definitions indexed and resolved.
- Component-Model index for cross-file resolution.
- Line-marker provider for related-symbol navigation.

## WAT language support

- File type and language registered for `.wat`.
- Parser definition, syntax highlighter, brace matcher.
- File-view provider factory.
- Problem-highlight filter.
- Use-scope optimizer for performance on large WAT files.
- Documentation target provider.
- Find Usages handler factory.
- File-size checker that gates expensive operations on very large files.

## Hex view and binary file type

- Hexana registers a generic binary file type covering `.bin`, `.elf`, and `.exe`. These open in the hex view directly.
- File-type overrider claims `.wasm`, `.wat`, `.wit` for Hexana.

## Run configurations and debugging

Hexana 0.9 ships a `WasmRunConfigurationType` with a producer that creates a run configuration from any open `.wasm` file. Supported runtimes:

- **Wasmtime** — run and debug (debug requires LLVM 22.1+).
- **WAMR** — run and debug.
- **GraalVM** (built-in or custom installation) — run only.

The runtime is selected per-project in **Settings → Build, Execution, Deployment → WASM Runtime**. The configuration also accepts a custom GraalVM home directory. See [`run-and-debug.md`](run-and-debug.md).

Hexana also detects which WASM proposals a module uses (Threads, SIMD, GC, EH, etc.) and propagates the correct `--wasm-features` (or runtime-equivalent) flags automatically.

The debugger is registered via `WasmDebugRunner` and `WasmLineBreakpointType`; breakpoints are placed on WAT lines and back-mapped through DWARF when available.

## MCP server

Hexana registers `HexanaToolset` against the platform MCP server (`com.intellij.mcpServer`). 17 tools at 0.9, in canonical order:

```
summarize_module, list_imports, list_exports, list_globals,
list_types, list_memory, list_element_segments, list_functions,
functions_for_indices, get_globals_for_indices,
get_memory_for_indices, get_types_for_indices,
get_locals_for_functions, get_instructions_for_functions,
list_exported_functions, list_data, list_data_segments
```

Each tool is documented per-section in [`mcp-tools.md`](mcp-tools.md).

## Java-side WebAssembly API support

Loaded when the host IDE includes the Java module (`com.intellij.modules.java`). Hexana 0.9 contributes:

- **GraalWasm completion** for `org.graalvm.polyglot.*` calls that load WASM (`Source.newBuilder("wasm", url)`, `Context.eval(...)`, `module.newInstance(ProxyObject.fromMap(Map.of(...)))`, `getMember`/`invokeMember`).
- **Chicory completion** for `com.dylibso.chicory.*` calls (`Parser.parse(...)`, `Instance.builder(...)`, `instance.export("...")`, `ExportFunction.apply(...)`, `new HostFunction(...)`, `Store.addFunction`, `ImportValues`, `FunctionType.of(...)`).
- **`JavaWasmReferenceIndex`** indexes Java string literals that name `.wasm` exports / imports and resolves them across files.
- **Five inspections**:
  - `WasmExportInspection` — unresolved WebAssembly export name.
  - `WasmExportArgCountInspection` — export argument count mismatch.
  - `WasmExportArgTypeInspection` — export argument type mismatch.
  - `WasmImportInspection` — unresolved WebAssembly import name.

See [`java-integration.md`](java-integration.md).

## JavaScript and TypeScript integration

Loaded when the host IDE includes the JavaScript plugin (WebStorm by default; opt-in for IntelliJ IDEA, RustRover, PhpStorm, Rider). Hexana 0.9 contributes a `WasmFrameworkIndexingHandler` that hooks into the JetBrains JS type-inference pipeline and provides:

- **Imports completion** inside the second argument of `WebAssembly.instantiate(...)` / `instantiateStreaming(...)` — module names and per-module item names typed against the resolved `.wasm`'s real imports.
- **Exports type inference** on `.instance.exports.<name>` — function exports become typed callables with argument-count and argument-type checking; memory, table, and global exports get the right `WebAssembly.*` types.
- **Literal-union argument types** — when a WASM function branches on a string-literal argument, the parameter is typed as the literal union (e.g. `"add" | "sub"`), not just `string`.
- **`compile` / `compileStreaming` support** — `WebAssembly.Module` returned by `compile(...)` carries the resolved path forward into a later `new WebAssembly.Instance(module, imports)`.
- **`fetch` heuristic** — when instantiating from an `ArrayBuffer`, Hexana traces a sibling `fetch("…wasm")` call to identify the source binary.
- **TypeScript-aware** — recognises `WebAssembly.Instance` / `Module` / `WebAssemblyInstantiatedSource` resolved through TypeScript's `WebAssembly` namespace.
- **Application-level cache** (`WasmBinaryDataCacheService`) — each `.wasm` is parsed once and reused across all JS resolves in the session.

See [`js-integration.md`](js-integration.md) for the full reference.

## Indexes

Hexana ships four file-based indexes:

| Index | Purpose |
|---|---|
| `WasmIndex` | Maps `.wasm` file content into a queryable module representation. Used by Goto Symbol, run-config producer, MCP tools, and Java/JS integrations. |
| `WasmExportIndex` | Symbol index keyed by export name → owning `.wasm` file. |
| `DwarfIndex` | Indexes DWARF debug information for source mapping. |
| `WitComponentIndex` | Cross-file index for WIT component declarations. |
| `JavaWasmReferenceIndex` | Java string-literal → WASM export/import resolver (Java module only). |

JavaScript-side resolution is not file-based; it uses the application-level `WasmBinaryDataCacheService` instead. See [`js-integration.md`](js-integration.md).

## DWARF debug information

Hexana parses DWARF v4 and v5 sections embedded in `.wasm` custom sections:

- `.debug_str` — string table, original offsets preserved.
- `.debug_abbrev` — abbreviation tables.
- `.debug_info` — compilation units, DIE trees.
- `.debug_line` — line-number programs.

Used by the debugger for source-line mapping (via `WasmDwarfInjector` and `WasmDwarfUtil`) and by the **Source mapping** feature (added 0.8) which lets the user navigate from WAT back to the source file the binary was compiled from when DWARF is present.

## Goto Symbol contributor

Hexana adds a `gotoSymbolContributor` (`HexanaGotoSymbolContributor`) that surfaces:

- `.wit` interface, world, and type declarations.
- Component-model exports.
- Regular `.wasm` exports — picking one opens the file and selects the matching row in the Exports tab.

## Notifications

Hexana registers the `hexana` notification group (balloon display). Currently used for the `MissingWasmToolsNotification` editor notification that surfaces when a `.wat` file is opened but Hexana cannot find the binary tooling needed to operate on it.

## Settings pages

Two registered `applicationConfigurable` entries (see [`settings.md`](settings.md)):

- **Tools → Hexana** — general Hexana settings.
- **Build, Execution, Deployment → WASM Runtime** — runtime selection (Wasmtime, WAMR, GraalVM), runtime paths, default-runtime configuration.

## Usage statistics

`HexanaCounterUsagesCollector` registers event group `org.jetbrains.hexana` (recorder `FUS`) with one event in 0.9: `wasm.file.opened`. Subject to the IDE's standard statistics-collection consent.
