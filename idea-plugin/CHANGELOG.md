# Changelog

## Unreleased

### Added
- [DWARF section detection and parsing](https://github.com/JetBrains/hexana/issues/49)
- [Explorer integration](https://github.com/JetBrains/hexana/issues/52)
- [Source mapping via DWARF](https://github.com/JetBrains/hexana/issues/48)
- [JS interop - code completion and type inference of instance.exports.](https://github.com/JetBrains/hexana/issues/18)
- [JS interop - code completion of namespaces and property names for imports](https://github.com/JetBrains/hexana/issues/17)
- [Run configurations for WASM - run with Wasmtime or GraalVM (only run is supported for now, no debug yet)](https://github.com/JetBrains/hexana/issues/69)

### Changed

### Deprecated

### Removed

### Fixed
- [IJPL-242167 IAE: WasmModuleKt.fromSections via WasmIndex](https://youtrack.jetbrains.com/issue/IJPL-242167)
- [ClassCastException in WitStaticFuncItemMixin](https://github.com/JetBrains/hexana/issues/84)
### Security

## 0.7 - 2026-03-31

### Added
- [WAT: offsets-based line numbers](https://github.com/JetBrains/hexana/issues/35)
- [Search in table view for imports/exports/functions (start typing to search)](https://github.com/JetBrains/hexana/issues/7)
### Changed
- WIT: added very basic editing experience (keyword completion, code formatting)
- Table UX improvements for imports/exports/functions: arrow keys for navigation, scrolling, layout fixes

### Fixed
- [Issues with KDoc when Hexana is enabled](https://github.com/JetBrains/hexana/issues/67)
- shared limit support for memory type was added.
- [NIE on reading go wasm module](https://github.com/JetBrains/hexana/issues/77)

## 0.6 - 2026-03-17

### Added
- [Parse Component Model binary format (component sections, instances, type definitions)](https://github.com/JetBrains/hexana/issues/64)
- [Display component structure in the explorer tree (imports, exports, interfaces, worlds)](https://github.com/JetBrains/hexana/issues/63)
- [Support for WIT definitions language (code model, go to definition, find usages, documentation on hover)](https://github.com/JetBrains/hexana/issues/9)
- [Navigation from WIT files to export definitions in WASM files (currently only exports are supported, more to come later)](https://github.com/JetBrains/hexana/issues/11)

### Fixed

- [[mcp] Several MCP functions incorrectly working with indices of MCP request](https://github.com/JetBrains/hexana/issues/66)
- [[mcp] importFunctions() must ignore non-function imports](https://github.com/JetBrains/hexana/issues/65)

## 0.5 - 2026-01-19

### Added

- basic WIT files highlighting
- UX improvement for table views
- table view for function and custom sections
- [Introduce Basic Search capability in Binary View](https://github.com/JetBrains/hexana/issues/3)

### Fixed

- [tag type import parsing](https://github.com/JetBrains/hexana/issues/4)
- [Non-Standard WASM parsing should not lead to failure](https://github.com/JetBrains/hexana/issues/1)

## 0.4 - 2025-12-15

### Added

- "Structure view" for WASM files
- Basic Text Selection for Hex View

### Fixed

- navigation from hex view to the data definition in WAT
- global usages in unfolded WAT syntax

## 0.3 - 2025-12-08

### Added

- `summarize_module` — Returns a compact numeric summary (functions, imports, exports, memories, tables, types, module size)
- `list_functions` — Paginated list of function metadata with offset/limit support
- `list_exported_functions` — All exported functions with resolved signatures (paginated)
- `list_imports` — All imported functions with module/name/signature
- `functions_for_indices` — Detailed function records for specific funcidx values
- `get_instructions_for_functions` — Full decoded instruction stream for selected functions
- `get_locals_for_functions` — Local variable declarations for selected functions
- `list_types` — All type definitions (func, struct, array, rec)
- `get_types_for_indices` — Selective type retrieval by typeidx
- `list_memory` — All linear memory declarations with limits
- `get_memory_for_indices` — Selective memory retrieval by memidx
- `list_globals` — All global variables with type/mutability/init expression
- `get_globals_for_indices` — Selective global retrieval by globalidx
- `list_data_segments` — Data segments with metadata, pagination, filtering (recommended for large modules)
- `list_data` — Raw base64-encoded data segment payloads (use with caution on large modules)
- `list_element_segments` — All element segments (table initializers, funcref arrays)
- `list_exports` — All exports (functions, memories, tables, globals) with kind/index
- UTF-8 text preview
- navigation from function definition in wat to hex view 
- unfolded syntax support
- navigation from hex view to the function and data definition

## 0.2 - 2025-12-03

### Added

- Wasm data section navigation support
- Basic WAT convertation (for small files) and navigation
- Show list of export and import names
- Copy highlighted data section as ASCII ot kotlin listOf<UByte>
- ability to open any file in hex view
- action navigating to the given offset
