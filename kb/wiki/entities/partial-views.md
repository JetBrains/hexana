# Large files — 64-bit addressing, partial parsing, paged hex views

How Hexana handles files over 2 GiB (and, in VS Code, over `hexana.maxLoadedFileSizeMB`). History:
before 2026-11-06 such files failed to open (`FileChannel.map` / `readFile` refuse > 2 GiB); on
2026-11-06 a read-only head window shipped; later the same day this design replaced it (branch
`user/large-files`): every host shows the **whole file** in the hex view, and the formats that
matter at that size **parse the whole file** on the JVM. Large files stay **read-only** (user
decision: editing them is a separate follow-up).

## The split: `LargeBytes` vs `CommonByteBuffer`

`CommonByteBuffer` (encdec) stays `Int`-addressed — widening it would break ~250 files for formats
that cannot exceed 2 GiB anyway (Wasm, DEX, class, protobuf, PE). Instead:

- `encdec/.../LargeBytes.kt` — `size: Long`, `get(Long)`, `read(Long, …)`, `window(offset: Long,
  length: Int): CommonByteBuffer` (zero-copy where possible), `asCommonByteBufferOrNull()`,
  `containsRange`, `readBytes`, `u16/u32/u64LeOrNull`; `CommonByteBuffer.asLargeBytes()`.
  `LargeBytesSearch.kt` — chunked `indexOf` (forward/reverse) and `findAll(limit)`.
- JVM `MappedFileBytes.open(path)` — lazily maps 1 GiB chunks that run 256 MiB into the next, so
  windows up to 256 MiB are views; larger windows get their own mapping. Holds no file handle.
- Decoders that are `Int`-bound keep working on a **window** of just their region.

## Ranges and selections are 64-bit

- `NativeByteRange` stores `start`/`length`/`end` as `Long`. **`offset`/`size`/`endExclusive` are
  checked `Int` views that throw `ArithmeticException` past 2 GiB** — so PE, COFF, archives and every
  existing consumer compile unchanged, and a consumer on the large-file path that still uses them
  fails loudly instead of truncating. Code that handles ELF/Mach-O/PDB ranges uses `start`/`length`.
- `RowSelectionData(offset: Long, size: Long)` (+ `Int` constructor). `structureRangeOrNull` has a
  `Long fileSize` overload. `HexViewStateHolder` offsets, `HexPosition.getByteOffset`,
  `displayBaseOffset`, `HexanaBinaryVirtualFile.BASE_OFFSET_KEY` are `Long`; nested tab names keep
  the 8-digit hex form when the offset fits an `Int` (they are user-visible and test-pinned).
- Not widened (deliberate): `BinarySizeNode` (Int) — providers return `sizeAnalysis = null` past
  2 GiB rather than a truncated tree; `BinaryStringRecord` (Int) — string scans skip sections past it.

## Providers take the whole file

`BinaryProvider.fileSize` (default `bytes.size`). Every factory overload
(`createBinaryProvider(bytes, …, fileSize, source)`, `(kind, …)`, `(parser, …)`,
`nativeOrGenericBinaryProvider`) threads `source: LargeBytes?`. Migrated providers take
`source: LargeBytes = sharedBytes.asLargeBytes()`, override `fileSize`, and parse tables from it;
`bytes` stays the (≤ 2 GiB) head for everything unmigrated. `NativeBinaryProvider.wholeFile` gives the
source for ELF / Mach-O / fat Mach-O (else `bytes`).

| Format | What reads the whole file | Windowed decoders |
|---|---|---|
| ELF | `ElfParser` (LargeBytes, Long offsets) | DWARF over `debugSectionSpan` (one window, accessor `base`); ARM attributes via `sectionView`; build-id / `.gnu_debuglink` in `NativeDebugCompanionScanner.sectionView` |
| Mach-O, fat | `MachOParser`; string tables copied only up to 64 MiB | DWARF via `machODebugSectionSpan`; fat slices only via `FatMachOBinaryProvider.sliceHeader/sliceBytes` (never slice `provider.bytes`) |
| ZIP64, tar | `ZipParser`, `TarParser` | entry extraction (`decompressZipEntry`/`extractTarEntry` take `LargeBytes`) |
| Windows PDB | `MsfStream` (block × size as Long) | — (companion PDBs next to a PE stay capped by `MAX_AUTOMATIC_PDB_SIZE`) |
| Parquet, Arrow file | footer read from the end of `source` | preview chunk / first batch through a window with rebased page offsets; **value provenance shifted back by the window base** |
| GGUF, safetensors | header only; tensor ranges validated against `fileSize` | — |
| `ar` (static libraries) | `PeParser.parseArchive(LargeBytes)` (10-digit sizes → `ULong`; a member cut off by the end of the bytes ends the listing with an `archive.truncated` diagnostic instead of failing) | members via `ArchiveBinaryProvider.memberBytes`/`classifyMember` (a member > `MAX_BINARY_WINDOW_BYTES` is classified from its head and not openable) |
| PE, COFF, DEX, class, Wasm, pcap (10k-packet cap), Arrow stream | head only | — |

