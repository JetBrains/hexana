# Wasm Debugger: Stepping Model and Disassembly View

**Date**: 2026-08-11
**Status**: Implemented (branch `user/nodejs-debug-dwarf`)
**Related**: [operand stack and variable representation](2026-08-11-wasm-debug-stack-representation-design.md), [Node.js/CDP specifics](2026-08-11-wasm-debug-nodejs-cdp-design.md)

## Overview

V8 steps WebAssembly one instruction at a time. Without intervention a single
source line takes several Step Over clicks to cross, and the user cannot tell
whether a click advanced a statement or an `i32.add`. This spec defines two
coordinated pieces:

1. A **focus-dependent stepping model**: source-line granularity by default,
   instruction granularity while the disassembly is visible.
2. A **Wasm Disassembly view** (left pane of the debugger's **Wasm** tab) that
   shows the paused function one instruction per row and tracks the pause
   offset across steps.

## Stepping model

### Granularity selection

The debugger tool window's **Wasm** tab content is tracked via a
`ContentManagerListener` on the `RunnerLayoutUi` content manager
(`disassemblyTabSelected`, volatile). Selection, not keyboard focus, is the
signal: clicking the step toolbar buttons moves keyboard focus away from the
tab, so literal focus would never trigger instruction mode.

- Tab **selected** → each `startStepOver/Into/Out` sends exactly one CDP step:
  one wasm instruction.
- Tab **not selected** → the step arms a `LineStepState` and enters the
  auto-repeat loop below.

### Source-line auto-repeat

`LineStepState` records, at step initiation: the CDP step command, the DWARF
`(file, line)` of the top frame (via `wasmOffsetToSource` on the frame's
module-absolute offset), and the stack depth (`callFrames.size`).

On each `Debugger.paused` while a state is armed:

| Condition | Action |
|---|---|
| a breakpoint was hit | disarm, present the pause |
| same `(file, line)` AND same depth AND budget left | re-send the same step command, do NOT notify the session (UI stays "running") |
| line or depth changed, no DWARF mapping, or budget exhausted | disarm, present |

The budget (`MAX_AUTO_STEPS = 256`) exists because a single-line loop
(`loop {}`) would otherwise spin the debugger forever; on exhaustion the pause
presents wherever execution is. Depth participation makes Step Into present on
function entry instead of skating across it.

If the frame at initiation has no DWARF line mapping, the step degrades to a
single instruction step (no state armed).

## Disassembly view

### Data source

`WasmDisassemblySource` (idea-plugin, `run/CdpDebuggerTabs.kt`) indexes the
wasm binary once at session start: code-entry offsets (`scanOffsets`), the
imported-function count, and the name section's function subsection. Function
bodies are parsed lazily, one `parseCodeEntry` per first visit, and cached by
code-section-local index (`ConcurrentHashMap`) so stepping inside one function
is lookup-only. Bounded for 14k-function / 50MB modules by construction: only
visited functions are ever decoded.

### Rendering contract

Rows are produced by a recursive walk over `WasmCodeRecord.opCodes`:

- **Mnemonics and operands come from `watFor`** (binaryProvider
  `WatMnemonics.kt`, made public), the same renderer the virtualized WAT view
  uses. The debugger must never grow a second opcode-to-text scheme; the
  first version derived text from Kotlin class names and produced `i32.l_t_s`
  and `flow.call`.
- **Symbolic call operands**: a `WatSymbolNames` resolver backed by the name
  section's function namemap (global index space, imports first) renders
  `call $name`; modules without a name section fall back to numeric indices.
  The full `WasmSymbolResolver` chain (DWARF names, symbol maps, exports) is
  not wired; debug builds always carry a name section.
- **Block nesting**: the parser nests block bodies inside
  `Flow.Block`/`Loop`/`If`/`Else` (`value`) and `TryTable` (`code`) rather
  than keeping `opCodes` flat. The walk recurses with two-space indentation
  and emits a synthetic `end` row per block.
- **Offsets**: opcodes with immediates carry their real module-absolute byte
  offset. Zero-operand singleton opcodes (`i32.add`, `drop`, `return`, …) are
  shared object instances whose inherited offset defaults to 0 and means
  nothing — they render with a blank offset cell and are never dropped from
  the listing.

### Pause tracking

On every pause/frame change the panel selects and scrolls to the row whose
offset is the largest not exceeding the pause offset, skipping blank-offset
rows (linear scan; fine at pause frequency for one function body). Non-wasm
frames or missing DWARF clear the panel rather than erroring.

## Files

```
idea-plugin/src/main/kotlin/.../run/
├── CdpDebugProcess.kt      # LineStepState, startStep, auto-repeat hook in
│                           #   handleDebuggerPaused, tab-selection tracking
└── CdpDebuggerTabs.kt      # WasmDisassemblySource, WasmDisassemblyPanel,
                            #   table models, session listener
binaryProvider/.../format/WatMnemonics.kt   # watFor + WatSymbolNames (public)
```

Tests: `WasmDisassemblySourceTest` (offset lookup, row building, caching,
monotonicity over offset-bearing rows, presence of blank-offset singleton
rows) against `dwarf-types.wasm` and `loop.wasm`.

## Limitations / follow-ups

- Stepping semantics remain V8's within a granularity: no "step over the
  whole call at instruction level" special case.
- `end` rows carry no byte offset (the parser does not record the end byte).
- VS Code has no custom debugger tab concept (DAP); the disassembly view and
  instruction-granularity stepping are IntelliJ-only.
