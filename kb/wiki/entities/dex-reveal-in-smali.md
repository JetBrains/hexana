# DEX "Reveal in Smali"

The context-menu action on the DEX **Classes** table that jumps to that class in the **Smali**
tab, in IntelliJ and in VS Code/webapp.

## Shared contract (`plugins-shared`)

- `DexTableTabs.kt`: `DEX_CLASSES_TAB_NAME`, `DEX_REVEAL_IN_SMALI_LABEL`, and
  `TableTabDescriptor.withDexRevealInSmali(onReveal)`. It adds the item only to a non-empty
  Classes tab and leaves every other tab as it is. `onReveal` receives the row index, which is
  the index into `DexFile.classDefs`. The smali views key classes by the same index
  (`dexSmaliClassBodyId`), so nothing else needs translating.
- `TabDescriptor.kt`: `TableTabDescriptor.withExtraContextMenuItems(items)` copies a descriptor
  with extra `contextMenuItems`, so a host can add actions to a shared tab without restating it.
  **When you add a constructor property to `TableTabDescriptor`, also carry it over here.**
- Tested in `DexRevealInSmaliTest`.

## Hosts

Each host supplies its own `onReveal` that switches to the Smali tab. The tab is selected first,
and the request is held until the Smali view composes.

- **IntelliJ.** `TableTabEnricher.enrichDexTabs` (wired via `getTabEnricher` for
  `DexBinaryProvider`) → `HexViewStateHolder.requestRevealDexClassInSmali`. That sets
  `dexSmaliClassNav` (a `MutableStateFlow<Int?>`) and selects the tab by name. It is a state flow
  rather than a shared flow because the Smali panel only composes once its tab is selected.
  `DexSmaliPanel` collects it, calls `ChunkPanelNavigator.gotoChunk(dexSmaliClassBodyId(i))` (which
  unfolds the class header on the way), and resets the flow to `null` so the same class can be
  revealed again. Tested in `DexTabEnricherTest`.
- **VS Code / webapp (`web-ui`).** `HexanaPanel` applies `withDexRevealInSmali` for
  `DexBinaryProvider` and writes the index into a `MutableState<Int?>`. It hands that state to the
  tab content through `LocalDexSmaliReveal`, a composition local, because the caller builds the
  Smali tab (`dexSmaliExtraTabs`) outside `HexanaPanel`. `DexSmaliTab` consumes and resets the
  request, clears a search that hides the class, expands the class, and on the next composition
  scrolls its header into view via `VirtualizedList(reveal = ListReveal(...))`. A
  per-request id makes revealing the same class twice scroll again. Tested in
  `DexEditorContentTest` (`DexSmaliItemIndex.headerIndexOf`).

## Tree-table context menus

The Classes tab is a tree table (`childrenProvider`). Before this feature, neither host's tree
table showed `contextMenuItems`. Both now show them on **top-level rows only**
(`row.depth == 0`), because descriptor menu items are indexed by top-level row and child rows
have no index of their own.
