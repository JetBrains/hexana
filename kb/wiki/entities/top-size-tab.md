# Top (size analysis) tab

The "Top" tab is built once, host-neutrally, by `SizeTableTabsProvider` in
`plugins-shared/src/commonMain/kotlin/org/jetbrains/hexana/shared/SizeTableTab.kt` from
`BinaryProvider.sizeAnalysis`. The IntelliJ plugin, VS Code webview and webapp render the same
`TableTabDescriptor` (tree table plus `TreemapData`). `idea-plugin/.../ui/panel/PanelSize.kt` is an
older IntelliJ-only panel that nothing calls any more.

## Nested modules and components (2026-11-16)

For a component (`WasmComponentBinaryProvider`), `SizeAnalysisBuilder` emits each embedded core
module / nested component as a `SizeNode.EmbeddedModule` / `EmbeddedComponent` that carries only
its byte slice. The builder does not recurse, because building every nested tree up front froze the
panel on the 15 MB `composed.wasm`. The tab expands these containers:

- **Nesting under the parent, as for other formats.** `Component 3` → `Module 0` → sections →
  functions. Containers are numbered per type in definition order (`Module 0`, `Component 1`), in the
  same order as `WasmComponentBinaryProvider.nestedComponents` and the Components tab.
  - An earlier variant flattened containers of 10% or more into prefixed top-level rows
    (`Component 3 › Module 0 › Code`) with "N smaller items" groups. It was dropped on user feedback
    as confusing; the tree table already nests.
- **Treemap depth depends on size.** Every Top row gets `TREEMAP_BASE_DEPTH` (2) levels. A node of at
  least `TREEMAP_DEEP_SIZE_FRACTION` (1%) of the file keeps expanding, down to `TREEMAP_MAX_DEPTH`
  (8). Below the base depth only children of at least that 1% are drawn. Smaller siblings fold
  into one `N smaller items` block, which selects the parent row. The fold is needed because the
  host layout scales siblings to fill the parent, so dropping them silently would inflate the rest.
  So going deep never produces mosaics of microscopic squares, for example individual functions.
  Function and data-segment detail is never drawn, because those sizes are opcode counts and
  content runs, not bytes. Both treemap hosts (`idea-plugin/.../panel/SizeTreemap.kt`,
  `web-ui/.../vscode/SizeTreemap.kt`) label leaves below level 1 only when the rectangle is at least
  80×18. Group (non-leaf) rectangles are never labelled. A label that doesn't fit is shortened
  to `f#453` only when its last word is a number. Before that check, `Custom: .debug_line`
  rendered as `C#.debug_line`.
- **Absolute offsets.** Nested trees are built from the container's slice, so their node offsets
  are relative to it. Each row carries a `SizeScope(base, wasm)`. The content start is
  `offset + size - slice.limit()`, because the node spans the whole section and the slice is its
  content.
- **Per-function detail in nested modules.** The scope holds a `WasmBinaryProvider` over the nested
  module's slice, so `func N` and `data N` rows inside a nested module expand.
- Table and treemap share the same lazily resolved `SizeRow.children`, so treemap `treePath`s match
  table row indices.

## Gotcha: shared slice cursors (why the IDE never went deeper)

In the IDE a local file is memory-mapped (`JvmByteBuffer` over a `MappedByteBuffer`). A slice keeps
its own read cursor, and the slices stored in the provider's *cached* size tree are shared by every
build of the tab. The first parse left a slice's cursor at its end, so every later
`WasmParser().parse(slice)` failed and the nested containers came out empty: one opaque block each.
Tests that used `ByteArray.toCommonByteBuffer()` and built the tab once did not catch it. The fix is
to always parse `slice.duplicate().position(0)`. The regression test builds the tab three times over
a mapped `composed.wasm`.

## Components tab listed one of N nested components

`WasmComponentBinaryProvider.nestedComponents` used the parser's singular `componentSection`, which
holds only the **last** component section. So `composed.wasm` showed 1 of its 4 components, and
every row and graph node opened that same section. `nestedComponents` now enumerates
`sections.filterIsInstance<WasmComponentSection>()`, and `NestedComponentData.section` carries each
one's range. Users that read `componentSection` alongside it were switched to the per-entry
section: the Components tab, both table enrichers (IntelliJ, VS Code), both dependency graphs, and
the webapp worker. The MCP `nestedComponents` summary count now reports all of them.

Follow-ups from a code review (2026-11-16):

- `NestedComponentData` keeps a **view** of the nested bytes (`slice`), not a copy. For a
  memory-mapped file, copying every nested component put another file's worth of bytes on the
  heap. `buffer` returns a fresh `duplicate()` per call. Every section scan positions itself, so
  callers no longer share a cursor.
- `NestedComponentData.componentIndex` is the definition's index in the **component index space**.
  That space counts component-sort imports (`ComponentExternComponent`), component-sort aliases, and
  definitions, in section order, including definitions that fail to parse. Dependency-graph node IDs
  and labels use it, because edges (`instantiate component N`, sort-index arguments, exports) refer
  to that space. The list position still names opened files, as in the Components tab.
- A reported risk that eager deep treemap parsing stalls the composition-time descriptor build was
  checked and not acted on. On the 15 MB `composed.wasm` a warm JVM build takes about 5 ms
  (365 treemap nodes). Containers of 1% or more at one level don't overlap, so parsing is at most
  about one file's worth of section headers per level, across at most 8 levels.

Tests: `plugins-shared/src/jvmTest/.../SizeTableTabNestedTest.kt` (synthetic components, plus
`wasmParser/src/commonTest/resources/component/composed.wasm`, both as a byte array and mapped).
