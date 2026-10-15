# APK Classes tab and "Reveal in DEX"

The `.apk` **Classes** tab (every class across all DEX entries) and its **Reveal in DEX** action,
in IntelliJ and VS Code; plus the generic "select row N of table tab T" request it introduced.

## Shared

- `plugins-shared/.../shared/ApkClassesTab.kt`: `apkDexEntries` (multidex load order),
  `suspend collectApkDexClasses(provider, onProgress, readEntry)` and `apkClassesDescriptor`.
  Inflation is host-supplied and `suspend` (JVM `Inflater` vs browser `DecompressionStream`);
  the collector `yield()`s between entries so a browser repaints. Gated by `isApkFileName`
  (same as the APK flavour of the Entries tab). Rows carry the `ZipEntry` and the
  `classDefIndex`, which is the row index of that DEX's own Classes tab.
- `APK_MAX_DEX_ENTRY_SIZE` (256 MiB): an entry declaring a larger uncompressed size is reported
  unreadable without calling `readEntry`, so a crafted APK cannot make a host allocate from an
  untrusted size. Entries are parsed one at a time and dropped, so a per-entry cap bounds the peak.

## Both hosts

- Tab content composes only while its tab is active, so the loaded `ApkDexClasses` must live
  outside it or every return to the tab reinflates every DEX: IntelliJ keeps it in a
  `MutableState` created in `apkClassesPanels` (the `Panel` outlives tab switches), web in
  `HexanaPanel` (`remember(provider)`). The tab's `LaunchedEffect` skips loading when it is set.
- A row-selection request whose row the table's search or predefined filter hides clears them,
  keeps the request, and selects once the recomputed rows show it (web flat + tree; IntelliJ tree
  for search, both IntelliJ tables for filters). IntelliJ's `Table` owns its search field, so the
  bridge clears it with `Table(searchResetRequest = n)`. Pitfall: a DOM test that builds its
  provider inside the composable gets a new provider per recomposition, which resets table state
  and hides this bug.

## IntelliJ

- Hardcoded lazy panel `ui/panel/ApkClassesPanel.kt` (`ZipBinaryProvider` branch of
  `resolveHardcodedPanels`/`discoverHardcodedPanelNames`, keyed on file name). Loads in
  `LaunchedEffect` + `Dispatchers.IO`, shows `LoadingTabContent(message)` with per-DEX progress.
- Reveal: `HexanaFileEditor.revealDexClassInZipEntry` → `openZipEntry` (which reuses an open
  `HexanaBinaryVirtualFile` with the same parent + `ENTRY_PATH_KEY`) → the opened editor's
  `HexViewStateHolder.requestSelectTableRow(tab, row)`. `openZipEntry` inflates in `editorScope`
  off the EDT and calls back on the EDT (`onOpened`), rechecking for an already-open entry.
- `pendingTableRowSelection` is consumed by flat and tree tables in `TableTabBridge`; `Table`
  treats `externalSelectedIndex` as a full selection (highlight, focus, `onRowSelect`, scroll).
  Pitfall found: the tree table's non-treemap branch did not pass `externalSelectedIndex` at all.

## VS Code / web

- `web-ui/.../ApkClassesTab.kt`: tab added in `HexanaPanel` (composes only when active), inflates
  with `DecompressionStream("deflate-raw")`, shows `LoadingIndicator(message)` (from `HexDiffView`).
- Reveal goes through `Host.revealZipEntryRow` (gated by `Host.canRevealZipEntryRow`, false in the
  webapp, which cannot open entries). `VscodeHost` posts `open-zip-entry` with
  `selectTabName`/`selectRowIndex`.
- `WasmEditorProvider.openZipEntry`: finds an open panel for the stable `zipEntryModuleUri` and
  `reveal()`s it; posts `select-table-row` if that webview is loaded, else queues it in
  `pendingTableRowSelections`, flushed by `markLoaded` right after `load-wasm` (messages are ordered).
- `pendingTableRowSelections` entries are dropped on panel dispose when no unloaded panel for that
  URI is left to deliver them (`dropOrphanedTableRowSelection`), so a later open is not stale.
- `inflateRaw` cancels its stream reader on coroutine cancellation (and when output overruns),
  with rejection handlers on the reader cancel and pending write.
- Webview: `select-table-row` → `Main.kt` `TableRowSelectionRequest(tab,row,requestId)` →
  `BinaryEditorContent`/`HexanaPanel(tableRowSelection)` → switches tab and provides
  `LocalTableRowSelection`; web flat and tree tables consume it (tree reuses the treemap's
  `selectFlatRow`; its scroll effect is keyed on the wrapper element since a request can land first).
- Tests: `webapp/.../ApkClassesDomTest` (Chrome DOM: lazy tab, deflated DEX, Reveal call, selection),
  `vscode-plugin/webview/.../SelectTableRowDecodeTest`, `plugins-shared/.../ApkClassesTabTest`.
