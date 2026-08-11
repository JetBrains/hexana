# Wasm Debugger: Node.js / CDP Specifics

**Date**: 2026-08-11
**Status**: Implemented (branch `user/nodejs-debug-dwarf`)
**Related**: [stepping and disassembly](2026-08-11-wasm-debug-stepping-disassembly-design.md), [operand stack and variable representation](2026-08-11-wasm-debug-stack-representation-design.md)

## Overview

Everything specific to debugging wasm inside V8 over the Chrome DevTools
Protocol: process launch, the breakpoint registration race, V8's wasm frame
model, memory access, and the integer-base contracts that connect DWARF to
CDP. Both frontends (IntelliJ `CdpDebugProcess`, VS Code `CdpDebugAdapter.ts`)
implement this pipeline; platform differences are listed at the end.

## Launch

Node has no CLI flag to run a `.wasm` file, so the runner passes an inline
`--eval` harness (`buildNodeJsRunArgs`, plugins-shared): read the binary,
compile, auto-stub every import (functions return 0, `proc_exit` exits,
memory/table/global get minimal instances), instantiate, call the configured
export with the configured arguments. Contract gotchas:

- the export name defaults to `_start`; modules without it need the run
  configuration's export field set (e.g. `add`),
- program arguments are passed as the call arguments (JS `ToNumber` coercion
  applies to wasm numeric params).

Debug mode launches `node --inspect-brk=0 --eval …` and parses the
`ws://127.0.0.1:<port>/<uuid>` URL from stderr.

## Breakpoint registration

V8 compiles the wasm script mid-run; a breakpoint registered after
`Debugger.scriptParsed` arrives loses the race against execution. The
pipeline therefore:

1. sets `Debugger.setInstrumentationBreakpoint(beforeScriptExecution)` right
   after `Debugger.enable`;
2. on the instrumentation pause following the wasm `scriptParsed`, registers
   breakpoints against the concrete scriptId and resumes;
3. keeps a `urlRegex` (`^wasm://`) registration as the pre-parse fallback.

Locations: `{ scriptId, lineNumber: 0, columnNumber: <module-absolute byte
offset> }`. Offsets come from the DWARF line table via
`sourceToAllWasmOffsets(path, line)`.

Conditional breakpoints ride the same calls via the `condition` field. On
IntelliJ, condition text is rewritten from DWARF source names to `$varN`
using the local-name map of the subprogram covering the breakpoint offset;
on VS Code the condition passes raw (no name map exists before the first
pause), so only V8-visible names work there.

## Pause handling

`Debugger.paused` dispatch, in order: instrumentation pauses (register +
resume); `Break on start` (register pending breakpoints, then resume); pure
JS pauses after the wasm script was seen (auto-resume — don't fight other
debuggers or block the harness); line-step auto-repeat (see the stepping
spec); breakpoint/step presentation.

Frame presentation synthesizes inlined frames: `DwarfInlineFrameExtractor`
returns the `DW_TAG_inlined_subroutine` chain covering the pause offset
(innermost first) and the UI stacks display-only frames suffixed
`(inlined)` above the physical frame, each caller at its recorded call site.
Scopes and evaluation delegate to the physical frame; V8 knows nothing about
these frames, so stepping stays physical. Wrapper frames (no DWARF
subprogram) are filtered; synthetic frames never are.

## V8 wasm frame model (empirical, Node 24)

- Wasm frames report `url = wasm://wasm/<name>-<hash>`; the pause location's
  `columnNumber` is the **module-absolute byte offset**.
- `scopeChain` types: `wasm-expression-stack` (holds the `stack` object,
  className `Stack`), `local` (wasm locals as `$var0…N` wasmvalue objects),
  `module` (instance/memories/globals — intentionally not shown).
- A `wasmvalue` unwraps via `Runtime.getProperties` on its objectId; the
  inner `value` property carries the number and `description` the type
  (`i32`, `f64`, …).

## Memory access

Linear memory is read with `Debugger.evaluateOnCallFrame` on the wasm frame:
`memories[0].buffer` is available in that evaluate context, and the
expression builds a bounded `Uint8Array` view and returns it hex-encoded
(strings survive `returnByValue`; binary encodings do not). Reads are capped
at 4KB per variable. Verified end-to-end against live Node in
`CdpDebugIntegrationTest`.

## Integer-base contracts

Every integer crossing a boundary here has a base; these are the pinned ones
(each was a shipped bug or a near-miss):

| Value | Base |
|---|---|
| CDP `columnNumber` (wasm frame) | module-absolute byte offset |
| DWARF `DW_AT_low_pc` / line-table addresses | code-section-relative (convert with the code section's content offset) |
| `DW_AT_low_pc = 0xFFFFFFFF` (or 0) | linker tombstone for dead-stripped code, not an address — must be rejected |
| `DW_FORM_ref4` (abstract_origin, DW_AT_type) | CU-header-relative; `DW_FORM_ref_addr` is section-relative |
| `sourceToAllWasmOffsets` line key | 0-based editor line (`XLineBreakpoint.line` passes through) |
| `wasmOffsetToSource(...).line` | 1-based DWARF line |
| `CdpClient` JSON params | must be `Int` — `Long` serializes in a form V8 rejects ("Invalid parameters") |

Source-file resolution from DWARF paths is by longest path suffix (both
directions), never base name alone: multi-crate projects contain several
`lib.rs`.

## Platform parity

| Capability | IntelliJ | VS Code |
|---|---|---|
| Launch/breakpoints/stepping/inlined frames/memory variables | yes | yes |
| Conditional breakpoints with source names | yes (translated) | raw pass-through only |
| Wasm tab (disassembly + operand stack), instruction stepping | yes | no (DAP has no custom debugger tabs; the stack stays in Variables) |

## Files

```
idea-plugin/src/main/kotlin/.../run/
├── CdpDebugRunner.kt / CdpDebugProcess.kt / CdpBreakpointHandler.kt / CdpClient.kt
plugins-shared/src/commonMain/.../shared/CdpSourceLocationResolver.kt
vscode-plugin/src/CdpDebugAdapter.ts (+ extension-lib SourceLocationResolver wrapper)
```

Tests: `CdpDebugIntegrationTest` (live `node --inspect-brk`, production
classes, skips without node), `CdpSourceLocationResolverTest`,
`ConditionTranslationTest`, `CdpInlinedFrameTest`. Ad-hoc protocol probes
live in `.cache/nodejs-debug-test/` (uncommitted).

## Limitations / follow-ups

- No interactive debugger console (evaluate-expression field only).
- No attach-to-running-node; launch only.
- DWARF5 `.debug_rnglists` ranges are not decoded (non-contiguous inlined
  ranges are missed).
- The reverse line index can leak tombstoned offsets (seen once as offset
  `4294967567` for a closing brace line); needs the same tombstone filter as
  the inline extractor.
