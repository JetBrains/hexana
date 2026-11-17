# Indirect-Candidate Narrowing: Provenance, Grouping, Vtable Mining — Design Spec

Date: 2026-07-28
Status: P1 + P2 implemented; P3 open
Origin: continuation of the narrowing ladder in
`2026-07-26-indirect-call-visibility-design.md` (N1 constant-index resolution
shipped in 84f799b0). Motivating case: `core::fmt::write`'s `call_indirect`
sites show 68 signature-matched candidates; the index is loaded from a Rust
`dyn` vtable, so N1 cannot apply. This spec covers the next three slices:
P1 operand provenance, P2 popup grouping/filtering, P3 heuristic vtable
mining (the ladder's N2).

## P1 — operand provenance (sound, small)

When the index cannot be resolved to a constant, DESCRIBE where it comes
from instead of staying silent. The popup header gains one line:

```
index: i32.load(+12) of local 1        <- vtable-shaped dispatch
index: local 2 (parameter)             <- flows in from callers
index: <not statically evident>        <- everything else
```

### Design

Generalize `resolveConstantIndirectIndex` into a provenance resolver
(`wasmParser/analysis/dispatch/IndirectIndexAnalyzer.kt`):

```kotlin
sealed class IndirectIndexProvenance {
    data class Const(val slot: Int)                      // N1, unchanged
    data class Load(val byteOffset: Int, val base: String) // i32.load offset=O (base)
    data class Local(val localIdx: Int)                  // local.get N
    data object Unknown
}
```

Same walk discipline as N1: only the immediately preceding instruction at
the same block level is inspected; for a `Load`, the instruction before the
load is described one level deep (`local N`, `global N`, `load(...)`,
`<expr>`), no resolution, no recursion past depth 2. The strings are
display-only; `Load.byteOffset` is the machine-readable part P3 consumes.

Honesty: the provenance line states shape, not meaning. It does NOT claim
"vtable" — that interpretation is P3's job (a load at +12 off a local is
overwhelmingly a vtable method slot in Rust code, but the analyzer cannot
know the producer).

### Cost

One backwards walk per popup open (already the N1 cost). No new state.

## P2 — popup grouping and filtering (pure UX)

68 candidates are unreadable as a flat list, and even a mined set benefits
from structure.

### Grouping

Group candidates by demangled trait method. Rust demangled names carry the
structure: `<T as core::fmt::Display>::fmt::h…` groups under
`core::fmt::Display::fmt`; names without the `<T as Trait>::method` shape
group under their last non-hash path segment; unnamed candidates under
`func[N]`. The popup renders collapsible group headers sorted by group size:

```
call_indirect (i32, i32) -> i32: 68 statically known candidates
index: i32.load(+12) of local 1
  Display::fmt (31)          <- collapsed by default when >1 group
  Debug::fmt (24)
  Write::write_str (6)
  other (7)
```

Grouping is display-only name parsing (hash suffixes stripped for the key,
never for identity — a name is not a unique key; rows stay keyed by funcidx).
Single-group results render flat, exactly as today.

### Filtering

A type-to-filter field at the top of the popup (same interaction and
component style as `WasmGotoFuncPopup`), case-insensitive contains over the
candidate labels. Filtering flattens groups while active.

### Size annotation

Each candidate row shows its code size (`codeOffsets[codeIdx].size`,
already at hand); rows sort by size descending within a group. Retained
size via the dominator tree is explicitly deferred (it would force the
call-graph + dominator computation on first popup open).

## P3 — heuristic vtable mining (the ladder's N2)

For load-fed sites, intersect the site's load offset with vtables harvested
from data segments. Labeled heuristic, one click back to the full set.

### Why it works

Rust `dyn` vtables on wasm32 are static data with a fixed layout, and
function "pointers" in wasm ARE table slots:

```
+0   drop_in_place   table slot (signature (i32) -> ())
+4   size            usize
+8   align           usize, power of two
+12  method 0        table slot
+16  method 1        table slot
...
```

A `call_indirect` whose index is `i32.load(+12)` off a vtable pointer
dispatches method 0 of SOME vtable; the candidate set is column +12 across
all vtables whose method-0 slot matches the site's signature.

### Mining algorithm (wasmParser, `dispatch/VtableMiner.kt`)

One pass over active data segments with constant offsets (same convention
as the element-segment slot image):

1. Read the payload as 4-aligned little-endian i32 words.
2. A window fingerprints as a Rust vtable when: word0 is a valid table slot
   whose function's canonical signature is `(i32) -> ()` (drop glue); word1
   is a plausible size (0 <= size < 2^24); word2 is a power of two <= 4096;
   words 3..k are valid table slots while their columns stay
   signature-consistent across the run. Windows shorter than one method are
   discarded.
3. Record each mined vtable as (data offset, method-column slots).

Site-side narrowing: provenance `Load(byteOffset = O)` with O >= 12 selects
column `(O - 12) / 4 + 3`; the narrowed set is
`{ functionAtSlot(vt[column]) }` over mined vtables, intersected with the
site's signature candidates. Empty intersection = no narrowing (fall back,
never show an empty heuristic result as truth).

Popup presentation:

```
narrowed to 12 of 68 (vtable heuristic, method slot 0, 12 vtables) [show all]
```

### Honesty and limits

- Integer coincidences can fingerprint non-vtables; signature-consistency
  columns and the power-of-two align check bound the false-positive rate but
  do not eliminate it. The label says "heuristic", and [show all] is one
  click.
- The fingerprint is RUST's layout. C++/Emscripten vtables (offset-to-top,
  RTTI slot, no size/align) do not match it; they get no narrowing in this
  phase. A generic weaker pattern (any run of same-signature-column slots)
  is possible later at lower confidence, flagged separately.
- Vtables constructed at runtime, or in passive data segments applied by
  `memory.init`, are invisible (same static boundary as everything else).
- Producer detection is NOT a gate: mining runs on any module, it simply
  finds nothing where the pattern is absent.

### Cost at 14k functions / 50 MB

One linear scan over active data segments (skiko-scale: tens of MB) with
cheap integer predicates, lazy on the provider, off the EDT, computed only
on the first load-fed popup open. Mined vtables are int arrays. Site-side
narrowing is a per-popup set intersection.

## Non-goals

- Reachability-based pruning of the table (circular: table entries root
  themselves) and whole-program points-to over linear memory (the parent
  spec's N3 rejection stands).
- A dynamic tier (run under wasmtime/wazero with an instrumented table,
  record observed targets per site) is a real idea but a separate feature
  with its own spec, not part of this slice.
- Diff/inline-expander integration of narrowing results (popup only, like N1).

## Build order

1. **P1 provenance** — extend the analyzer, thread the provenance line into
   the popup. Smallest, sound, and immediately explains the 68.
2. **P2 grouping + filter + size** — popup-only UI work, independent of P3.
3. **P3 vtable mining** — consumes P1's `Load.byteOffset`; lands behind the
   heuristic label with [show all].

## Test plan

- P1: analyzer unit tests per shape (const — existing; load with offset and
  base kinds; local; unknown), on synthetic opcode lists plus the fixture.
- P2: pure functions for group-key extraction (Rust `<T as Trait>::method`,
  hash stripping, fallback segments) unit-tested; popup exercised in runIde.
- P3: miner unit tests on a hand-built data segment containing two valid
  vtables, one decoy (align not a power of two), and one truncated window;
  end-to-end narrowing on a small Rust `dyn` fixture (two trait impls, one
  dyn call site) compiled with `--release` and checked in; the 68-candidate
  `core::fmt::write` site on edge-render-pre-opt as the manual scale check.
