# Indirect-Call Visibility — Design Spec

Date: 2026-07-26
Status: draft
Origin: Guy Bedford (an external partner) demo feedback, "it would be interesting to make
indirect calls more visible". Extended in-session with the WAT-view and diff-view
asks.

## Overview

Today every `call_indirect` in a module funnels through one synthetic
`CallGraphNode.IndirectCallHub` rendered as `<indirect calls>` in the caller-paths
and dominators panels, and the virtualized WAT view offers no navigation from a
`call_indirect` site at all. The information to do better is static and cheap: the
instruction's type immediate plus the element segments determine the exact set of
functions a site can dispatch to.

This spec introduces one shared, signature-keyed **candidate index** in
`wasmParser` and surfaces it on four existing surfaces:

1. **Virtual WAT**: Cmd+B on a `call_indirect` row opens a bounded candidates
   popup; entries navigate like the existing follow-call shortcut.
2. **Caller paths / dominators**: split the single hub into per-signature hubs
   (`<indirect: (i32, i32) -> i32>`), preserving the linear edge bound.
3. **Build diff**: a "dispatch surface" section showing, per signature, which
   candidates entered or left the table between two builds.
4. **MCP**: a candidates query, and a signature-keyed variant of the existing
   slot-level `dispatch_table_diff`.

## Core concept: canonical signature, not typeidx

The `call_indirect` runtime check is structural: any table entry whose declared
function type matches the expected signature passes, even if it was declared under
a different type index. Two structurally identical types at different indices are
interchangeable. Everything in this feature is therefore keyed by **canonical
signature** (the params/results value-type lists), never by raw typeidx. Keying by
typeidx would silently drop candidates, the same failure family as the diff
engine's positional-matching bug (`entities/wasm-diff-positional-fn-mismatch`).

Refinement for typed-funcref tables (WasmGC): when the table's element type is a
concrete `(ref $t)` rather than plain `funcref`, the candidate set narrows to
entries compatible with `$t`. V1 may treat such tables as funcref (over-approximate)
but must not crash on them; the narrowing is a follow-up.

### Honesty boundary (state it in UI and docs)

