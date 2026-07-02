---
name: wasm-inspector
description: >
  Use when inspecting or reasoning about a WebAssembly binary or component (.wasm) — imports/exports,
  functions, memories, globals, tables, element/data segments, ABI or API diffs between two builds,
  crash-frame triage from a stack trace, host/supply-chain import audits, memory and global-state
  policy checks, data-segment payload checks, or entry-point effects. Drives the bundled Hexana MCP
  server, which extracts ground-truth structure directly from the binary (prefer it over shell tools
  like objdump/strings/hexdump for any .wasm fact).
---

# WASM binary inspection with Hexana

Hexana's MCP server reads ground truth straight from the `.wasm` binary. Use it for any structural
fact about a WebAssembly module or component, and keep answers grounded in what the tool reports.

## Tools

The bundled release server exposes focused production tools. Start with the narrowest matching
tool instead of looking for a generic catch-all tool.

Common inputs:

- `path` — real path to the `.wasm` file.
- `baselinePath` — second `.wasm` for compatibility/diff checks when the tool supports it.
- `query`, `name`, `funcidx`, `limit`, `maxBytes`, and detail flags — use only when present in the tool schema.

## Pick The Tool By Task

| Task | Tool |
|------|------|
| "What is this module?" / counts / first look | `summarize_binary` or `summarize_module` |
| Enabled Wasm features / proposals | `list_enabled_features`, `list_required_proposals`, `diff_required_proposals` |
| Runtime/component compatibility | `check_runtime_compatibility`, `check_component_compatibility` |
| Imports, exports, and exported ABI surface | `list_imports`, `list_exports`, `search_exports`, `list_exported_functions` |
| Functions and signatures | `list_functions`, `search_functions_by_name`, `resolve_function_signature`, `resolve_function_symbols` |
| Function internals and call graph | `disassemble_function`, `get_function_call_graph`, `resolve_source_locations` |
| Crash stack triage | `resolve_wasm_frames` |
| Memory declarations / memory policy | `list_memory`, `check_memory_contract` |
| Globals / global-state policy | `list_globals`, `audit_global_state_policy` |
| Tables and data segments | `list_element_segments`, `list_data_segments`, `audit_data_payload_policy` |
| Entry-point effects / host-call policy | `get_entrypoint_policy` |
| Local stack budget | `audit_local_stack_budget` |
| ABI binding regeneration planning | `plan_abi_binding_regeneration` |

## Rules (efficiency + correctness)

- **One focused call, then stop.** Do not fan out into broad follow-up calls unless a required output field is
  genuinely missing.
- **Gather inputs before calling** for `resolve_wasm_frames` and `audit_data_payload_policy` — one call, not a probe loop.
- **Keep `limit` and `maxBytes` small.** Request `detail=evidence` only for the few records that need
  offsets, locals, element lists, or instruction text.
- **Path fields take a real path** — the task's `.wasm` path or a path echoed in a previous result.
  Never write `local`, `current`, or `baseline` into a path field.
- **Stay grounded in the tool output.** For any `.wasm` structural fact, prefer Hexana over shell tooling
  (`objdump` / `strings` / `hexdump`); the MCP server reports the verified binary structure.
