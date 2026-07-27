# Source-Location Navigation for WASM — Design Spec

Date: 2026-07-27 (corrected same day after implementation-time findings)
Status: phase A implemented
Origin: user request — link functions (Functions/Exports tabs) and instruction
offsets (virtualized WAT) to source locations via source map or DWARF; show
`filename:line` in a right-click context menu; clicking navigates to the file.

## Overview

For a wasm module with debug information, every code offset has a source
location. Hexana already had a full resolution chain; this feature surfaces it
on the WASM views and fixes the chain where it was silently broken.

Surfaces:

1. **Functions tab / Exports tab**: per-row context menu gains a
   `Go to Source (dir/file.rs:123)` item; clicking navigates to the file at
   that line. Exports: function exports only.
2. **Virtualized WAT**: instruction rows (any row carrying a byte offset) get
   a right-click menu with the same item, resolved per instruction; the
   dispatch-candidates action for `call_indirect` rows joins the same menu.
3. **Hex view**: the pre-existing "Open in sources" item now shows the
   resolved `file:line` in its label and shares the navigation helper.

## What actually exists (corrected after implementation)

The initial exploration under-reported the codebase. Ground truth:

- `WasmBinaryProvider.sourceLocationProvider: HexanaSourceLocationProvider`
  existed (DWARF-backed via `DwarfSourceLocationProvider`), and
  `HexanaFileEditor` overrode it per editor with a source-map fallback.
- A COMPLETE wasm source-map parser existed at
  `idea-plugin/sourcemap/SourceMapParser.kt` (gson `JsonReaderEx` +
  `Base64VLQ`, binary-searched `MappingEntry` table), loaded from a
  `<file>.map` SIDECAR by `initSourceLocationProvider` with VFS-existence
  filtering of `sources`. The `sourceMappingURL` custom section itself is NOT
  yet consulted (that is phase B).
- The hex view already had an "Open in sources" context item.
- DWARF `.debug_line` v4/v5 parsing and `DwarfSourceLocationResolver` were
  complete (DWARF64 v5 header TODO upstream).

## Bugs found and fixed in phase A

The existing chain was wired but broken end-to-end; three defects hid behind
each other:

1. **CU-range gating returned null on rustc output.**
   `DwarfSourceLocationResolver.resolveLocation` finds the compilation unit
   by `DW_AT_low_pc`/`high_pc`; rustc emits `DW_AT_ranges` instead, so every
   lookup returned null. Fix: `DwarfSourceLocationProvider` now builds one
   merged, sorted row table from BOTH resolver passes (CU-scoped +
   line-table-only) and binary-searches it — the exact shape
   `NativeDwarfSourceLocationProvider` already used for native binaries.
2. **Offset-convention mismatch.** Wasm DWARF addresses are relative to the
   code section's content start; every caller passes module-absolute offsets.
   The provider now takes an `addressBase` (the code section content offset)
   and converts. Wrong-convention queries fail closed (null), not silently
   wrong.
3. **Line-base disagreement between backends.** DWARF lines are 1-based;
   the source-map parser returned 0-based VLQ values through the same
   `HexanaSourceLocation` type. Normalized: `HexanaSourceLocation` is
   documented 1-based, the source-map backend shifts on construction, and
   navigation subtracts 1 (the hex view previously navigated DWARF results
   one line off).

Also fixed: the editor's DWARF-else-sourcemap fallback happened per
missing-module, not per failed query, so a Kotlin/Wasm module (parses fine,
no DWARF) could never reach the loaded source map. The override now chains
per query. The provider is cached per provider instance (it was constructed
fresh on every access, which would have rebuilt the row table per query).

## Phase A additions

- `HexanaSourceLocationProvider.getFirstSourceLocationInRange(start, end)`:
  function-LEVEL lookups need the first mapped row at-or-after the body
  start (the size field and locals precede the first line row; a point query
  at body start resolves into the previous function). Implemented by both
  backends; default null.
- `WasmSourceLocationNavigation.kt` (idea-plugin): `sourceLocationLabel`
  (last two path segments + line), `wasmFunctionSourceLocation` (body-range
  query per defined-function index), `openWasmSourceLocation` (VFS lookup,
  1-based -> 0-based shift, `OpenFileDescriptor.navigate`, not-found
  notification) — shared by hex view, table tabs, and WAT rows.
- `TableTabEnricher`: `sourceLocationMenuItem(provider, project, codeIdxFor)`
  added to the Functions tab (row index = codeIdx) and Exports tab
  (exported funcidx - importFuncCount; hidden for imports). Per-row memo
  keeps the visibility/label/action triple to one resolution.
- WAT rows: `ContextMenuArea` wrapper in `WatRowItem` around the rendered
  row, items built lazily at popup time (source location + dispatch
  candidates for `call_indirect` rows). No per-row work while scrolling.

## Costs at 14k functions / 50 MB

- The merged DWARF row table is built once per provider, lazily, off the
  menu path's first use; queries are `findLast` scans over the sorted table
  (linear — matches the native provider; upgrade both to binary search if a
  profile ever says so).
- Table-tab and WAT menus resolve at right-click time only.
- Source-map parse cost unchanged (already load-once per editor).

## Test plan (implemented)

- `WasmSourceLocationTest` (idea-plugin) against the existing
  `debug-add.wasm` fixture (Rust + DWARF, ground truth documented in
  `CdpSourceLocationResolverTest`): module-absolute point query resolves
  `debug_add.rs:10`; the wrong (code-relative) convention returns null; the
  body-range query on `add` (codeIdx 1) returns the signature line 9;
  `sourceLocationLabel` shortening.
- `SourceMapParserTest`: hand-written 2-segment VLQ map pins the 0-based ->
  1-based normalization and the before-first-mapping miss.

## Remaining phases

- **Phase B — sourceMappingURL-driven loading**: read the URL from the
  custom section (path relative to the wasm file; `data:` URLs with inline
  base64 JSON; no http(s) fetching), falling back to the existing `<file>.map`
  sidecar convention. The parser itself already exists.
- **Phase C (deferred)**: VS Code parity; inline `sourcesContent` virtual
  files; opt-in remote map fetching; binary-searched lookups if profiling
  warrants.

## Limits to state honestly

- DWARF paths are build-machine paths; navigation resolves what the VFS can
  find and notifies otherwise. Source-map `sources` with pseudo-URL schemes
  resolve only when the relative path exists next to the map.
- Wasm source maps have no trustworthy column info; navigation targets the
  line.
- DWARF64 v5 line headers remain unimplemented upstream (pre-existing TODO).
