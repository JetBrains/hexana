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

## IntelliJ

- Hardcoded lazy panel `ui/panel/ApkClassesPanel.kt` (`ZipBinaryProvider` branch of
  `resolveHardcodedPanels`/`discoverHardcodedPanelNames`, keyed on file name). Loads in
  `LaunchedEffect` + `Dispatchers.IO`, shows `LoadingTabContent(message)` with per-DEX progress.
- Reveal: `HexanaFileEditor.revealDexClassInZipEntry` → `openZipEntry` (which reuses an open
  `HexanaBinaryVirtualFile` with the same parent + `ENTRY_PATH_KEY`) → the opened editor's
  `HexViewStateHolder.requestSelectTableRow(tab, row)`.
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
- Webview: `select-table-row` → `Main.kt` `TableRowSelectionRequest(tab,row,requestId)` →
  `BinaryEditorContent`/`HexanaPanel(tableRowSelection)` → switches tab and provides
  `LocalTableRowSelection`; web flat and tree tables consume it (tree reuses the treemap's
  `selectFlatRow`; its scroll effect is keyed on the wrapper element since a request can land first).
- Tests: `webapp/.../ApkClassesDomTest` (Chrome DOM: lazy tab, deflated DEX, Reveal call, selection),
  `vscode-plugin/webview/.../SelectTableRowDecodeTest`, `plugins-shared/.../ApkClassesTabTest`.
