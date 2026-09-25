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
- A `.wasm` whose header is not Wasm is rejected: the registry's native fallback would otherwise
  take it (see below).

### One compare action (2026-09-24)

"Compare WASM" and "Compare Binary" are merged into one action per host — IntelliJ
`hexana.compareFiles` "Compare Binaries With…" (not "Compare Files": the platform's own text diff
has that name), VS Code `hexana.compareFiles` "Hexana: Compare Files". The provider is picked from
the bytes, and `BinaryDiffProviderRegistry.rejectionReason` phrases why a pair is refused (different
kinds, or a `.wasm` without a Wasm header), for both hosts. The old ids stay as hidden aliases so
bound shortcuts keep working: IntelliJ `LegacyCompareFilesAction` (in no menu, hidden from Find
Action), VS Code commands registered but not contributed in `package.json`.

In IntelliJ the entry is offered by **file type**, not extension (`CompareFilesAction.isComparable`):
`WasmFileType` or any `NativeBinaryFileType` (ELF, Mach-O, PE incl. COFF objects, `ar`), which
`NativeBinaryFileTypeDetector` assigns by magic — so extensionless binaries qualify and a headerless
`.bin` does not. VS Code still offers it by extension list (plus extensionless files), since its
`when` clauses cannot see content.

### IntelliJ diff window integration (2026-09-25)

The platform diff window (Compare Files, VCS changes) is extended through the `diff.DiffTool` EP
(`idea-plugin/.../diff/HexanaDiffTools.kt`), not through `DiffToolSubstitutor`. Facts from the
2026.2 platform sources:

- `DiffManagerImpl.getDiffTools()` lists EP tools **before** the built-ins (`SimpleDiffTool`,
  `UnifiedDiffTool`, `BinaryDiffTool`, `DirDiffTool`), so an EP tool whose `canShow` accepts wins by
  default; the user's choice is then persisted per tool class in `DiffSettings.diffToolsOrder`.
- `DiffRequestProcessor` shows a segmented **viewer switcher** whenever more than one `FrameDiffTool`
  accepts the request — no custom "switch mode" button is needed. Hexana registers two tools:
  `HexDiffTool` ("Hex viewer") and `HexanaStructuralDiffTool` ("Hexana diff viewer", hosts a
  `HexanaFileEditor` on a `HexanaDiffVirtualFile`). `HexDiffTool` implements the public
  `SuppressiveDiffTool` to hide the platform `BinaryDiffTool` (two plain editors side by side) wherever
  it accepts the request; suppression is applied to both the default pick and the switcher.
- The platform prev/next-difference arrows are driven by `DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE`,
  which is `@ApiStatus.Internal` — the hex viewer ships its own Previous/Next Difference toolbar
  actions instead.

The hex diff is **positional** (byte `i` vs byte `i`), rows of 16; runs of changed rows are blocks,
and unchanged stretches of ≥ 4 rows outside 3 rows of context fold into one clickable item
(`HexDiffLayout`). An insertion therefore marks every following row as changed; alignment (LCS) was
rejected as too costly for multi-MB binaries.

Sections in the hex diff (`diff/HexDiffSections.kt`): each side gets its own `StructureRange`s from
`createBinaryProvider(bytes, fileName = …)`, parsed after the byte diff is shown. `diffSections`
attributes changed bytes per section by scanning only changed blocks (bytes past the shorter side are
counted arithmetically). Tints use `HexanaTheme.diffStructureColors` — blues, purple, teal, slate —
deliberately disjoint from the diff's amber / red / green; the single-file `structureColors` palette
contains green, gold and orange and would read as changes. The section bar is a single wrapping `FlowRow` for both sides
(`mergeSections` pairs the n-th same-named section of each side, in left order, right-only ones after
their right-side predecessor; a pair shares one tint). Height is capped and scrolls; past 400 entries
(ZIP / PCAP emit tens of thousands) only changed sections are listed. A click scrolls the shared
positional view to the earliest change on either side, so a section at different offsets on the two
sides lines up on one side only.

Table tabs in the hex diff (`plugins-shared/.../shared/diff/TableTabDiff.kt`, `TableTabDiffer`): the flat
tabs (`childrenProvider == null`) every `TableTabsProvider` produces for each side are paired by name
within their provider (a trailing ` (N)` count is ignored) and merged into one `TableTabDescriptor`
using `rowTintProvider`, a default "Changed rows" `PredefinedFilter`, and `old → new` cell text. Rows
need no key column: identical rows pair first (by full content, n-th with n-th); the rest pair through
a column whose values are unique on both sides, chosen by **how many cells its pairs agree on** — not by
pair count, since an address column matches as often as a name column but pairs unrelated rows; rounds
repeat while anything pairs. Right-only rows follow their right-side predecessor. Tree tabs are not
diffed yet.
