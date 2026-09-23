# Binary diff provider

The one model behind every diff editor: core Wasm modules, Component Model binaries, and native
binaries, in both IntelliJ and VS Code. Lives in `plugins-shared/.../shared/diff/`; the two hosts
only draw it. It's the diff counterpart of `TableTabsProvider`.

## Why it exists

Diff started as one IntelliJ view per format (`WasmDiffEditorView`, `WasmComponentDiffEditorView`,
`NativeDiffEditorView`, plus four panels), and the VS Code port was a copy (`WasmDiffView`,
`NativeDiffView`) that had already drifted: it had no Dispatch tab, no symbol maps and no component
diff. `HexanaFileEditor` knew every format through three virtual-file types and five flows.
Per-function native diff was next, so it needed one abstraction first.

## Shape

```
BinaryDiffInput(left, right, leftName, rightName, symbol maps)
        │  BinaryDiffProviderRegistry.forInput — first accepting provider wins:
        │    WasmComponentDiffProvider (both components) → WasmModuleDiffProvider (both core)
        │    → NativeDiffProvider (neither is Wasm).  A mixed pair has none → "cannot compare".
        ▼
BinaryDiffSession(tabs: List<DiffTab>, banner: () -> lines, detail: DiffDetailSource?)
   DiffTab(name, loadingMessage, compute: (DiffTabContext) -> DiffTabContent)
   DiffTabContent(header lines, table: TableTabDescriptor?, emptyMessage, footer)
   DiffTabContext(select: (DiffItemRef?) -> Unit, open: ((DiffOpenRequest) -> Unit)?)
   DiffDetailSource.render(DiffItemRef) -> SideBySideDiff(title, rows, emptyMessage)
        │
        ├── idea-plugin  ui/panel/BinaryDiffEditorView.kt  (BinaryDiffModel holds per-tab flows)
        └── web-ui       vscode/BinaryDiffView.kt
```

## Decisions worth remembering

- **Tables are ordinary `TableTabDescriptor`s**, rendered through each host's existing
  `TableTabBridge`, so diff tables get search and sort for free. Rows act through `rowClickAction`
  (never the hex view), which is why the bridges' hex state is a throwaway there. The
  classification colour is `TableTabDescriptor.rowTintProvider` → `RowTint`, a role rather than a
  colour, the same rule as `HexRangeRole`.
- **Everything shared is synchronous; hosts own the threading.** IntelliJ computes all tabs
  concurrently on `Dispatchers.IO` into editor-scoped flows (they survive editor-tab switches).
  The single-threaded webview computes each tab the first time it's viewed and caches the result.
- **`DiffItemRef` is the detail-pane contract.** Wasm fills it from size/entity/dispatch keys
  (`fn:pair:`, `fn:rmL:`, `fn:addR:`, `fn:idx:`, `fn:name:`, `fn:L->R`, `fn:rm:`, `fn:add:`, pinned in
  `BinaryDiffProviderTest`). A null index means "resolve by name". Native per-function diff plugs
  in as a `DiffDetailSource` on `NativeDiffProvider`, and no host changes are needed.
- **Navigation is a request, not a host call.** A component row returns `DiffOpenRequest.Diff`
  (sliced + materialized submodule pair, routed back through the registry) or `.Single` (a
  one-sided submodule). IntelliJ opens a new editor tab. VS Code pushes the diff onto an
  in-webview back stack, and sends a single binary to `Host.openModule`.
- **One IntelliJ virtual file, `HexanaDiffVirtualFile(name, BinaryDiffInput)`**, typed
  `BinaryFileType`. The provider is picked from the bytes, so "Compare Binary" on two `.wasm`
  objects now gets the Wasm diff instead of an empty native one.
