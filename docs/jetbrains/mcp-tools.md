---
title: Hexana MCP Tools (0.9)
description: Reference for Hexana's 17 Model Context Protocol tools for AI-assisted WebAssembly analysis.
version: "0.9"
mcp-tools: 17
---

# Hexana MCP Tools

Hexana 0.9 exposes a **Model Context Protocol** server with 17 tools that let AI assistants — Claude Desktop, Claude Code, Cursor, Continue, and any other MCP-speaking client — explore the WebAssembly module currently loaded in the IDE.

The toolset is registered through the platform's `mcpServer.mcpToolset` extension point (`HexanaToolset`, generated from the DSL in `mcp/tools-generator/src/main/kotlin/org/jetbrains/hexana/mcp/tools/HexanaTools.kt`).

## How do I connect an AI assistant to Hexana's MCP server?

Hexana's MCP toolset runs as part of the IDE's built-in MCP server (`com.intellij.mcpServer`). When the bundled plugin is enabled, the IDE exposes a local MCP endpoint that any compatible client can connect to. The exact connection mechanism depends on the client:

- **Claude Desktop** — add the IDE's MCP endpoint in `claude_desktop_config.json`.
- **Claude Code / Cursor / Continue** — configure the MCP server URL in the client's settings.

See your client's documentation for the connection string. Hexana itself requires no configuration beyond the plugin being installed and the platform MCP server being enabled.

## Tool order

The 17 tools are registered in a canonical order enforced at server start:

```
summarize_module
list_imports
list_exports
list_globals
list_types
list_memory
list_element_segments
list_functions
functions_for_indices
get_globals_for_indices
get_memory_for_indices
get_types_for_indices
get_locals_for_functions
get_instructions_for_functions
list_exported_functions
list_data
list_data_segments
```

Order matters for assistants that pick the first matching tool — module-wide overviews first, then per-section lists, then index-keyed lookups for drilling in.

## Common conventions

All tools share these conventions:

- **Pagination**: Tools that return collections paginate when results exceed 1,000 items. Callers receive the first page plus a continuation token.
- **Structured output**: Tools return JSON with stable field names. Schemas are generated from the DSL.
- **Empty-module behaviour**: When no `.wasm` file is loaded in the IDE, list-tools return an empty result; lookup-tools return an explicit "no module loaded" error.
- **Index types**: WASM indices are 32-bit unsigned integers, returned as JSON numbers.
- **Naming**: Symbolic names (export name, import module/name, function name from the `name` custom section) are preserved verbatim; if no name exists, the tool returns the numeric index.

## Tool reference

### `summarize_module`

Returns a module-level summary: magic bytes, binary format version, section presence, total size in bytes, and counts of imports, exports, functions, types, memories, globals, tables, element segments, and data segments. The first tool to call when an assistant first encounters a binary.

### `list_imports`

Returns all imports grouped by kind (function, table, memory, global, tag). Each entry includes the import module name, item name, and resolved type signature. Use for understanding what host capabilities the module requires.

### `list_exports`

Returns all exports with name, kind (function, table, memory, global, tag), and target index. Use for the module's public surface.

### `list_globals`

Returns the global section: type (`i32`/`i64`/`f32`/`f64`/`v128`/`funcref`/`externref` or GC ref type), mutability, and initialiser expression.

### `list_types`

Returns the type section: function types and (when GC is in use) recursive groups, structs, and arrays. Each type has a stable index.

### `list_memory`

Returns the memory section: per-memory min/max page count and shared-or-not flag (per the Threads proposal).

### `list_element_segments`

Returns element segments: passive vs. active, table index for active segments, element kind, and item list. Supports element segment type 6 per WebAssembly 3.0 §5.5.12 (fixed in 0.8.2).

### `list_functions`

Returns all defined functions (imports excluded) with index, type, and local count. Pagination kicks in for modules with more than 1,000 functions — Skiko has ~14,000 and pages through this tool routinely.

### `functions_for_indices`

Look up specific functions by index. Inputs: a list of function indices. Outputs: function name (from the `name` custom section if present), type, local count, and code-section byte range. Use after `list_functions` to drill in.

### `get_globals_for_indices`

Look up specific globals by index. Inputs: a list of global indices. Outputs: type, mutability, initialiser.

### `get_memory_for_indices`

Look up specific memories by index. Inputs: memory indices. Outputs: limits and shared flag.

### `get_types_for_indices`

Look up specific types by index. Inputs: type indices. Outputs: full type description including parameters, results, and (for GC) field layouts.

### `get_locals_for_functions`

Returns the local declarations for specific functions. Inputs: function indices. Outputs: locals declared per function as a list of `(count, type)` pairs.

### `get_instructions_for_functions`

Returns the instruction sequence for specific functions. Inputs: function indices and (optionally) byte ranges to limit output size. Outputs: instructions as opcode + immediates, plus their original byte offsets. The largest-payload tool — use a range or limit the function set.

### `list_exported_functions`

Returns only the subset of functions that are exported, with their export name. Convenience overlap with `list_exports` filtered to functions, but ordered by function index instead of export order.

### `list_data`

Returns the contents of data segments. Inputs: optional segment index and byte range. Outputs: raw bytes (base64) plus the segment's offset expression. Range bounds validated in 0.9 to reject empty or out-of-bound ranges.

### `list_data_segments`

Returns the data section metadata: per-segment kind (passive vs. active), memory index for active segments, and byte size. Pair with `list_data` for the actual bytes.

## Error contract

When a tool cannot answer (no module loaded, invalid index, malformed input), it returns a structured error with:

- `code` — a stable identifier (`no_module_loaded`, `invalid_index`, `invalid_range`, `unsupported_section`, `internal_error`).
- `message` — a human-readable explanation.
- `path` — when validation failed, the JSON path of the offending field.

The contract is covered by `HexanaMcpErrorContractTest` and `HexanaMcpStructuredOutputContractTest`.

## What this toolset does not (yet) do in 0.9

- No write operations — tools are read-only. The editable-binary-documents arc on master (post-0.9) introduces an edit pipeline; an MCP-facing edit surface is on the roadmap.
- No instruction-flow analysis (call graphs, dead code) — the building blocks (`get_instructions_for_functions`) are present, but no aggregated graph tool is registered.
- No DWARF tooling exposed via MCP — DWARF parsing exists in-IDE and powers the debugger, but is not yet reflected in tools.
- No cross-module tools — every tool operates on the single module currently loaded in the IDE.

## See also

- [`features.md`](features.md) — full feature reference.
- [`architecture.md`](architecture.md) — how the MCP DSL generates `HexanaToolset.kt`.
- Tests under `idea-plugin/src/test/kotlin/org/jetbrains/hexana/mcp/direct/` — one per tool, plus contract suites.