Virtualized native disassembly decodes each function from `wholeFile.window(...)`.

## Parse coverage — when to warn

`BinaryProvider.parseCoverage(fileLength): ParseCoverage` (`binaryProvider/.../ParseCoverage.kt`) says
whether the tabs miss anything of the file on disk: `WholeFile`, or `Prefix(parsedBytes, missing)` where
`missing` reads after "Tabs leave out" ("Wasm sections", "packets", "archive members", "structures").
Hosts show the "(first X parsed)" size label only for `Prefix`; the "Editing disabled" badge still shows
for every large file (editing large files is not done), its tooltip from `largeFileNotice(fileSize,
coverage)` / `partialViewNotice(loaded, fileSize, coverage)` and the label from `largeFileSizeLabel`
(plugins-shared `PartialView.kt`).

- Default `prefixCoverage`: `WholeFile` iff `fileSize >= fileLength` — so whole-file parsers (ELF,
  Mach-O, ZIP, tar, ar, PDB, Parquet, Arrow file) and model headers (GGUF, safetensors, told the real
  size) are covered with no override. In VS Code they read the whole file through fetched bytes (see
  below) and are covered once the fetch rounds finish.
- `GenericBinaryProvider` itself (unknown format; not its many format subclasses): `WholeFile`.
- PE: `WholeFile` when the last section's raw data, the certificate table (its "RVA" is a file
  offset), debug raw data and the COFF symbol table end within the parsed bytes — an installer's
  overlay past 2 GiB is a blob. COFF objects: sections + relocations + symbol table.
- pcap: `WholeFile` once `MAX_CAPTURE_PACKETS` (10k, `parsing/pcap` `PcapFile.kt`) were found in the
  head — a small capture is capped the same way, so reading further would list nothing more. This is
  why pcap was not migrated to `LargeBytes` (its dissectors all read `provider.bytes`).
- Wasm module/component: always `Prefix("Wasm sections")` when cut (sections run to the end of file).
- Tests: plugins-shared `ParseCoverageTest`, `LargeArchivesTest` (ar member after 2.5 GiB),
  `PartialViewTest` (texts).

## IntelliJ host

- `VirtualFile.toBinaryWindow()` → `BinaryWindow(buffer = head, source = MappedFileBytes)`;
  `HexanaFileEditor.partialFileSize`/`fileSource`; `sourceFor`/`fileSizeFor` give them to the factory
  only for the file itself (never for an inflated `.gz` or an in-memory entry).
- `HexViewer(source=)`, `DataInspectorPanel`, `HexViewStatusBar`, `HexSearchField(source=)` read the
  whole file. Rows read via `LargeBytes.read`; offset column widens past 8 hex digits. Search runs on
  `Dispatchers.Default` with chunked `findAll` (≤ 100k highlights, per-cell binary search
  `isInSearchResult`) and `indexOf` from the cursor. Copy is capped at 16 MiB.
- The Compose v2 `ScrollbarAdapter` is `Double`-based, so ~335M rows scroll fine.
- `toMappedBuffer` / `File.toCommonByteBuffer` throw `FileTooLargeToMapException` (readable message)
  for paths that need the whole file in one buffer (running Wasm, diff); the structural diff shows it.
- Read-only: `hexEditDoc = null` for partial views; `PieceTableDocument.originalHash` is now lazy (it
  hashed the whole original on open).
- No editor notification bar: `FileDescriptionBar` shows an "Editing disabled" badge after the parser
  chooser when `fileSize != null`, with `largeFileNotice(fileSize, coverage)` as its tooltip
  (2026-11-09; replaced the former `PartialViewNotification`). See *Parse coverage* above.

## VS Code host

- Head read with Node file handles (`largeFiles.ts` `readHead`), limit `hexana.maxLoadedFileSizeMB`
  (default 512, ≤ `MAX_LOADABLE_BYTES`, test-checked against the Kotlin constant); tabs parse the head.
- **Paged hex view**: `web-ui` `HexBytes` (`BufferHexBytes` for in-memory files — webapp unchanged —
  and `PagedHexBytes`: head from memory, 64 KiB pages fetched from the host, ≤ 512 cached, a
  `loadStamp` state re-renders rows; unloaded bytes show `··`). Host messages `read-byte-range` →
  `byte-range-content` and `search-bytes` → `search-bytes-result` (`readRange`/`searchFile`, exactly
  one reply each; webview `HostRequests` with deadlines, caught before the composable handler).