The static candidate set covers element-segment entries (plus, optionally, the
table's declared initializer). Tables mutated at runtime via `table.set` /
`table.init` / host writes make the set an under-approximation. Rare in
wasm-bindgen and Emscripten output, but the UI copy must say "statically known
candidates", not "possible targets". `call_ref` targets flow through `ref.func`
values, not tables, and stay out of scope (matches `CallGraphBuilder`'s twiggy
semantics, where `ref.func` targets are direct edges already).

## Component 1: CandidateIndex (wasmParser, commonMain)

New `wasm/analysis/dispatch/CandidateIndex.kt` next to the callgraph package.

- **Input**: element segments, function → typeidx mapping, type section, table
  element types.
- **Output**:
  - `Map<CanonicalSig, IntArray>` — sorted funcidx candidates per signature.
  - `funcidx → slots` reverse view ("pinned by element segment N, slot M"), which
    also feeds a later "pinned by table" badge in the garbage view.
  - Per-signature site counts once Component 2 lands.
- **Construction**: one pass over element segments, one over the type section.
  O(table entries + types) time and memory. Built lazily on first use, cached with
  the same lifecycle as the call graph, computed off the EDT (`Dispatchers.IO`).
- Passive segments are included but flagged (`reachableVia = TABLE_INIT`) so the UI
  can distinguish "in the table at instantiation" from "could be copied in".

Cost checkpoint at 14k functions / 50 MB (mandatory per project rules): Skiko-scale
element sections hold tens of thousands of entries; the index is a few hundred KB
of int arrays and one linear pass over already-parsed segments. No new decoding of
code bodies. Large C++/Emscripten modules (the case that exploded the naive call
graph) have big tables but few distinct signatures; the index stays flat arrays.

## Component 2: per-signature hubs in the call graph

`CallGraphBuilder.addEdgesForFunction` currently reduces indirect calls to a single
boolean (`hasIndirectCall`), discarding the type immediate. Change the walk to
collect the set of distinct canonical signatures used by `call_indirect` /
`return_call_indirect` in the function.

Replace the single `IndirectCallHub` with one hub node per signature. The
hundreds-of-millions-of-edges blowup that motivated the hub (see the KDoc on
`CallGraphNode.IndirectCallHub`) stays prevented, because the bound is unchanged in
shape:

- caller → hub edges: at most one per (function, signature) pair, bounded by the
  number of indirect call sites;
- hub → candidate edges: the table entries, partitioned by signature, summing to
  exactly the table size.

Total edges remain O(indirect sites + table entries), the same linear bound as the
single hub, just partitioned. A signature hub with zero candidates is still shown:
a `call_indirect` that can only trap is a finding, not noise.

Consumer audit before coding (sealed-type rule): every `when` over `CallGraphNode`
must be enumerated via find-references on `IndirectCallHub`. Known today:
`WasmPanelCallerPaths.kt:123`, `WasmPanelDominators.kt:350`, plus whatever the MCP
`FunctionCallGraphQuery` renders. Node identity: hub ids move from the single
`INDIRECT_CALL_HUB_ID = -2` to a reserved negative range or a side table; anything
that assumes "id -2 = the hub" must be found and updated.

Dominator consequence (the payoff): a function retained solely through one
signature's hub now reports "retained by indirect callers of (i32) -> i32" instead
of the undifferentiated `<indirect calls>`, and the garbage view can explain "not
removable: element segment pins it".

## Component 3: virtual WAT candidates popup

`WasmPanelWatVirtualized` precedent: on a `call N` row, Cmd+B extracts the funcidx
and fires `onFollow(FollowTarget(funcIdx, section))` without inflating the row to
an edit field. Mirror it for `call_indirect`:

- **MVP**: Cmd+B (and a context action) on a row whose `info.op` is
  `OpCodes.Flow.CallIndirect` / `ReturnCallIndirect` resolves the canonical
  signature from the type immediate and opens a popup listing candidates
  (name, funcidx, code size). Selecting an entry navigates through the existing
  `FollowTarget` path. The popup list is virtualized and bounded: hot signatures in
  big modules can have thousands of entries, so render a scrollable virtual list
  with a count header, never an unbounded inline expansion. Names are display
  labels only, keyed rows use funcidx (names repeat; a symbolic name is not a
  unique key).
- **Phase 2, inline annotation**: a display-only trailing token
  `;; N candidates` on `call_indirect` rows. It must be a synthetic token excluded
  from the row's `watText`, because that text feeds `commitRowEdit` parsing;
  the insert path (`commitInsertEdit`) and delete/locals paths must be checked for
  symmetry at the same time (edit-pipeline path-symmetry rule). This step is
  deliberately separate from the MVP so a rendering mistake cannot regress editing.
- Index computation happens on first invocation, off the EDT; the popup shows a
  progress placeholder if the index is still building.
- VS Code parity: the webview WAT renderer is a separate implementation; parity is
  a follow-up, tracked but not in scope here.

## Component 4: dispatch-surface diff

A new section in the WASM build diff answering "did any candidate list change".

- **Identity across builds comes from the diff engine's match model**
  (`ModuleDiffResult`, the renumber-invariant `FunctionNormalizer` hash + match
  passes), NOT from name comparison. This is what makes the section quiet by
  default: wasm-bindgen closure shims rename every build
  (`...__invoke__h<hash>`), and content-hash matching already pairs them, where
  name matching would report full churn. Unmatched-by-engine candidates fall back
  to displaying as added/removed, which is the truthful answer.
- Per canonical signature, report:
  - candidate count before/after;
  - **entered**: functions in the current candidate set whose matched counterpart
    was absent from the baseline set (split into "new function" vs "existing
    function newly added to the table");
  - **left**: the symmetric cases;
  - the number of `call_indirect` sites per side using that signature.
- Sort by absolute candidate delta; v1 shows candidate code sizes, retained-size
  attribution via the dominator tree is a later enrichment.
- Placement: a grouped table alongside the existing diff panels
  (`WasmEntityDiffPanel` family). Summary is O(signatures), computed with the
  cheap diff tier; per-signature drill-down resolves names on demand.
- MCP: add a signature-keyed operation (working name `dispatch_surface_diff`).
  The existing `dispatch_table_diff` stays as the slot-level view; its records
  gain a canonical-signature attribute so agents can pivot between the two.
  Both paginate; candidate lists are capped per record with a continuation.

## MCP additions

- `indirect_candidates` query: given a function (or signature), return the
  candidate set with names and sizes, paginated (>1000-item rule).
- `trace` with `indirect_call` relation gains per-signature fan-out instead of the
  undifferentiated hub.

## Build order

1. **CandidateIndex + WAT popup.** Smallest demonstrable slice, and the direct
   answer to the feedback; this is the artifact to show Guy Bedford.
2. **Per-signature hubs** in caller paths + dominators (+ garbage-view "pinned by
   element segment" badge from the reverse view).
3. **Dispatch-surface diff** section + MCP `dispatch_surface_diff`.
4. **Inline `;; N candidates` annotation** and VS Code WAT parity.

Each phase verifies on real artifacts before moving on: the workers-rs
`edge-render-worker` pre/post pair (externref + EH heavy, table churn from
wasm-opt), Skiko (14k functions, hot signatures), and a name-stripped optimized
build (matcher fallback path). Compile all KMP targets for the `wasmParser`
commonMain changes.

## Non-goals

- `call_ref` / typed-funcref dataflow analysis (only the table-element-type
  narrowing refinement is planned).
- Runtime/dynamic table tracking. Static under-approximation, stated honestly.
- Devirtualization or optimization suggestions. Visibility only.
- Native binaries. Indirect-call visibility for machine code is a different
  problem (jump tables, relocations) and out of scope.

## Dispatch tab: user-facing description (docs source material)

Shipped as phase 3. This section is written to be lifted into user
documentation (docs site, marketplace changelog, release notes) with light
editing; keep the claims as stated, they are verified.

### What it answers

The Dispatch tab compares two builds of a module and answers: did the set of
functions reachable through `call_indirect` change, and how. Every
`call_indirect` dispatches through a table; which functions it can reach is
determined statically by the element segments (which functions sit in the
table) and the call site's type (only entries with a structurally matching
signature pass the runtime check). That set, grouped by signature, is the
module's dispatch surface. It matters for size: a function in the table is
pinned, wasm-opt cannot prove it dead, so it survives every optimization
pass. When a rebuild grows the surface, the module quietly got harder to
shrink, and nothing in a plain size report says why.

### Reading the table

One row per candidate that changed state, grouped by signature:

- **Signature**: the canonical type, e.g. `(i32, i32) -> i32`. Structural,
  not type-index-based; two type-section entries with the same shape count
  as one signature.
- **Candidates** (`768 -> 593`): distinct functions dispatchable through
  this signature, baseline vs current build.
- **Change**: Entered (became reachable through the table, green) or Left
  (stopped being reachable, red).
- **Function**: `new` means the function did not exist in the baseline;
  `removed` means it is gone from the current build; `existing` means the
  function is present in both builds and only its table membership changed.
  "Existing + Entered" is the silent-pinning event: a dependency bump or
  codegen change put an already-present function into the table, and from
  then on wasm-opt must keep it.
- **Candidate**: the function name. Clicking a row opens the side-by-side
  WAT detail, with both versions when the function exists in both builds.

The footer ("N signatures changed, M unchanged") says how much of the
surface is stable.

### Why identity is the hard part

Function indices renumber completely on any rebuild, and wasm-bindgen's
closure shims change their names every build (`...::h<hash>`). Comparing by
index or name would report total churn every time. The tab instead reuses
the diff engine's content-hash function matcher (the same machinery behind
the Entities tab): functions pair by normalized body content, name anchors,
call-graph propagation, and a fuzzy fallback. Only what the matcher cannot
pair shows as new/removed. Per-signature caller counts come from the
call graph's per-signature indirect-call hubs.

### A verified example

Between a workers-rs build and its `wasm-opt -Oz` output, the element
segments are identical in size (1,415 entries), yet candidate counts drop
sharply (768 to 593 on the hottest signature). Duplicate-function
elimination merged ~360 identical functions, leaving many table slots
pointing at one shared body: the table did not shrink, the dispatch surface
did. Neither a size treemap nor an entity list expresses that effect.

### Limits to state honestly in docs

- Candidates are statically known: runtime `table.set` writes are invisible.
- Typed-funcref (WasmGC) signatures join by rendered heap-type index, so
  their cross-build identity is only as stable as the type index.
- An optimized plus name-stripped comparison is the matcher's hardest
  regime and lists long entered/left tails; a same-flags release-to-release
  diff reads much quieter.

One-sentence summary: the diff now tells you when a rebuild silently changed
what your `call_indirect`s can reach, and which of those functions became
unremovable in the process.

## Risks

- **Sealed-node consumers.** Splitting the hub changes a shared sealed hierarchy;
  the consumer enumeration above is the gate before any code.
- **Edit-pipeline regression** from inline annotations, mitigated by phasing them
  after the popup MVP and by the path-symmetry check.
- **Hot-signature popups** (thousands of candidates) must stay virtualized and
  paginated end to end, including the MCP layer.
- **Signature canonicalization subtleties** with GC types (structural equivalence
  across rec groups). V1 canonicalizes core value types; GC-typed signatures
  compare by resolved structure, with a test on a WasmGC artifact (Kotlin/Wasm
  output) before claiming support.
