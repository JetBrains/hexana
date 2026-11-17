# WebAssembly Module Diff — Design Spec

## Overview

A diff engine that compares two WebAssembly modules and classifies every entity
(function, type, import, export, global, table, memory, data/element segment,
start, tag) as **identical / moved / modified / added / removed**. One pure-Kotlin
engine produces an immutable result that two consumers read: a Compose UI editor
tab (ships first) and a small family of MCP tools (wraps the engine afterward).

The hard, valuable part is not comparison — it is *matching*. WASM references
everything by numeric index, so a naive byte or text diff reports "everything
changed" for a one-function insertion. The engine is therefore a binary-matcher
(BinDiff/Diaphora in spirit) adapted to WASM's structure, exploiting priors that
native differs must recover: real names on imports/exports, a frequently-present
name section, explicit types, and structured (losslessly recoverable) control flow.

## Goals

- Classify all entity kinds across sections 1–13 + custom sections, robust to pure
  index renumbering (insert one function → not "everything changed").
- Serve four use cases from one engine, each lighting up as its layer lands:
  - **Size regression** — "why did my .wasm grow 200 KB" (section + per-function deltas).
  - **Supply-chain / build audit** — "what actually changed", new imports especially.
  - **Toolchain / version comparison** — structural + symbolic-name WAT diff.
  - **Optimization verification** — instruction/CFG diff within a matched function pair.
- Summary appears instantly (O(n)); deep per-function diffs compute on demand.
- Engine is IDE-free and headless-capable so the same code backs UI and MCP, and so
  it can run on every Kotlin target (mirrors the binary-edit-journal module rule).
- Two underlying modules are never both held as fully-decoded trees in heap at once.

## Non-Goals (deferred)

- Byte-level / hex diff. That is the **binary edit journal's** `JournalDiff.kt`
  (see Relationship below) — a different, lower layer. This spec is semantic/structural.
- Three-way / merge. Two-way only.
- Native-code (`.text`) diff for ELF/PE/Mach-O. The per-function CFG machinery may
  later be shared, but native binary diff is out of scope here.
- Editing either module. The diff document is strictly read-only.
- CFG visual rendering in v1 (instruction-level diff ships first; CFG view is a stub).

## Relationship to existing specs

The **binary edit journal** (`2026-05-08-binary-edit-journal-design.md`) reserves
`JournalDiff.kt — Phase 2: diff(a, b) → JournalEntry list`. That is a *byte-range*
diff producing replace/insert/delete ops — the right tool for "patch file A into
file B". This spec sits **above** it: it diffs *parsed WASM entities*, not bytes,
and its output is a classified match model, not a journal. The two do not overlap;
a future feature could lower a WASM entity diff into a journal, but neither depends
on the other today.

---

## Architecture

```
wasm/diff/                         — pure Kotlin, no IntelliJ / Compose deps
  DiffMatchEngine.kt               — passes 0–5 orchestrator
  hash/EntityHash.kt               — Round-1 content hash + structural fingerprint
  hash/Normalizer.kt               — LEB128 decode, local renumber, operand canon.
  match/NameAnchor.kt              — pass 2 (export > import > name-section)
  match/CallGraphPropagation.kt    — pass 3 (mutual-best-with-margin fixed point)
  match/FuzzyMatch.kt              — pass 4 (type-bucket → MinHash → greedy)
  match/TwoRound.kt                — operand substitution + SCC iteration
  fndiff/InstructionDiff.kt        — Myers over normalized instruction stream
  fndiff/CfgDiff.kt                — basic-block CFG matching (escalation path)
  model/ModuleDiffResult.kt        — immutable output contract (sealed types)

hexanaDocument/ (or equivalent)
  diff/BinaryDiffDocument.kt       — binds left/right ByteSource + match model
  diff/DiffDocumentManager.kt      — lifecycle owner; supports headless open

idea-plugin/ (IDE-only)
  ui/diff/WasmDiffEditor.kt        — virtual-file editor tab, 4 sub-tabs
  ui/diff/SizeImpactPanel.kt       — v1
  ui/diff/EntitiesPanel.kt         — v2
  ui/diff/WatDiffPanel.kt          — v3 (reuses virtualized WAT renderer)
  ui/diff/FunctionDiffOverlay.kt   — v4

mcp/tools/
  DiffWasmSummaryTool.kt           — orient call
  DiffWasmEntitiesTool.kt          — paginated entity list
  DiffWasmFunctionTool.kt          — single-function instruction diff
```

