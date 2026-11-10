# Wiki log

Append one line per write-back: date, page, what changed.

- 2026-09-04 — `entities/wasm-runtime-compatibility`, `sources/webassembly-features-json`,
  `index` — created. Design and upstream-schema notes for the Wasm runtime compatibility
  table (features.json ingestion, `.hexana/runtimes.json`, badge warnings, Compatibility tab,
  seven new detected proposals).
- 2026-09-04 — `entities/wasm-runtime-compatibility` — recorded the Compatibility tab's
  supporting-runtimes column in both its forms: leading `Supported in` over the whole
  catalog with nothing declared, trailing `Other runtimes` excluding the declared ones
  otherwise; plus the label format and the sort key.
- 2026-09-04 — `entities/wasm-runtime-compatibility` — added `RowActionKind` (docs vs run
  icon per host) and recorded the web-UI row-action/ID-column decoupling it uncovered.
- 2026-09-04 — `entities/wasm-runtime-compatibility` — added `PrologueActionDescriptor`:
  the Compatibility prologue now links to each host's runtime settings page instead of
  naming the project file, with a path fallback for hosts that have no settings UI.
- 2026-09-04 — `entities/wasm-runtime-compatibility` — recorded live tracking of
  `.hexana/runtimes.json`: the pure `RuntimeTargetsTracker` policy, why a save announces
  itself, and why only the Compatibility tab is re-resolved.
- 2026-09-08 — `entities/wasm-runtime-compatibility` — recorded `badgedProposals`: a
  component whose only proposal is the component model gets no proposals badge, since the
  file-type badge already says it.
- 2026-09-08 — `entities/wasm-runtime-compatibility` — component binaries now get a
  Compatibility tab; recorded the `tableTabs(provider, context)` default-drops-context trap
  that had hidden it.
- 2026-09-08 — `entities/wasm-runtime-compatibility` — dropped `componentModel` detection
  entirely (upstream has support data for two entries, so every table column read `?`);
  recorded the general lesson that detectability alone does not justify a proposal.
- 2026-09-08 — `entities/wasm-runtime-compatibility` — Compatibility prologue now links to
  webassembly.org/features as the data source; `prologueAction` became a list.
