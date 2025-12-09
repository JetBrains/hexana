# Changelog

## Unreleased

### Added

- structure view for WASM files

### Changed

### Deprecated

### Removed

### Fixed

### Security

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