```
                    ┌───────────────────────────────────────┐
   left  ByteSource │  DiffMatchEngine  (pure, headless)      │
  ─────────────────▶│   0 type + anchor prep                  │
  right ByteSource  │   1 exact content-hash   → IDENTICAL/MOVED  ModuleDiffResult
  ─────────────────▶│   2 name anchoring       → MODIFIED      │──────────────┐
                    │   3 call-graph propagation               │  (immutable) │
                    │   4 fuzzy fingerprint                     │              │
                    │   5 residual classify    → ADDED/REMOVED  │              │
                    │   (lazy) per-function Myers / CFG          │              │
                    └───────────────────────────────────────┘              │
                              ▲                                              ▼
        BinaryDiffDocument ───┘                        ┌──────────────────────────────┐
        DiffDocumentManager.open(left,right)           │ A: Compose UI tab (ships 1st) │
        — headless for MCP, editor tab for UI          │ B: 3 MCP tools (wraps engine) │
                                                        └──────────────────────────────┘
```

The engine is pure and headless. `DiffDocumentManager.open(left, right)` works with
or without an editor tab; the MCP path opens headless, leases the result, releases on
completion. UI and MCP read the **same** immutable `ModuleDiffResult`.

---

## Locked-in decisions

### 1. Never hash raw bytes — decode first

LEB128 is variable-length: a function that grows past a 7-bit boundary re-encodes
every downstream `local.get N`; inserting imports widens funcidx encodings. All
hashing and diffing operates on **logical decoded values** — a normalized sequence of
`(opcode, [decoded-immediates])` tuples — never on raw byte slices.

### 2. Two-round hashing breaks the index cycle

A function's identity depends on which functions its `call` operands resolve to —
which is the matching we are computing. Resolve the cycle by iterating:

- **Round 1** — hash with cross-entity index operands *canonicalized away*
  (`call ?`, `ref.func ?`, `global.get` → placeholder). Local indices, branch
  labels, and instruction-intrinsic immediates (memory offset, alignment, lane) are
  function-local and hashed as-is. Also compute a cheap **structural fingerprint**:
  type hash, instruction count, control-flow nesting profile, call count, local
  type list. The fingerprint has no unresolved references and seeds matching.
- **Round 2** — re-hash, substituting each `call N` with its **matched partner's
  stable id**. This promotes "looks modified (operands differ numerically)" →
  IDENTICAL/MOVED. Iterate over the SCC-condensed call graph (leaf SCCs first; within
  a recursive SCC, iterate to a fixed point on provisional ids).

Convergence: monotone (a confirmed match is never retracted; refined hashes only
merge more buckets, never split confirmed ones), finite height → terminates. Cap at
**3 rounds** (5 hard limit); marginal yield drops sharply after round 2. Only
substitute **high-confidence** (anchor / strong-propagation) partners into round-2
hashes, so a weak match cannot poison the hash of a function that calls it.

### 3. Matching pipeline — ordered, monotone passes

Each pass consumes the prior pass's unmatched residual and only *adds* matches.

| Pass | Mechanism | Confidence | Cost |
|---|---|---|---|
| 0 | type match (structural hash, rec-groups as a unit) + build name maps | — | O(n) |
| 1 | exact Round-1 hash bucketing; same idx → IDENTICAL, diff idx → MOVED | 1.0 | O(n) |
| 2 | name anchoring: export > import `module.field` > name-section | 0.85–1.0 | O(n) |
| 3 | call-graph propagation: anchored pairs vote for neighbours by callsite position; **mutual-best-with-margin only** | 0.6–0.9 (depth-decayed) | O(E·rounds) |
| 4 | fuzzy: type-bucket gate → MinHash top-K prefilter → **greedy mutual-best** | ≤0.8 | O(k·K·log) |
| 5 | residual → ADDED (right-only) / REMOVED (left-only) | — | O(n) |