- 2026-09-09 — `entities/wasm-runtime-compatibility` — compatibility is now reported only
  against browsers and runtimes; `WasmFeatureCatalog.executionRuntimes` drops the catalog's
  `Tools` entries (including Hexana's own) from the supporting-runtimes columns and the
  settings runtime list, while `runtimes` stays complete for already-declared targets.
- 2026-09-09 — `entities/wasm-runtime-compatibility` — a Compatibility row whose every
  verdict is `?` collapses to one `No data` label and sorts last; recorded that the collapse
  is faked column-wise because `TableTabDescriptor` has no cell spanning.
- 2026-09-10 — `entities/dwarf-outline-model` — extracted the DWARF tab row model into
  `plugins-shared` so the VS Code webview could render the same tabs as IntelliJ; recorded the
  un-memoized-children contract, the `groupPanels` naming rule, and three known gaps (eager
  `.debug_info`, per-byte JS buffer allocation, the webapp worker route).
- 2026-09-16 — `sources/hexana-0-20-whatsnew`, `index` — recorded the 0.20 card selection,
  checked capture policy, and the generated What's New page runtime contract.
- 2026-09-16 — `sources/hexana-0-20-whatsnew` — refined the 0.20 capture contract: dark
  breakpoint gutter, command-entry Console GIF, and editor-only structural-regions capture.
- 2026-09-10 — `entities/dwarf-outline-model` — code review pass: `.debug_info` attribute rows
  addressed `.debug_abbrev` (shared abbrev declaration), DWARF64 `strp` never resolved, and the
  IntelliJ search index was built on the EDT; all three fixed, with regression tests pinning
  that attribute offsets fall inside `.debug_info` and differ per DIE.
- 2026-09-15 — `entities/hexana-scripting-api` — added the `open(path)` script global
  (opens another local binary as the same wrapper object as `binary`); recorded the
  `scriptGlobals` assembly point, why a host-dependent global stays bound and throws
  rather than being omitted (`window.open`), and that `open` reuses `BinaryInputLoader`
  + `AllowedFilePolicy` instead of introducing a file-access path of its own.
- 2026-09-15 — `entities/hexana-scripting-api` — added `binary.path`/`binary.dir`
  (members, not globals, so they compose with `open(...)`); recorded that null means
  "no file behind this binary", that the CLI/MCP path is the resolved one via the new
  `LoadedBinary.resolvedPath`, and that `binary.name` is now derived from whichever of
  name/path the host supplied — which fixed VS Code reporting a full fsPath as the name.
- 2026-09-15 — `entities/hexana-scripting-api` — `open(path)` now works in the VS Code
  webview: the script's generator suspends at the call, the extension host reads the file,
  and the driver resumes with the parsed binary (a failure arrives as `iter.throw`, so the
  script's own catch still sees it). Recorded `ScriptOpenPolicy`, the `(yield open(p))`
  precedence trap, and the transform's new function-body skip — which also fixed printing
  from a helper failing to compile in the browser host.
- 2026-09-18 — `entities/binary-provider-selection` — new page. Provider selection was
  implemented twice (IntelliJ's `HexanaFileEditor.resolveProvider` vs the `select` +
  `nativeOrGenericBinaryProvider` pair everyone else ran) and had drifted: Parquet, Arrow
  and `.pb` resolved only in the IDE because they key off the file name, which
  `nativeOrGenericBinaryProvider` never sees. Recorded the new shared
  `createBinaryProvider` / `resolveBinaryProviderKind`, why gzip stays host-side (the
  factory is sync, common-code gunzip is `suspend` and absent on wasmJs), why Arrow stream
  magic is gated behind the extension, the `sourcePath` vs `fileName` split, and the three
  separate VS Code routing lists that have to move with it.
- 2026-09-21 — `entities/binary-provider-selection` — corrected after review. The
  extension checks ran *before* the Wasm selector, so real Wasm named `.parquet`,
  `.arrow` or `.pb` was misclassified; content now decides first and the name is a
  fallback only. Also recorded that `HexanaFileEditorProvider.accept` carries no magic
  of its own, so extensionless Wasm was unreachable until `\0asm` went into
  `NativeBinaryFileTypeDetector`; that VS Code's `inMemoryModules` branch returns before
  gzip handling, so archive entries needed the same inflation as files from disk; and
  that both hosts now cap inflation at 128 MiB.
- 2026-09-21 — `entities/binary-provider-selection` — restored the IntelliJ file-type
  checks the factory refactor had dropped. `is ParquetFileType` / `is ArrowFileType`
  were removed as redundant with magic detection, which ignored that the registry is
  also where Settings | Editor | File Types associations live, and where a truncated
  Parquet with no trailing magic is still typed. Now `resolveIdeBinaryProviderKind`
  consults it after content and name; "Open As Protobuf Message" split off to match on
  its synthetic virtual file so the action stays forced while a plain `.pb` stays a
  hint.
- 2026-09-21 — `entities/binary-provider-selection` — VS Code now contributes two
  `customEditors` entries: the claimed formats at `default`, and `*` at `option` so
  "Hexana Binary Editor" appears under "Reopen Editor With…" for any file. Prompted by
  an Arrow stream named `.stream`, which no pattern matched and whose magic is too weak
  to sniff. Recorded why the priorities cannot be merged — `default` on `*` would open
  every text file as hex, and two matching `default` editors suppress auto-opening
  entirely.

- 2026-09-23 — `entities/binary-diff-provider` — Diff unified behind `BinaryDiffProvider` in
  `plugins-shared/shared/diff/`: one view per host (`BinaryDiffEditorView`, `BinaryDiffView`)
  replaces the per-format IntelliJ views and the drifted VS Code copies, ahead of per-function
  native diff.
- 2026-09-23 — `entities/binary-diff-provider` — Native per-function diff: Functions tab + code
  detail for ELF / Mach-O / COFF, name→hash pairing, address-masked instruction comparison, and
  the host-supplied disassembler (`BinaryDiffHost`) — Capstone in IntelliJ, raw bytes in VS Code.
- 2026-09-24 — `entities/binary-diff-provider` — Webview performance: quadratic Mach-O/COFF size
  inference fixed in `NativeFunctionDiscovery` (11.5 s → 110 ms on clangd), 32-bit body hash
  for Kotlin/JS, and cooperative async tab computation + prefetch in `BinaryDiffView`.
- 2026-09-24 — `entities/binary-diff-provider` — AI review fixes: file-offset dedupe of native bodies,
  lazy `DiffOpenRequest`, webview back-stack state + virtualized detail, Wasm-only routing in
  Compare WASM, and diff release docs / QA plan (`idea-plugin/QA/13-binary-diff.md`).
- 2026-09-24 — `entities/binary-diff-provider` — One compare action per host (`hexana.compareFiles`),
  shared `rejectionReason`, old action/command ids kept as hidden aliases.
- 2026-09-25 — `entities/binary-diff-provider` — IntelliJ diff window integration: `diff.DiffTool` hex viewer + structural viewer, platform viewer switcher, positional hex diff with folds.
- 2026-09-25 — `entities/binary-diff-provider` — hex diff sections: per-side structure ranges, diff-safe palette, section bar with modified counts.
- 2026-09-25 — `entities/binary-diff-provider` — hex diff table tabs: `TableTabDiffer` (flat tabs, keyless row pairing, cell-agreement column choice).
- 2026-09-25 — `entities/binary-diff-provider` — Compare Binaries With… opens the platform diff window (Hexana viewers) instead of an editor tab.
- 2026-09-25 — `entities/binary-diff-provider` — per-entry-point default viewer via diff places.
- 2026-09-25 — `entities/binary-diff-provider` — VS Code hex diff, shared hex-diff model, Compare Selected redirect.
- 2026-09-28 — `entities/binary-inspections` — Shared binary inspections: Wasm parse, native parser diagnostics, runtime compatibility, and script findings as `BinaryProblem`s; Problems tab for every format, IntelliJ/VS Code count badge, `hexana-cli inspect-binary` (pretty/table/json/sarif) and advanced-profile `inspect_binary` MCP tool.
- 2026-09-28 — `entities/binary-inspections`, `entities/wasm-runtime-compatibility` — Proposals badges lose their warning state in IntelliJ and web-ui; the problems badge is the single marker.
- 2026-09-29 — `entities/binary-inspections` — Review fixes: untrusted problem ranges go through `selectionIn`, unique SARIF fingerprints and real tool version, OS-native path lookup for script findings, VS Code badge counted from `liveProvider` outside composition.
- 2026-09-30 — `entities/binary-inspections`, `entities/wasm-runtime-compatibility` — Proposal badges warning-coloured (no icon) with ⚠ in tooltips; `CellValue.tint`/`RowTint.Warning` tints the failing runtime cell; runtime-compatibility Problems rows open the Compatibility tab via `ProblemNavigator.selectTab`.
- 2026-09-30 — `entities/wasm-runtime-compatibility` — Proposal tooltips: affected proposals first, warning-coloured, with a shared remark; VS Code badge tooltip becomes a hover popover.
- 2026-09-30 — `entities/binary-inspections` — Problems → Compatibility navigation moved to double click (`TableTabDescriptor.rowDoubleClickAction`).
- 2026-10-09 — `entities/pcap-http-replay` — PCAP HTTP request replay: common reassembly/parse/`.http` rendering, IntelliJ replay dialog and HTTP Client scratch export.
- 2026-10-09 — `entities/pcap-http-replay` — VS Code replay dialog + REST Client `.http` export; row→request logic moved to common `HttpReplay.capturedRequest`.
- 2026-10-09 — `entities/pcap-http-replay` — Review fixes: sequence-based TCP reassembly, Connection-nominated + Proxy-Authorization header stripping, tchar methods, 1 MiB editable/inline body limit, 16 MiB response cap.
- 2026-10-09 — `entities/apk-emulator-run` — APK Run button: installs and launches on an existing Android emulator AVD; hidden unless SDK + emulator + AVD are present.
- 2026-10-09 — `entities/apk-emulator-run` — Logic moved to `plugins-shared` `AndroidEmulatorRun`; VS Code gets the APK Run button (`extension-lib` exports + `src/apkRunner.ts`, `Host.canRunApk`).
- 2026-10-09 — `entities/apk-emulator-run` — Review fixes: SDK root fallthrough, AVD-matched boot polling, strict install/launch success, prerelease ordering, cancellation.
- 2026-10-15 — `entities/dex-reveal-in-smali` — DEX Classes tab "Reveal in Smali" (IntelliJ): `enrichDexTabs` + `HexViewStateHolder.requestRevealDexClassInSmali` → Smali panel `gotoChunk(dexSmaliClassBodyId)`. Tree tables (`childrenProvider`) now show `contextMenuItems` on top-level rows.
- 2026-10-15 — `entities/dex-reveal-in-smali` — "Reveal in Smali" on VS Code/webapp: shared `withDexRevealInSmali` (plugins-shared `DexTableTabs.kt`) + `TableTabDescriptor.withExtraContextMenuItems`; web `LocalDexSmaliReveal` provided by `HexanaPanel`, consumed by the Smali tab via `ListReveal`. Web tree tables now show context menus on top-level rows.
- 2026-10-15 — `entities/dex-reveal-in-smali` — Entity page for DEX Reveal in Smali (review fix: the two entries above had no page).
- 2026-10-15 — (no page) — DEX Classes tab "Reveal in Smali" (IntelliJ): `enrichDexTabs` + `HexViewStateHolder.requestRevealDexClassInSmali` → Smali panel `gotoChunk(dexSmaliClassBodyId)`. Tree tables (`childrenProvider`) now show `contextMenuItems` on top-level rows.
- 2026-10-15 — (no page) — "Reveal in Smali" on VS Code/webapp: shared `withDexRevealInSmali` (plugins-shared `DexTableTabs.kt`) + `TableTabDescriptor.withExtraContextMenuItems`; web `LocalDexSmaliReveal` provided by `HexanaPanel`, consumed by the Smali tab via `ListReveal`. Web tree tables now show context menus on top-level rows.
- 2026-10-15 — (no page) — APK **Classes** tab (IntelliJ): shared `collectApkDexClasses`/`apkClassesDescriptor` (plugins-shared `ApkClassesTab.kt`, host supplies entry inflation); hardcoded lazy+async `apkClassesPanels` for `ZipBinaryProvider` when `isApkFileName`. "Reveal in DEX" opens the entry and calls `HexViewStateHolder.requestSelectTableRow` — a generic pending-row request consumed by flat and tree tables in `TableTabBridge`. VS Code not wired yet.
- 2026-10-15 — (no page) — APK Classes follow-up: loader is the shared `LoadingTabContent` (now internal, takes a message) with per-DEX progress; `openZipEntry` reuses an open `HexanaBinaryVirtualFile` with the same parent + `ENTRY_PATH_KEY`; `Table` now scrolls on every external selection, and `TableTabBridge.reselect` clears/re-sets the index so a repeat reveal re-scrolls.
- 2026-10-15 — (no page) — Outside table selection (`Table.externalSelectedIndex`) is now a full selection: highlight, clear hover, table focus, `onRowSelect`, scroll. The bridge no longer calls `onRowSelect` itself; the treemap click drops its duplicate hex scroll.
- 2026-10-15 — `entities/apk-classes-tab` — APK Classes tab + Reveal in DEX on VS Code: web-ui `ApkClassesTab.kt` (DecompressionStream inflate), `Host.revealZipEntryRow`, host-side entry-panel reuse + `select-table-row`, `LocalTableRowSelection` in web tables; `collectApkDexClasses` is now `suspend`.
- 2026-10-15 — `entities/apk-classes-tab` — Review fixes: DEX entry size cap, loaded classes hoisted out of tab composition, reveal clears a hiding search/filter, IntelliJ `openZipEntry` inflates off the EDT, VS Code drops orphaned pending selections, browser inflate cancels its stream.
- 2026-10-15 — `entities/apk-classes-tab` — Web APK Classes freeze: `WebByteBuffer.get()` built an `Int8Array` view per byte (now cached; DEX parse 430→70 ms in Chrome), class list uses new `DexFileParser.parseClassDirectory`, and the collector's `pause` hook paints between entries. 23-DEX APK: 9.1 s frozen → 1 s, ≤57 ms stalls.
- 2026-10-20 — `entities/ui-driver-remote-classloaders` — Run/debug UI tests failed on TeamCity with "No such class RunContentManager in plugin null": plugin-less driver-sdk refs load through the performanceTesting classloader; tests now use Hexana-plugin-scoped refs.
- 2026-10-20 — `entities/clr-metadata` — .NET (CLR) assembly support: schema-driven ECMA-335 table decoding in `parsing/pe/clr`, `PeImageFile.clr` / `PeBinaryProvider.clrMetadata`, shared `.NET` tabs, csc-built `sample-clr.exe` fixture; mutation test found unbounded signature counts and self-referencing TypeSpec blowup.
- 2026-10-20 — `entities/clr-metadata` — IL view: `ClrIlDecoder` + EH clauses + lazy `ClrTokenResolver` (not thread-safe; IntelliJ serializes), shared `ClrIlText.kt` (Smali-style chunk tree, body offsets map to their type), IntelliJ `ClrIlPanel` + web `ClrIlContent`; Disassembly tabs hidden for `isPureIl` images. Fixture gained switch/try/generic-instantiation; base64 copy for JS/Wasm tests.
- 2026-10-20 — `entities/clr-metadata` — Disassembly tabs now also hidden for ReadyToRun images (`isPureIl` → `hasOnlyManagedCode`): R2R clears IL-only and has no symbols, so the native views swept metadata (paint.net `ComputeSharp.Core.dll`). R2R OS machine XOR decoded in the .NET tab; `sample-r2r.dll` fixture.
- 2026-10-20 — `entities/clr-metadata` — IL tab renamed to "IL Disassembly" (the two-letter title was hard to spot in the tab strip).
- 2026-10-20 — `entities/clr-metadata` — ReadyToRun decoding (`ClrReadyToRun.kt`: RuntimeFunctions lengths for x64/ARM64/ARM/x86, NativeArray/NativeHashtable entry points, R2R method sigs incl. owner-type module override gotcha, manifest metadata) and the managed Top tree (`ClrSizeAnalysis.kt`); verified against crossgen2 perfmaps/maps on 4 targets.
- 2026-10-20 — `entities/clr-metadata` — AI review fixes: section raw size past EOF clamped in the size tree; IL bodies only for IL code type; linear member-list ownership; cheap PE/Top tab discovery (+ `TableTabsProvider.tabPriority`) so IntelliJ no longer decodes CLR metadata/R2R on the EDT; app-wide IL render dispatcher; QA 14-dotnet. Web composition-time parsing left as a host-wide pattern.
- 2026-10-20 — `entities/clr-metadata` — Basic .NET `.resources` support: `ClrResourcesParser` (standalone + embedded), `ClrResourcesBinaryProvider` + Header/Resources tabs, embedded sets expand in the assembly Resources tab; fixture + runtime ResourceReader dump; host extension lists updated.
- 2026-10-20 — `entities/clr-metadata`, `entities/hexana-scripting-api` — .resources review fixes (resource-count amplification, local DateTime, embedded decode off the EDT), webapp protocol now accepts dotnet-resources/arsc/parquet/arrow, browser `binary.raw.parsedResources` via a reflection-free value; found that browser reflection over Kotlin objects is dead in production bundles (minimized names).
- 2026-10-21 — `entities/pdb-support-feasibility` — PDB feasibility study: parity target = PE line info via nativeSourceLocationProvider + companion loading; work breakdown, fixtures, risks.
- 2026-10-21 — `entities/pdb-symbols` — PDB step 1: `debugInfo/pdb` MSF/info/DBI/TPI decoder, Portable PDB via CLR decoder (#Pdb, tables 0x30–0x37, documents), providers + tabs on all hosts, content-only routing, fixtures with llvm-pdbutil / System.Reflection.Metadata oracles.
- 2026-10-21 — `entities/pdb-symbols` — PDB step 2: neutral NativeSourceLocationProvider + AddressLineTable, C13 lines, Portable sequence points + embedded PDB (Inflate moved to parsing/codec), PE attach/match (GUID+age / GUID+stamp), companion loading in IntelliJ and VS Code, Debug Info/Context, IL .line, Methods Source column.
- 2026-10-21 — `entities/pdb-symbols` — AI review fixes: recorded PDB path no longer followed, regular-file + 512 MiB gate, rejections recorded, C13 block ends, DBI count overflow, cheap sequence-point count, no global *.pdb association in VS Code; composition-time decode in the webview declined (host-wide pattern).
- 2026-10-21 — `entities/binary-opened-format-analytics` — how to break down `format=generic` in PostHog; IDE misreports archive/dex/aar-like zips/protobuf/pdb as generic.
- 2026-10-21 — `entities/binary-opened-format-analytics` — fixed generic misreporting on IDE/VS Code/webapp; zip family → zip (jar/war/apk kept); new pdb/portable_pdb/arsc/dotnet_resources formats; GenericExt firmware/object buckets.
- 2026-10-21 — `entities/binary-opened-format-analytics` — review fixes: IDE format by logical (.gz-stripped) name incl. tab events; VS Code no extension fallback after failed content checks.
- 2026-10-27 — `entities/dependency-security-audit` — security audit of external deps; bumped jackson 2.21.8, logback 1.5.38, vscode-plugin/test npm overrides.
- 2026-10-28 — `entities/file-parser-associations` — new: per-file parser choice from the format badge, stored in `.hexana/file_associations.json` (+schema), IntelliJ settings page, VS Code dialog/command, live tracking.
- 2026-10-28 — `entities/file-parser-associations` — fix: forced generic was labelled by the sniffed native header; 3-arg `fileTypeDescription` honours the chosen parser.
- 2026-10-28 — `entities/file-parser-associations` — IntelliJ: replaced close+reopen (took effect only on the second switch) with an in-place provider swap via Compose state.
- 2026-10-30 — (no page) — editors hide the bottom tabs panel + divider when the tab list is empty (generic binary): IntelliJ `VerticalSplitPanel(showSouthPanel)`, web `HexanaPanel(onTabsEmptyChange)`. With no tabs the script "+" was already unreachable in both hosts.
- 2026-11-02 — `entities/file-parser-associations` — AI-review fixes: panel display override, web liveProvider keying, class/DEX tabs under forced generic, dialog row keys, `..foo` keys in VS Code, off-EDT IntelliJ rebuild, Structure view refresh, serialized + atomic association writes.
- 2026-11-02 — `entities/file-parser-associations` — IntelliJ: dropped `key(provider)` around the editor view (whole-tree teardown made a parser switch blink); BinaryEditorView state is keyed on `binaryProvider` instead.
- 2026-11-02 — `entities/file-parser-associations` — IntelliJ blink on parser switch traced (frame-by-frame) to the sidecar-restore spinner gate; now first-open only.
- 2026-11-02 — `entities/file-parser-associations` — VS Code blinks: PDB loading gate now first-open only (previous provider kept while a replacement loads its PDB); divider moved into HexanaPanel.
- 2026-11-02 — `entities/file-parser-associations` — "Edit File Associations…" in the badge menu: IntelliJ settings page, VS Code opens the JSON.
- 2026-11-03 — `entities/scripted-binary-providers` — new: scripted binary providers and auto-attached tabs, assignable via file_associations.json, in IntelliJ and VS Code.
- 2026-11-03 — `entities/scripted-binary-providers` — demo project examples/providers/ (script under .hexana/providers, firmware-image.bin, file_associations.json), tested.
- 2026-11-03 — `entities/scripted-binary-providers` — "Create New Provider Script…" menu item: shared stub, .d.ts sidecar, auto-assign; VS Code placeholder round-trip.
- 2026-11-03 — `entities/file-parser-associations` — VS Code: associated files reopen in Hexana from the text tab; removed `.bin` drift from KNOWN_EXTENSIONS (pinned to package.json by a test).
- 2026-11-03 — `entities/file-parser-associations` — VS Code: associated files now reroute on every open (loop guard scoped to the in-flight reopen).
- 2026-11-03 — `entities/scripted-binary-providers` — review fixes: overlap-aware StructureRangeLookup, stable provider-tab ids, generation-guarded catalog publish, session close on cancelled handoff, virtualized web provider tab.
- 2026-11-05 — `entities/binary-opened-format-analytics` — how `generic` opens arise per surface, HogQL breakdown queries (distinct users, post-fix versions only), and the list of formats `DetectedFormat` does not sniff.
- 2026-11-05 — `entities/ml-model-formats` — new: GGUF + safetensors support (parsers, providers, tabs, script values, routing on all hosts, reference fixtures); >2 GB files still unsupported.
- 2026-11-06 — `entities/partial-views` — new: files over the load limit open as a read-only head on IntelliJ and VS Code (`fileSize` through the factory, head reads, editing off, notices); ml-model-formats and binary-provider-selection updated.
- 2026-11-06 — `entities/partial-views` — rewritten: whole-file hex views (IntelliJ mmap, VS Code paged + scaled scroll), 64-bit ranges/selections, ELF/Mach-O/ZIP64/tar/PDB/Parquet/Arrow parse files over 2 GiB; large files read-only.
- 2026-11-09 — `entities/partial-views` — large-file notice moved from the IntelliJ notification bar and VS Code strip into an "Editing disabled" toolbar badge with the full notice as tooltip.
- 2026-11-09 — `entities/partial-views` — `ParseCoverage`: large-file warnings only when the parser missed structures (PE overlay, pcap cap, unknown formats covered); `ar` archives parse past 2 GiB.
- 2026-11-09 — `entities/partial-views` — VS Code: whole-file parsers run over `SparseLargeBytes` (zero-filled misses, fetched in rounds by `StructureBytesFetcher`, 256 MiB / 64-round caps).
- 2026-11-10 — `entities/partial-views` — VS Code paged hex view: rows re-render when pages arrive (`loadStamp` passed to `HexRow`), fetch queue capped at 4 newest-first, prefetch of neighbour pages, stale queued pages dropped.
- 2026-11-10 — `entities/partial-views` — VS Code paged hex view: placeholder rows now re-render on arrival because a `byteOrNull` miss reads `loadStamp`; the earlier `HexRow` parameter was ignored by Compose skipping (unused param).
- 2026-11-10 — `entities/partial-views` — review fixes: `extentFits` for Parquet/Arrow extents, COFF string-table coverage, GGUF/safetensors coverage + safetensors header from `source`, IntelliJ 32 GiB row limit notice, single-pass `findAll`, capped search label, TAR entries off the EDT, `.jit` whole-file hex view, long-name scan bound, fetcher tail merge + budget release, paged-search generations.