- **Scroll model** (`HexScroller`): rows are drawn pinned to the viewport top from an authoritative
  `Long topRow`. Up to `MAX_SCROLL_PX` (8M px) the container scrolls natively; beyond, the spacer is
  capped and the scrollbar position *scales* to a row, while wheel/keys/drag/auto-scroll move whole
  rows (our own `scrollTop` writes are ignored when the scroll event comes back). Chromium caps
  element height at ~33.5M px — the old 1:1 spacer could not scroll past ~27 MB of rows.
  `scrollHexViewerTo` posts to `HexViewerScrollRequests` instead of touching the DOM.
- `HexSelectionState` is `Long`; `select(RowSelectionData)` / `matches(...)` helpers. Paged search
  asks the host for one match at a time (label "at 0x…").
- `messageBytesOrNull` views a posted `Uint8Array` in place; test the type on an expression
  (`payload.unsafeCast<Any>() is Uint8Array`) — a smart cast on the `dynamic` broke the array path.
- Webapp parser-worker wire protocol stays `Int` (50 MB cap); it narrows bounded offsets.
- `EditorToolbar` shows the same "Editing disabled" badge after the format badge
  (`editingDisabledNotice`: `largeFileNotice` with a paged hex view, else `partialViewNotice`, as the
  `title` tooltip); no notice strip above the hex view.

## VS Code: whole-file parsers over fetched bytes (`SparseLargeBytes`)

The webview holds only the head (`hexana.maxLoadedFileSizeMB`) and cannot read the file
synchronously, so the 64-bit parsers get a `SparseLargeBytes` (encdec) as their `source`: the head
plus fetched chunks. A read of bytes not held **returns zeros** and records a page-aligned miss
(widened by the run it follows, ≤ 4 MiB, so sequential walks double instead of costing a round per
page; one record for a byte-by-byte walk). Zeros, not an exception, because provider lazies such as
`ElfBinaryProvider.sections` do not catch and would crash composition; zero counts/sizes come out as
empty tables. Only a `window` > 64 MiB over missing bytes throws `BytesNotLoadedException` (DWARF
spans, `sectionView`, fat slices catch it → `null`).

`web-ui` `StructureBytesFetcher` runs the rounds: `onMiss` schedules a round (setTimeout, never a
state write in composition) → fetch the misses (+ the last 1 MiB in the first round that misses
anything; a head-only provider misses nothing and is never rebuilt) in ≤ 16 MiB requests
(`readByteRangeFromHost`, the extension's `MAX_RANGE_BYTES`) → `bytes = current.withChunks(...)`
(state) → `Main.kt` rebuilds the provider keyed on it. Caps: 256 MiB fetched, 64 rounds; past them,
or on a failed read, ranges are declined (not retried) and `isIncomplete`. `coverageOverride` is a
`Prefix` while fetching or incomplete; `BinaryEditorContent` prefers a provider's own `Prefix`.
`tabStateKey = buffer` keeps the selected tab across rebuilds.

Zero-filled bytes must never reach the user: `LargeBytes.isFullyLoaded(start, end)` guards
`ArchiveBinaryProvider.memberBytes` (VS Code then opens the member via `host.openTarEntry`, a raw range
read by the extension) and `FatMachOBinaryProvider.sliceBytes`; `classifyMember` classifies a member
it cannot view from a 64 KiB prefix. ZIP/tar entries were already extracted by the extension from disk.
Invariant change: in VS Code `source.size` can be ≤ 2 GiB while `bytes` is only the head, so the size
trees check `source.size > bytes.size` (not `> Int.MAX_VALUE`).

Tests: encdec `SparseLargeBytesTest` (all targets); plugins-shared `LargeArchivesTest` runs ZIP64, tar,
`ar` and an ELF through the rounds from a 4 KiB / 256-byte head and compares with the full parse;
web-ui `StructureBytesFetcherTest`.

## Tests (all sparse files — nothing big is written)

encdec `MappedFileBytesTest` (3 GiB, chunk boundaries, large windows), `LargeBytesSearchTest`;
idea-plugin `PartialViewEditorTest` (editor on 3 GiB: marker past 2 GiB, search); plugins-shared
`LargeElfTest`, `LargeMachOTest`, `LargeArchivesTest` (ZIP64 + tar), `ParquetWholeFileTest`
(head-only buffer + whole source, provenance equality), `PartialViewTest`; pdb `LargePdbTest`;
web-ui `HexBytesTest`, `HexScrollerTest` (real DOM, 5 GiB of rows); vscode `largeFiles.test.ts`.
Gotcha: `encdec` Wasm test linking once failed with a "missing key" internal compiler error — a
stale incremental cache, fixed by `--rerun-tasks`. The webapp `parserWorker` source set is only
compiled by `:webapp:compileParserWorkerKotlinJs`, not `compileKotlinJs`.

## Not done

Editing large files; MCP/CLI (own caps); scripting `binary.size` is the parsed size; webapp (50 MB);
VS Code opening a fat Mach-O slice past the head (no raw-range fallback yet);
DWARF larger than one 2 GiB window; native-image detection past the head.