Removing the identical/moved set first (pass 1) is the biggest win: on a wasm-opt
before/after or a compiler point release, 70–95% of functions are byte-identical
post-canonicalization and never reach the expensive passes.

**Pass-1 hash collisions** (tiny trampolines, identical monomorphizations) form an
equivalence class on each side — do *not* pair 1:1 here; defer the bucket to pass 3
for context-based (caller/callee) disambiguation, else to a stable positional pairing
in pass 5, flagged "interchangeable".

**Pass 3 fixed point**: worklist of confirmed pairs; matched pairs propose candidate
edges to unmatched neighbours weighted by (propagating-pair confidence × signature
agreement × callsite-position match); confirm only **mutual-best with vote margin**;
terminate when a full sweep confirms nothing new (3–6 sweeps typical). The anti-cascade
guard (mutual-best-with-margin) is what stops one wrong anchor from cascading.

**Pass 4 scale defense — no Hungarian on 14k².** A dense 14k×14k cost matrix is ~1.5 GB
and O(n³) is untenable for the adversarial near-empty-anchor case. Hard type-index gate
(no cross-signature candidates) + MinHash top-K (~8) sparse candidate graph + greedy
mutual-best with a similarity floor (~0.5–0.6). Hungarian is reserved as an optional
refinement within a single small type bucket (≤200 nodes) when max precision is asked.

### 4. Function-level diff operates on *normalized decoded* instructions

The document layer owns slicing (it knows byte ranges); the **engine decodes and
normalizes** the slice before diffing — it never byte-compares raw `ByteSource`
slices (that would make `--reorder-locals` or any LEB128-width change look like a
full rewrite). Normalization: renumber locals to first-use order, substitute matched
stable-ids for call operands, treat branch labels as the relative depths they already
are. Then:

- **Default: Myers diff (O(ND))** over the normalized instruction stream. LCS is the
  right tool for optimization verification — mostly-same sequence with local edits —
  and WASM's structured control flow makes linear order meaningful (unlike native).
