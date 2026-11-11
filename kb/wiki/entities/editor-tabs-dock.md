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
- Not covered: the diff editors (IntelliJ `BinaryDiffEditorView`, web `BinaryDiffView`/`HexDiffView`) keep their
  bottom layout.
- Manual QA: `idea-plugin/QA/04-analysis-tabs.md` 4.17.
- **Review fixes (2026-11-11):** `HexanaSettings.loadState` re-syncs the Compose mirror (Settings Sync / external
  reload). `VerticalSplitPanel` wraps both panes in `movableContentOf` (latest lambdas via `rememberUpdatedState`),
  so docking elsewhere or the tabs appearing keeps pane state instead of recreating it; the expand icon is derived
  from `topFraction <= 0` instead of a `remember`ed flag that reset on re-docking. The vertical tab list brings the
  selected row into view (`BringIntoViewRequester`). Table and listing reserve a 10 dp bottom gutter while their
  horizontal scrollbar shows; the listing's width tracker resets on fold changes. Not changed: the claim that a
  growing `widest` rebuilds the O(n) lazy-list DSL — with strong skipping the content lambda's captures are stable,
  so `LazyColumn` keeps the same content instance and only re-measures.

## VS Code (2026-11-11)

- **Setting:** `hexana.tabsPanelPosition` (`bottom` | `right`, Global). `WasmEditorProvider.ts` posts
  `update-setting` `tabsPanelOnRight` on every webview `ready` and on configuration change (that listener is now
  disposed with its panel); the webview's dock button posts `set-tabs-panel-position`, which writes the setting.
- **Plumbing:** `web-ui` `LocalTabsPanelDock` (`TabsPanelDock(onRight, setOnRight)`), provided only by the VS Code
  webview (`Main.kt`) — so the many webapp `BinaryEditorContent` call sites need no change, and the webapp keeps the
  bottom layout with no dock button.
- **Layout:** one element tree for both docks — `BinaryEditorContent`'s `.hexana-editor-split` switches
  `flex-direction` (column / row) around the hex area and the panel, and `HexanaPanel` keeps its tab content in the
  same row wrapper either way (the vertical list is a sibling added after it). **Not `movableContentOf`:** moving
  DOM nodes resets the moved elements' `scrollTop` to 0 while the virtualized tables' Compose state kept the old
  offset, so a scrolled table rendered rows 2973–3022 off-screen and showed nothing until its tab was recreated
  (reported; reproduced by `DockedTabsRedockDomTest`). Virtualized tables (flat and tree) and `VirtualizedList`
  also re-read viewport height and offset on resize (`observeResize`, a `ResizeObserver`), since re-docking,
  expanding and divider drags resize them without a scroll event. `HexanaPanel(dockedRight, widthPx, expanded, …)`: left
  `ColumnResizeDivider`, `DockedPanelHeader` (expand, dock, "+"), content + `VerticalTabList` on the right edge
  (≤ 220 px, ellipsized, active row `scrollIntoView({block: 'nearest'})`). Expanded = `calc(100% - 12px)`; a drag from
  there starts at the panel's measured width. Icons are SVG masks in `editor.css` (`hexana-icon-*`), like the debug
  icon.
- **Narrow tables:** `.hexana-panel-table` is `table-layout: fixed; width: 100%`, so fill columns got whatever the
  measured columns left — 24.6 px for the CLR Methods "Type" column at the default docked width. Tables now get a
  `min-width` (`panelTableMinWidth`: measured `ch` widths, other columns ≥ title or 12 ch, plus 16 px padding each);
  their scrolling parent then scrolls sideways.
- **Listings:** `VirtualizedList(naturalWidthRows = true)` — rows `min-width: 100%; width: max-content;
  white-space: nowrap` — for IL, Smali and DWARF. The flex type-header rows wrapped to 54 px inside 18 px rows.
  Opt-in because the side-by-side diff sizes columns in percentages of the row.
- **Pinned by** `webapp` `DockedTabsLayoutDomTest` (Chrome, real `editor.css` and `sample-clr.exe` served from
  `layout-fixtures` via `jsTestProcessResources` + `karma.config.d/web/config.js`); the table and IL checks failed
  before the fixes.
- **Large-table drift (2026-11-11, not dock-specific):** the virtualized tables in `TableTabBridge` size their
  spacers at `rowHeight = 24` px per off-screen row, but no CSS made rows 24 px: with `editor.css` they render at
  ~18 px (12 px font + padding). Each move of the rendered window shifted the content, Chrome's scroll anchoring
  "corrected" `scrollTop`, which moved the window again — the table kept scrolling by itself (~96 px/frame) after
  input stopped; small tables are not virtualized, so they were fine. Fixed by fixed-height virtual rows
  (`PanelTableRow(heightPx = rowHeight)`) and `overflow-anchor: none` on both virtual scroll wrappers.
  `LargeTableScrollDomTest` pins it (flat table failed before; the tree table's pin/settle logic already absorbed
  the corrections, so its variant is a guard only).

