# Wasm Debugger: Operand Stack and Variable Representation

**Date**: 2026-08-11
**Status**: Implemented (branch `user/nodejs-debug-dwarf`)
**Related**: [stepping and disassembly](2026-08-11-wasm-debug-stepping-disassembly-design.md), [Node.js/CDP specifics](2026-08-11-wasm-debug-nodejs-cdp-design.md)

## Overview

A paused wasm frame has three value habitats, and each needs its own
representation:

1. the **operand stack** (V8's `Stack` object) — unnamed intermediates,
2. **wasm locals** (V8 `wasmvalue` scope properties, `$varN`) — values the
   compiler kept in registers/locals,
3. **linear memory** (`DW_OP_fbreg` slots on the shadow stack, or absolute
   addresses) — structs, arrays, and, in unoptimized builds, everything.

The governing rule for what appears where: **the DWARF location class is
authoritative**. A variable is shown by the path its location expression
names, never by a positional guess.

## Operand stack (Wasm tab, right pane)

- Source of truth: the `stack` property (className `Stack`) that V8 places
  inside a scope of type `wasm-expression-stack` (older layouts: inside the
  `local` scope — the lookup scans both).
- Rendered as a table (index, type, value) in the **Wasm** tab, refreshed on
  `sessionPaused` / `stackFrameChanged`, cleared on resume. Entries are
  wasmvalue objects; each is unwrapped to its inner value.
- The `stack` row is **hidden from the Variables tree**. It lived there
  originally, but XDebugger restores node expansion on every pause, so an
  expanded `stack` node re-fetched and re-unfolded on every step. Other
  nested wasmvalue containers in the tree still flatten to
  `0 = {i32} 9`-style children.
- Semantics to know: the operand stack is genuinely **empty at statement
  boundaries** — an empty table at a line breakpoint is correct. It comes
  alive under instruction stepping, where unnamed intermediates (e.g. a
  computed return value that has no `DW_TAG_variable`) become visible.

## Variables view rules

For each `wasmvalue` property of the local scope, in order:

1. **Non-wasm scope artifacts** keep their raw names and consume no local
   index.
2. **Wrapper functions** (no DWARF subprogram for the frame): all locals
   hidden — they are compiler internals.
3. **DWARF name mapping**: `$varN` names map to source names via
   `buildWasmLocalToNameMap` — explicit `DW_OP_WASM_location local N`
   entries first, positional fallback second.
4. **Authority guard**: a name that `memoryVariablesAt(offset)` reports at
   this pause is *excluded* from positional mapping. At `-O0` the positional
   fallback would otherwise label the shadow stack pointer with a variable
   name (`p = {i32} 1048560`) and the dedupe would then suppress the correct
   row. Heuristic and authoritative sources agree on optimized builds and
   diverge exactly on the builds this feature exists for.
5. **Unmapped locals** (map non-empty, no name): hidden as spill slots.

## Linear-memory variables

`memoryVariablesAt(moduleOffset)` (plugins-shared facade) returns, for the
subprogram covering the pause offset: name, resolved `DwarfTypeInfo`, and a
location descriptor — `fbreg` offset plus the frame-base wasm local
(`DW_AT_frame_base` = `DW_OP_WASM_location local N`), or an absolute address.

At a pause, per variable:

1. frame base = value of the frame-base local, read from the wasmvalue scope;
2. `byteSize` bytes (capped at 4KB per variable) are read from `memories[0]`
   via `Debugger.evaluateOnCallFrame`, hex-encoded (see the Node.js spec);
3. `DwarfMemoryDecoder` decodes little-endian per type: base types by
   encoding+size, enums by enumerator value, pointers as hex, structs as
   expandable members at their `DW_AT_data_member_location` offsets, arrays
   bounded at 100 children with a truncation marker.

Deduplication against the wasmvalue path is by name, and the memory path
wins by construction of rule 4 above. Failures (unresolvable frame base,
read error) omit the variable silently with one log per frame.

## Files

```
plugins-shared/src/commonMain/.../shared/
├── CdpSourceLocationResolver.kt   # memoryVariablesAt facade
├── CdpMemoryVariable.kt           # location descriptor model
└── DwarfMemoryDecoder.kt          # typed little-endian decoding
debugInfo/dwarf/src/commonMain/.../dwarf/
├── DwarfTypeResolver.kt           # DW_AT_type chains (CU-relative refs)
└── DwarfVariableInfoExtractor.kt  # subprograms, variables, frame base
idea-plugin/src/main/kotlin/.../run/
├── CdpDebugProcess.kt             # computeChildren rules, CdpMemoryValue
└── CdpDebuggerTabs.kt             # operand-stack table
vscode-plugin/src/CdpDebugAdapter.ts  # same rules, DAP Variables
```

Tests: `DwarfMemoryDecoderTest` (pure decoding), `CdpMemoryVariableTest`
(extraction against the `-O0` fixture, hard assertions), `DwarfMemvarsFixtureTest`
(parser-level, wasmParser corpus), `CdpDebugIntegrationTest` (live node
end-to-end: `Point { x: 5, y: 2, z: 3 }` pre-increment).

Fixture note: `-O1` const-propagates locals into `DW_OP_lit` pieces — only
the `-O0` fixture (`dwarf-memvars.wasm`) has anything in linear memory to
test. Keep both optimization levels in the corpus.

## Limitations / follow-ups

- Wasm-global frame base (`DW_OP_WASM_location` global form) is not resolved;
  such variables are skipped.
- Pointer dereference from the memory path is not implemented (one-level
  lazy deref is specced but stubbed).
- The expression evaluator and conditional breakpoints still use the
  positional name map only — conditions referencing memory-located variables
  translate to the wrong `$varN` on `-O0` builds.
- Location lists are consulted via `effectiveLocation` without PC-range
  selection per pause offset.
