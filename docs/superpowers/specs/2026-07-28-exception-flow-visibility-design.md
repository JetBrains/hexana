# Exception-Flow Visibility for WASM — Design Spec

Date: 2026-07-28
Status: draft
Origin: user request — do for exceptions what the indirect-call visibility work
(`2026-07-26-indirect-call-visibility-design.md`,
`2026-07-28-indirect-candidate-narrowing-design.md`) did for `call_indirect`.
Exception handling is the same shape of question: a typed reachability relation
the binary answers statically but no tool surfaces. Aligns with the top item on
the feature-fit list from the same external feedback (a Rust panic-surface view,
built on panic recovery via wasm exception handling).

## The parallel

`call_indirect` asks "which functions can this reach", keyed by canonical
signature and populated by element segments. Exceptions ask two symmetric
questions keyed by **tag identity**:

- At a `throw $tag`: **which handler catches this?** — the nearest enclosing
  `try_table` whose catch clause matches `$tag` (or a `catch_all`); if none in
  this function, it escapes to callers and propagates up the call graph.
- At a `try_table`: **which throws can land here?** — the inverse: throw sites
  in the body plus callees that propagate a matching tag.

Same machinery as the dispatch work: a typed relation + a call-graph
propagation + a WAT popup + a diff. Same honest-static-analysis character
(sound where control flow is explicit, over-approximating at dynamic edges,
labeled as such).

## What the parser already gives us (verified)

- `OpCodes.Flow.Trow(tagIdx)` — `throw`, carries the tag index.
- `OpCodes.Flow.ThrowRef` — `throw_ref`, rethrows a dynamic `exnref`, no
  static tag.
- `OpCodes.Flow.TryTable(blockType, catches, code)` — `catches` is a list of
  `CatchType`: `Catch(tagIdx, labelIdx)`, `CatchRef(tagIdx, labelIdx)`,
  `CatchAll(labelIdx)`, `CatchAllRef(labelIdx)`; `code` is the nested body.
- `CallGraphBuilder` already recurses into `try_table` bodies
  (`is OpCodes.Flow.TryTable -> walk(op.code)`), so the call graph sees calls
  inside try bodies.
- Rows carry byte offsets; the virtualized WAT panel has the popup +
  hand-cursor affordance pattern from the dispatch feature.

### Prerequisite gap (small)

The exception/tag section (`WasmSectionException`, section id 13) is a parse
stub today (offsets only, no records). E1 must add tag parsing: each tag is
`(attribute: u8, typeidx: u32)`, so `tagIdx -> typeidx -> functype`. This
mirrors the other `WasmSection*` parsers and gives tags a displayable
signature ("tag $0: (i32)"). Imported tags (in the import section, external
kind tag) extend the tag index space, imports first, same convention as
funcidx.

## E1 — intra-procedural handler resolution (sound, small)

New `wasmParser/analysis/exceptions/ExceptionFlow.kt`. Pure structural walk of
a function's opcode tree (already parsed); no call graph.

- **Local handler resolution.** For each `throw $tag`, walk the enclosing
  `try_table` stack (structural nesting is explicit: a `throw` inside
  `try_table.code` is guarded by that try_table). The innermost try_table with
  a matching `Catch(tag)` / `CatchRef(tag)`, or any `CatchAll`/`CatchAllRef`,
  is the handler. Report: `caught by catch $tag at try_table @<off>` (this
  function), or `escapes this function`.
  - Matching is by tag index (after import offset), the exact analogue of
    canonical-signature matching. A `catch_all` matches every tag.
  - `throw_ref` has no static tag: it can be handled only by a
    `catch_all`/`catch_all_ref` (or an enclosing `catch_ref` that captured a
    matching exnref, not statically decidable) — reported as "dynamic
    (throw_ref): caught only by catch_all on this path, else escapes".
- **Per-function escape set.** The set of tags a function can throw without
  catching locally = throw tags with no matching enclosing handler, plus a
  `dynamicThrow` flag for uncaught `throw_ref`. This is the function's throw
  signature, the input E2 propagates.

### E1 UI

WAT popup on a `throw` / `throw_ref` row (same click + hand-cursor affordance
as the dispatch popup, NOT a Tooltip wrapper — see the selection-hierarchy
crash lesson in the dispatch spec):

- `throw $tag  (tag $0: (i32))`
- `caught by catch $0 at try_table @0x1234` → click jumps to the handler row,
  or `escapes this function` (E1 stops there; E2 continues the sentence).

## E2 — interprocedural propagation (the call-graph fan-out analogue)

Fixed-point over the existing call graph, mirroring the per-signature hub idea:

- A function's **effective thrown-tag set** = local escapes ∪ (thrown sets of
  callees reached by a call NOT wrapped in a `try_table` that catches the
  tag). A call inside a `try_table` whose catch covers tag T removes T from
  what propagates through that edge; a `catch_all`-wrapped call removes all.
- Iterate to a fixed point (tags only grow along un-guarded edges; converges
  like the dominator/propagation passes). Bounded by tags x functions.

### E2 findings and UI

- **`try_table` popup**: "catches N throw sites across M functions" (union of
  callee escape sets intersected with its catch tags), each click-navigable;
  plus "catch_all additionally covers K tags".
- **Uncaught-exception finding (the demo-grade result, parallel to N1's
  provable trap):** a throw whose tag is in the effective thrown set of an
  **exported entry point** with no handler on any path = a statically-provable
  exception that escapes the module to the host. For a Workers `fetch` handler
  this is "this panic aborts the request." Surfaced in the popup ("reaches N
  exported entry points uncaught") and as a dedicated panel.
- **Throw/panic-surface panel** (sibling of the dispatch-surface work): rows =
  functions that can throw uncaught, grouped by tag, with code sizes and an
  entry-point-reachability marker. The panic-surface view requested in the feedback.

## E3 — diff + MCP (deferred)

- **Exception-surface diff**: per tag, which throw sites and which
  uncaught-at-entry tags entered or left between two builds; cross-build
  function identity via the existing content-hash matcher (as the dispatch
  diff does). A regression signal: "release 0.2 introduced a panic that
  reaches the fetch handler uncaught."
- MCP query for throw/handler resolution and the uncaught set (paginated).

## Honesty boundaries (state in UI and docs)

- **`throw_ref` is dynamic**: the rethrown tag is not statically known, so its
  handler resolution is an over-approximation (any `catch_all`, or an enclosing
  `catch_ref` that might hold a matching exnref). Labeled "dynamic".
- **Imported functions can throw opaquely** (host/JS exceptions enter wasm as
  `exnref`). "Uncaught" is sound for wasm-originated tags; host-origin throws
  are a separate, honestly-labeled category, an imported call is treated as
  "may throw an unknown (host) exception" only when the site is not under a
  `catch_all`.
- **`catch_all` on a path makes "uncaught" false** — the analysis gets this
  right for free and it is sound.
- **Legacy EH** (the pre-`try_table` `try`/`catch`/`delegate`/`rethrow`
  encoding) is out of E1/E2 scope unless the parser already lowers it to the
  same shape; state which encoding a module uses. Modern toolchains emit
  `try_table`.

## Costs at 14k functions / 50 MB (mandatory checkpoint)

- E1: one structural pass per function, done lazily/on demand at popup time
  for a single function (cheap), or once module-wide to build escape sets for
  the panel (one opcode walk per function, same order as the call-graph build).
- E2: fixed-point over the call graph; tags-per-function is small, iteration
  count bounded by call-graph depth. Built lazily off the EDT, cached on the
  provider like `dispatchCandidates` / `callGraphResult`.
- Popups resolve per click; the panel builds once.

## Build order

1. **E1**: tag-section parsing + `ExceptionFlow` local resolution + escape
   sets + the `throw` WAT popup. Smallest, sound, self-contained; demoable on
   any EH-bearing Rust/C++ module.
2. **E2**: call-graph propagation + `try_table` popup + uncaught-at-entry
   finding + the throw/panic-surface panel.
3. **E3**: exception-surface diff + MCP (deferred).

## Test plan

- E1: hand-built modules (byte fixtures, as the dispatch tests do) —
  a throw caught by an enclosing `try_table` (matching tag), a throw caught by
  `catch_all`, a throw with no local handler (escapes), a `throw_ref`
  (dynamic). Assert handler offset / escape set per case. Plus a real
  `--release` Rust module with a `dyn`/panic path checked in.
- E2: a two-function fixture where callee throws and caller wraps the call in
  a try_table that catches (contained) vs does not (propagates to an exported
  entry, uncaught). Assert the uncaught-at-entry finding.
- Reuse the dispatch feature's UI-crash lesson: the popup uses the hand-cursor
  affordance and DisableSelection, never a Tooltip over selectable rows.

## Non-goals

- Value-flow of exception payloads (what data a thrown exception carries beyond
  its tag type).
- Proving a `throw_ref`'s dynamic tag (needs exnref points-to; out of scope,
  same spirit as the rejected whole-program memory analysis for indirect calls).
- Runtime/JS exception modeling beyond the "imported may throw" category.
