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

## The tool

One tool: **`query_artifacts`**. Pass the binary (`path` to the `.wasm`) and an `operation`; output is
byte-bounded. Useful params:

- `operation` — picks the query (table below). Default `overview`.
- `query` — case-insensitive filter terms, or the payload for `crash_frames` / `data_payload_audit`.
- `entityTypes` — filter: `export`, `import`, `memory`, `global`, `function`, `element`.
- `startName` / `startFuncidx` — a function/import/export name or unified index for `trace` / `host_call_diff`.
- `relations` — `direct_call`, `direct_imported_call`, `indirect_call`, `memory_write`.
- `baselinePath` — a second `.wasm` for diff-style operations.
- `limit` (≤100), `detail` (`summary` | `evidence`), `maxBytes` (start small).

## Pick the operation by task

| Task | `operation` | Key params / discipline |
|------|-------------|-------------------------|
| "What is this module?" / counts | `overview` (default) | none — skip it entirely if the task already names a focused fact |
| Triage a crash / stack trace | `crash_frames` | **Read the crash report first; pass ALL numeric stack funcidx values as strings in `query` in ONE call.** Don't probe one frame at a time |
| API/ABI changed between two builds | `api_diff` | `baselinePath` = the old `.wasm`. Returns changed signatures, public type indices, ref-type import ABI risks |
| Exported ABI surface (one module) | `public_surface` | exported functions/signatures |
| Compact imports + exports (one module) | `abi_surface` | `limit=1` is often enough (summary JSON arrays) |
| Host / supply-chain import audit | `import_surface` | host manifest/link checks; `functionImportsJson` on the summary |
| Memory declarations / limit changes | `memory_contract` | add `baselinePath` for limit-change detection |
| Mutable imported / new exported globals | `global_state_policy` | — |
| Data-segment payload policy check | `data_payload_audit` | **Read the policy first; pass all forbidden strings exactly, in ONE call.** Empty `query` returns only a reminder |
| Exported-function local-count budget | `local_budget` | — |
| Exports that write memory / call host directly | `entrypoint_effects` | omit `relations` to scan both `memory_write` and `direct_imported_call` in one call |
| Element-table slot target changes | `dispatch_table_diff` | `baselinePath` for the diff |
| Direct imported-call regression from a function | `host_call_diff` | `startName` or `startFuncidx` |
| Look up a specific symbol/index | any + `query` / `startFuncidx` | resolve it from results first, then drill down |
| No focused operation matches | `find` / `diff` / `trace` | generic fallbacks only |
| Ambiguous task, no named fact | `triage` | last resort |

## Rules (efficiency + correctness)

- **One focused call, then stop.** Don't follow a focused operation with generic `find`/`diff`/`trace`
  (e.g. `api_diff` → `find`, `import_surface` → `find`, `crash_frames` → per-frame `trace`,
  `entrypoint_effects` → per-export `trace`) unless a required output field is genuinely missing.
- **Gather inputs before calling** for `crash_frames` and `data_payload_audit` — one call, not a probe loop.
- **Keep `limit` and `maxBytes` small.** Request `detail=evidence` only for the few records that need
  offsets, locals, element lists, or instruction text.
- **Path fields take a real path** — the task's `.wasm` path or the `modules[].path` echoed in the result.
  Never write `local`, `current`, or `baseline` into a path field.
- **Stay grounded in the tool output.** For any `.wasm` structural fact, prefer Hexana over shell tooling
  (`objdump` / `strings` / `hexdump`); the MCP server reports the verified binary structure.