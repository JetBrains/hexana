# Editor tabs panel: bottom or right dock (IntelliJ)

The analysis tabs of the IntelliJ single-file editor (`ui/BinaryEditorView.kt`) sit either below the hex view
(default) or to its right. Added 2026-11-10.

- **Global, persisted, live:** `HexanaSettingsState.tabsPanelOnRight` (`hexana.xml`), exposed as
  `HexanaSettings.tabsPanelOnRight`, backed by a lazily created Compose `mutableStateOf`, so toggling in one editor
  moves the panel in every open editor. Lazy because the state component is loaded after construction.
- **Toggle:** an icon button in the tabs header, left of **+** (`AllIconsKeys.Actions.MoveToRightTop` /
  `MoveToBottomLeft`). A file with a single panel has no header, hence no toggle; it still follows the setting.
- **Split:** `VerticalSplitPanel(state, …, sideBySide = …)` switches to `HorizontalSplitPanel`. Both orientations
  share `HexViewStateHolder.topFraction` as the hex pane's share, so the proportion survives toggling and the existing
  `topFraction > 0.9f → 0.5f` restores (size badge, Top tab links) and expand/collapse need no changes.
- **Expand when docked right:** the expand button sets `topFraction = 0`. The vertical split clamps that to its 20%
  minimum; the horizontal one has no fractional minimum and shows the hex pane as a clipped 12 dp strip
  (`COLLAPSED_PANE_WIDTH`) to grab the divider. A drag from the strip first snaps the fraction to the strip's real
  share so the divider follows the pointer. Dragging may also narrow the hex pane below 20% in this layout.
- **Vertical tabs:** when docked right the horizontal `ScrollableTabStrip` is replaced by `VerticalTabList` on the panel's right (outer) edge, right of the content (rows as
  wide as the longest title, capped at 220 dp, ellipsized, scrollable, selection bar on the outer edge, scrollbar on the inner one, close button for
  script tabs). A tall, narrow panel shows only two or three tabs of a horizontal strip.
- **Tables in a narrow pane:** `ui/components/Table.kt` used to split whatever width it got among columns, so in a
  docked panel fixed columns overflowed and weighted ones shrank until titles wrapped letter by letter. It now has a
  minimum content width (`minTableContentWidth`: fixed widths + paddings, and weighted columns wide enough for their
  title / 12 chars after the weight split); below it, header and rows scroll sideways together (`horizontalScroll`
  on one column holding both), with the scrollbars outside the scrolled content. Header titles are single-line with
  ellipsis. Applies to every table (flat and tree), not just docked ones.
- **Virtualized listings** (`ui/panel/VirtualizedChunkPanel.kt`: IL, Smali, class files, WAT, native disassembly)
  had the same problem: header and body rows wrapped to the pane width (type headers letter by letter). Rows now keep
  their natural width: the `LazyColumn` sits in a `horizontalScroll` and is `max(viewport, widest row)` wide. Every
  item, including those the panels' `elementContent` emits, goes through `WidthTrackingLazyListScope`, which wraps it
  in `naturalWidthRow` — reads the row's max intrinsic width into `ChunkRowWidthTracker.widest` (grows only, reset
  with the chunk tree) and lays it out at the list's width so backgrounds span it. Rows that cannot answer intrinsics
  (subcompose/lazy content) throw `IllegalStateException`, which is caught: they just take the list width. Cost: one
  intrinsic measurement per composed row.
- Not covered: the diff editor (`BinaryDiffEditorView`) and VS Code keep their bottom layout.
- Manual QA: `idea-plugin/QA/04-analysis-tabs.md` 4.17.
