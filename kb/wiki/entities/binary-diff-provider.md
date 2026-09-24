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

## Native per-function diff

`NativeDiffProvider` adds a **Functions** tab (after Size Impact) and a code detail pane when both
sides are ELF, Mach-O or COFF — the formats whose parsers yield `NativeFunctionRecord`s with a body
range (`fileOffset` + `size`). PE images have no function table yet (`.pdata` is not parsed), and
archives / fat Mach-O have no single body list, so they keep the size tab only. Code:
`plugins-shared/.../shared/diff/NativeFunctionDiff.kt`, pinned by `NativeFunctionDiffTest` (jvmTest,
over `parsing/native-fixtures`, with real Capstone).

- **Pairing:** same name first (demangled if available; same-named statics pair in address order),
  then a body-bytes hash unique on both sides catches a rename (MOVED "renamed from x"), the rest
  are added / removed. Identical bytes means identical.
- **Addresses:** relinking shifts every call target, so byte-different same-size pairs are decoded
  and compared with hex literals ≥ 0x100 masked (`normalizedKey`); a match is demoted to MOVED
  "addresses only". Bounded to 16 MiB of candidate code. The detail pane diffs the same masked
  keys but shows the real addresses. Known noise: a `.o` against its linked image still differs
  where a relocation placeholder (`[0x0]`) was filled in.
- **The decoder is a host capability:** `BinaryDiffHost.nativeDisassembler` (a suspend loader,
  called once per session and serialized with a mutex — one Capstone instance is not safe to share
  between the tab and the detail pane). IntelliJ passes `CapstoneWasmDisassembler.loadFromClasspath()`.
  VS Code passes nothing yet, so there the tab compares raw bytes and the detail pane shows a
  16-bytes-per-row hex diff; shipping `disassembler.wasm` to the webview would light it up with
  no shared change.
- `DiffTab.compute` and `DiffDetailSource.render` are `suspend` for that loader; the work itself
  stays blocking and hosts still pick the thread.
- The arch mapping (`nativeDisassemblyArch`, plugins-shared) moved out of IntelliJ's
  `NativeDisassemblyPanel`; per-function Thumb comes from `NativeFunctionRecord.isThumb`.

### Performance (webview)

- The first cause of a slow Functions tab was not the diff: `NativeFunctionDiscovery` inferred
  Mach-O/COFF sizes ("gap to the next symbol in the section") by scanning every symbol per
  symbol, quadratically — 11.5 s on the JVM for clangd's ~47k functions. It is now a sorted
  address list plus a binary search per section (`SortedAddresses`), also used for ELF's
  zero-size symbols: 110 ms. This also speeds up every other consumer of `nativeFunctions`.
- Body hashes are two 32-bit hashes packed into a Long, read in bulk. Kotlin/JS emulates `Long`
  arithmetic: a per-byte 64-bit FNV measured ~320 ms per 4 MiB in Chrome versus ~30 ms.
- The webview has one thread and no worker, and the diff model (lambdas in
  `TableTabDescriptor`) cannot cross `postMessage`. So "async" means cooperative: `BinaryDiffView`
  waits for the loading message to paint, runs `compute`/`render` on `Dispatchers.Default` (a
  macrotask dispatcher in the browser), and the providers `yield()` between parse, hash
  (every 1024 records), pairing and refine (every 64 decodes). Once the visible tab is done,
  the other tabs prefetch in the background. The provider parse itself is still one
  non-yielding step.

### Review fixes (2026-09-24)

- Function bodies are deduplicated by **file offset**, not address: in relocatable objects with one
  section per function (`-ffunction-sections`, C++ COMDAT, MSVC `/Gy`) every function has address 0,
  and keying on it merged them all into one.
- `DiffOpenRequest` carries a factory (`input: () -> BinaryDiffInput`, `bytes: () -> CommonByteBuffer`):
  materializing a submodule copies its bytes and the row click runs on the UI thread, so hosts
  invoke it off that thread (IntelliJ on `wasmDiffScope`, the webview after a paint on
  `Dispatchers.Default`).
- The webview back stack holds a `DiffScreenState` per entry (session, computed tabs, selected tab
  and row, rendered detail), so "Back" restores the parent instead of recomputing it; the detail
  pane is a `VirtualizedList`.
- **Compare WASM With…** accepts only the Wasm providers: the registry's native fallback would
  otherwise take a `.wasm` pair whose header is not Wasm.
