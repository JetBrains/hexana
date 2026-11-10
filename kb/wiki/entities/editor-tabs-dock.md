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
- **Vertical tabs:** when docked right the horizontal `ScrollableTabStrip` is replaced by `VerticalTabList` on the panel's right (outer) edge, right of the content (rows as
  wide as the longest title, capped at 220 dp, ellipsized, scrollable, selection bar on the outer edge, scrollbar on the inner one, close button for
  script tabs). A tall, narrow panel shows only two or three tabs of a horizontal strip.
- Not covered: the diff editor (`BinaryDiffEditorView`) and VS Code keep their bottom layout.
- Manual QA: `idea-plugin/QA/04-analysis-tabs.md` 4.17.