- **Escalation: CFG basic-block matching** when block reordering is detected (poor
  linear alignment). Blocks matched by recursive fingerprint (own opcode hash + multiset
  of successors' hashes), robust to reordering; Myers runs *inside* each matched block.

Both are **lazy**: each MODIFIED function carries a `diffHandle.resolve()` computed on
demand and cached (LRU, soft-referenced). The whole `ModuleDiffResult` is computable
without ever resolving a handle.

### 5. Output contract — one immutable model, both consumers read it

```
ModuleDiffResult
├─ summary    : per-class {identical, moved, modified, added, removed} + section size deltas
├─ matches    : List<EntityMatch>
├─ added      : List<UnmatchedEntity>     // right-only  (ADDED)
├─ removed    : List<UnmatchedEntity>     // left-only   (REMOVED)
└─ provenance : { passesRun, rounds, capsHit, timing }

EntityMatch
├─ entityClass    : Function | Type | Import | Export | Global | Table | Memory | Tag | Element | Data | Start
├─ idA, idB       : EntityRef { index, stableId }
├─ classification : IDENTICAL | MOVED | MODIFIED
├─ confidence     : Float [0,1]
├─ evidence       : set of { HASH_EXACT, EXPORT_NAME, IMPORT_NAME, NAME_SECTION, PROPAGATED(depth), FUZZY(score) }
├─ names          : { exportName?, importModuleField?, nameSectionName? }
├─ indexDelta     : Int                   // idB.index − idA.index; nonzero ⇒ renumbered
└─ diffHandle     : FunctionDiffHandle?   // present only for MODIFIED functions; lazy

UnmatchedEntity
├─ entityClass, id, names
└─ candidates : List<{ otherId, score, rejectedReason }>   // top-K near-misses
```

`indexDelta`, `evidence`, and `candidates` are **first-class**: the MCP tool must
*explain* a match without re-deriving it ("matched by export name, body changed, moved
412→598"), and unmatched-with-candidates ("probably one of these three") is more honest
than a bare ADDED/REMOVED. Sealed classifications give exhaustive `when` downstream
(per the project's sealed-type rule).

### 6. Entity identity & name derivation — one scheme everywhere

Stable key, in priority order, for a function at **global** index `g` (the name section
function subsection is global-indexed: imports occupy the low indices first):

1. name-section function name (`g` looked up directly)
2. export name (scan exports for `funcidx == g`)
3. import `module.field` (only when `g < importFuncCount`)
4. synthesized `$func{g}` fallback

Keys 1 and 3 are fully stable under renumbering; export names are stable while the
name is unchanged; the `$func{g}` fallback is index-dependent → matches relying on it
are flagged low-confidence. MCP serializes identity on the wire as `head::func[1042]`
with the name attached — the same identity, just a transport form.

The same name derivation feeds **symbolic-WAT rendering** (v3): rendering with `$name`
instead of numeric indices makes unchanged functions render textually identical despite
index churn, which is the entire point of the toolchain-comparison view.

### 7. Document model, laziness, and addressing

- **`BinaryDiffDocument`** binds `left`/`right` `BinaryDocumentHandle` (each a `ByteSource`
  + structural summary + `VirtualFile`) and the lazily-populated match model. Identity is
  `(leftUrl, rightUrl)` → re-openable across IDE restarts. `DiffDocumentManager` is the
  single lifecycle owner and supports **headless** open for MCP.
- **Three parse tiers**: (1) structural summary — section ranges, counts, name tables —
  eager, <500 KB for Skiko; (2) per-entity bodies — demand-loaded via `ByteSource.slice`,
  soft-ref cached; (3) CFG / data-flow — on demand, not cached. Neither side's full module
  tree is held concurrently.
- **Addressing is two-layer**: entity identity (stable, symbolic) is the diff unit; byte
  ranges (`leftByteRange?`, `rightByteRange?`) are derived per side from each side's own
  structural summary — never computed as a delta between sides, so LEB128-width changes are
  invisible to the mapping. A per-side interval tree gives byte-offset → entity for
  hex-view click-through.
- **VFS invalidation is soft**: on a VFS change the handle flips an atomic `stale` flag and
  posts a lightweight "file changed — reload?" banner via `invokeLater`. The callback does
  no heavy work (avoids mistaking a VFS RefreshSession for a render hang). In-flight reads
  against the mmap complete correctly; new tier-2/3 loads are gated until reload.
- **Threading**: `matchStructural` on `Dispatchers.IO`; result published via `AtomicReference`;
  all subsequent reads are lock-free against the immutable model.

---

## Consumers

### A. Compose UI editor tab

Opens as a virtual-file editor tab titled `old.wasm ↔ new.wasm`. Entry points:
"Compare With…" context action on a `.wasm` (mirrors IntelliJ's Git "Compare With"),
a tool-window toolbar button (`AllIcons.Actions.Diff`), and a Tools-menu action. Lands
on the **Size Impact** tab by default. Four sub-tabs map to the four use cases:

- **Size Impact** (v1) — sorted size-delta table grouped by section, Code expanded;
  summary header `+204 KB: code +180 KB (+12 fn), data +20 KB`; identical rows excluded
  (footer count only); treemap a secondary toggle (pre-computed off the composable body).
- **Entities** (v2) — filterable table (change-kind, section, min-size-delta, speed search),
  change-kind shown as text labels + prefix (not colour-only); "moved" shown only at high
  confidence (else degrades to modified), old→new index in tooltip; **new imports get a
  supply-chain annotation** (`⚠ 2 new host imports`, heuristic on WASI/`env.*` namespaces).
- **WAT Diff** (v3) — a **single shared `LazyColumn`** with two columns per row (not two
  synced lists), symbolic names on by default, scroll-sync to matched-function boundaries,
  jump-to-function dropdown. WAT lines pre-rendered to an `ImmutableList` on `Dispatchers.IO`;
  zero per-line work, no tree walks, in the composable body (prior-incident discipline).
- **Function drill-in** (v4) — an overlay (not a tab) from a MODIFIED row; instruction Myers
  diff with collapsed identical runs; CFG tab a "coming soon" stub on first ship.

States: `Initializing → ComputingSummary → SummaryReady(matchingInProgress) → FullyReady`,
plus `Identical` and `Error(message, retry)`. The summary bar fills from the fast first
pass while matching runs in the background ("Matching… 3,241 / 14,204 [Cancel]").

### B. MCP tools (three)

- **`diff_wasm_summary`** — orient call, no pagination. Returns `sizeDelta`, sparse
  per-section deltas, full per-class `totals`, and **`newImports[]` / `removedImports[]`
  promoted to top-level** (answers the supply-chain audit in one call), plus
  `topChangedBySize`. Most size-regression and audit questions resolve in 1–2 calls.
- **`diff_wasm_entities`** — paginated (`limit` default 100, max 1000; opaque offset cursor
  over a stable `(changeKindPriority, kindPriority, headIndex)` sort; filters hashed into
  the cursor). Filters: `kind`, `change`, `minSizeDelta`. Each item carries `id`, `change`,
  `name`, `baseSz`/`headSz`/`delta`, `confidence`, and `module` for imports/exports.
- **`diff_wasm_function`** — single function by `id`; unified-diff `blocks` (`context` /
  `added` / `removed`) with `instrCount` deltas. CFG omitted in v1 (`includeCfg?` later).

Short keys throughout (`baseSz`, not `baseModuleSize`) — token economy at 14k-entity scale.
`confidence` is a security signal: a `modified` entity at confidence 0.4 is effectively new
code sharing a few instructions; agents should scrutinize it like an `added` entity.

---

## Build order

Each phase ships standalone and delivers a complete use case.

| Phase | Engine needs | Delivers |
|---|---|---|
| **v1** | passes 0–2 + 5 only (cheap matching: hash + name; **no** propagation/fuzzy) + section sizes | Size Impact tab + summary bar — answers size regression, the most-requested case, before the hard algorithm stabilizes |
| **v2** | full matching (passes 3–4, two-round hashing) | Entities tab + filters + supply-chain new-imports lens |
| **v3** | symbolic-WAT rendering (reuses name derivation + virtualized WAT panel) | WAT Diff tab |
| **v4** | normalized Myers / CFG escalation | Instruction drill-in overlay (CFG stub) |
| **MCP** | stable engine | the three tools above |

The unlock: **v1 needs only cheap matching** (exact hash + name anchor; unmatched →
added/removed), so per-function size deltas and the summary are computable without
propagation or fuzzy matching. The UI shows the summary instantly while anything deeper
runs in the background.

---

## Parser prerequisites

Two correctness items the engine depends on; both are independent of this feature and
should be verified against the current parser before v2:

- **Element-segment flag 6** (`6 x e_o rt e*`): the explicit `reftype` byte `rt` between
  the offset expression and the element-expression list must be read, or the cursor
  misaligns and every subsequent entry in the segment decodes to garbage.
- **Legacy exception handling**: `delegate` terminates its `try` with **no** trailing
  `end`; `catch` / `catch_all` are structural delimiters, not free-standing opcodes. The
  normalizer must implement the block grammar exactly or every older-Emscripten body
  misparses. A legacy-EH vs finalized-EH build shows real `try/catch → try_table`
  changes — surface them as instruction diffs, do not suppress them.

---

## Open questions (to lock before v2)

1. **Match-tuning surface** — expose match thresholds (fuzzy floor, propagation margin)
   in a Settings sub-tab, or keep them internal constants until a real module needs tuning?
2. **Stripped modules** — when the name section is absent and exports are few (the
   adversarial near-empty-anchor case), do we degrade gracefully to size-only diff, or
   spend the fuzzy budget? Affects the pass-4 cap.
3. **Cross-proposal diffs** — comparing a GC/EH-enabled build against a baseline without
   them: confirm the type-section rec-group handling and tag index space are modelled
   before claiming general correctness.