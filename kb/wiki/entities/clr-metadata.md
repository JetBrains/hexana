# CLR (.NET) metadata support

Managed PE images (a non-empty CLR runtime header, data directory 14) are decoded by
`parsing/pe/.../pe/clr/` and surfaced as extra tabs on the ordinary PE editor. There is no
separate provider or file type: a .NET assembly is a `PeBinaryProvider` whose `clrMetadata`
is non-null.

## Parser (`parsing/pe`, common code)

- `ClrMetadataParser.parse(bytes, optionalHeader, sections)` → `ClrMetadata?`. Null means no
  CLR directory or an unreadable CLI header; everything deeper is best effort and reported in
  `ClrMetadata.diagnostics` (merged into `PeParser.parse` diagnostics, plus
  `pe.invalid_clr_header` when the header itself fails). `PeImageFile.clr` carries the result.
- `hasClrHeader(optionalHeader)` is the cheap check behind `PeBinaryProvider.isManaged`
  (used for the `PE32 (.NET)` file type label without decoding metadata).
- Reuses PeParser's byte helpers and `rvaToFileOffset`, made `internal` for this.
- Tables are schema-driven: `ClrTableSchema.kt` lists all ECMA-335 tables 0x00–0x2C and the
  13 coded-index kinds; column widths follow II.24.2.6 (heap-size bits, 2/4-byte simple and
  coded indexes). Cells are read on demand from the buffer, not materialized.
- An unknown table id (e.g. portable PDB tables 0x30–0x37) cannot be skipped, so it and every
  later table get `range = null` and a `clr.unknown_table` warning. Assemblies never contain
  them; standalone/embedded PDB metadata does.
- `#-` (uncompressed) streams: member lists index the FieldPtr/MethodPtr/ParamPtr tables; the
  owner maps (`memberOwners`) resolve through them. Hence types expose no member `IntRange`;
  use `ClrMetadata.fieldsByType` / `methodsByType`.
- `ClrSignatureDecoder` renders II.23.2 blobs C#-style (`U Map<U>(Func<T, U> map)`), using
  GenericParam names and Param names. Hardening found by the mutation test: element counts are
  checked against remaining blob bytes, rank/generic counts are capped, and a node budget is
  shared across re-entrant TypeSpec decoding (self-referencing TypeSpecs otherwise explode).
- Also decoded: method body headers (tiny/fat → IL range), embedded manifest resources
  (CLI resources directory + offset, u32 length prefix), `#US` strings, the assembly's
  `TargetFrameworkAttribute`, the entry point token, and ReadyToRun (`RTR` signature at the
  managed native header).
- Not decoded (yet): custom attributes in general, properties/events, PDBs,
  strong-name public key tokens of the assembly itself (needs SHA-1), MCP summary counts.

## Tabs (`plugins-shared/ClrTableTabs.kt`, all hosts)

Added by `NativeTableTabsProvider` for PE: `.NET`, `Types` (tree: fields then methods as
children, same column layout as the parent), `Methods`, `Assembly Refs` right after `Headers`;
`groupedTabs` adds `.NET Metadata` (Streams, Tables, Type Refs, Member Refs, User Strings,
Resources). `PeBinaryProvider.sizeAnalysis` nests "CLR metadata" (per stream) and "Managed
resources" under the section containing them. Scripts see `clrMetadata` through the
reflection-based API summary; `binary.format` stays `"pe"`.

## IL view

- Decoder: `ClrIlDecoder` (`ClrIl.kt`) — table-driven, all Partition III opcodes incl. `0xFE`
  prefix; branch targets are absolute IL offsets; an undefined byte becomes an `opcode = null`
  instruction (rendered `.byte`) and decoding continues; an operand past the code end stops with
  `truncatedAt`. Fat bodies also carry `exceptionClauses` (small/fat EH sections) and `initLocals`.
- Operand text comes from `ClrMetadata.tokens: ClrTokenResolver`, implemented by the parser's
  `Decoder` so it reuses its heaps/caches lazily (no pre-rendered call signatures: CoreLib would
  pay for 45k strings up front). **Not thread-safe** — IntelliJ renders on
  `Dispatchers.IO.limitedParallelism(1)` per assembly; web is single-threaded anyway.
  MethodSpec instantiations are fed in as the method generic context, so calls read
  `string Box<int>::Map<string>(Func<!0, string>)`; `!0` of a MemberRef parent stays positional
  (as in ILDasm).
- Text model: `plugins-shared/ClrIlText.kt`, a sibling of `DexSmaliText.kt` (type header +
  lazily rendered body chunks; rows carry `fileOffset` + `byteSize`). Its chunk tree maps not only
  TypeDef rows but also Field/MethodDef rows and method bodies back to the type body, so a hex
  offset inside IL finds its type. Tab name `CLR_IL_TAB_NAME` = "IL Disassembly".
- Hosts: IntelliJ `ui/panel/ClrIlPanel.kt` (VirtualizedChunkPanel; gutter click scrolls and selects
  the row's bytes), web `web-ui/.../ClrIlContent.kt` via `computeExtraTabs` for a managed
  `PeBinaryProvider`. Both are near-copies of the Smali hosts; a shared "foldable class listing"
  host component would remove that duplication.
- Native **Disassembly** tabs (IntelliJ only) are hidden when `PeBinaryProvider.hasOnlyManagedCode`:
  no native entry point and (IL-only **or** ReadyToRun). ReadyToRun images *do* contain native code
  (precompiled from IL), but no symbols, imports or exports, so the disassembly panels can only sweep
  `.text` — metadata and IL included — and show nothing useful; mapping R2R code to methods
  (RuntimeFunctions + MethodDefEntryPoints sections) would be what makes it worth showing again.
  Only mixed-mode C++/CLI keeps the tabs. Reads the CLI header + RTR signature only, so tab
  discovery stays cheap. The UI-test probe mirrors this. Sample: paint.net 5.1 arm64 — 183 R2R +
  89 IL-only assemblies → IL only; no mixed-mode file there to check the other branch.
- ReadyToRun gotchas: crossgen **clears the IL-only flag** (looks like mixed-mode unless the RTR
  header is checked), and non-Windows targets XOR the COFF machine with an OS constant (macOS
  0x4644, Linux 0x7B79, FreeBSD 0xADC4, NetBSD 0x1993, SunOS 0x1992; e.g. macOS ARM64 = 0xEC20).
  `clrPlatform` decodes both. Fixture: `test-data/pe/sample-r2r.dll` (`src/clr/r2r`, `dotnet publish`).
- Measured (JVM): rendering every type of CoreLib = 845k rows in ~0.5 s, worst type
  (`System.Array`) 12 ms, zero unresolved tokens/undefined opcodes; FSharp.Core 268k rows, 0 bad.

## ReadyToRun code → methods, and the Top tab

- `ClrReadyToRun.kt`: `ClrReadyToRunParser.parse(bytes, metadata, machine, …)` →
  `ClrReadyToRunImage` (R2R header + sections, RuntimeFunctions, methods, import sections, EH info);
  `PeBinaryProvider.readyToRunImage` is the lazy entry point. Format refs: dotnet/runtime
  `docs/design/coreclr/botr/readytorun-format.md` and `src/coreclr/tools/Common/Internal/NativeFormat`.
- Function lengths per arch: x64 RUNTIME_FUNCTION has an explicit end (12-byte entries); ARM64 /
  ARM read FunctionLength from packed unwind data or the `.xdata` header (×4 / ×2, Thumb bit masked);
  x86 has no OS unwind data — UnwindData points at GC info whose header starts with the method size
  as a big-endian 7-bit varint. All four verified exactly against crossgen2 perfmaps.
- Methods: MethodDefEntryPoints is a NativeArray (16-element blocks, tree-coded), InstanceMethodEntryPoints
  a NativeHashtable whose entries are R2R method signatures (`READYTORUN_METHOD_SIG_*`) followed by the
  entry id (`id&1` → fixups, `>>2`; else `>>1`). A method owns its runtime function up to the next
  method's (funclets). **Gotcha:** a `MODULE_ZAPSIG` wrapping the *owner type* also moves the method
  token into that module (no `UpdateContext` flag is emitted) — reading it as a local MethodDef gives
  out-of-range rows. Module index `i`: `1..n` = own AssemblyRef `i`; `n+1+k` = ManifestMetadata
  AssemblyRef `k` (empirical, consistent across images referencing 1/5/30 assemblies).
  `ClrMetadataParser.parseStandalone` decodes the manifest blob.
- Version-bubble images (`flags & 0x40`) are mostly *other assemblies'* generics: paint.net's
  `ComputeSharp.Core.dll` has 34 KiB of its own code and 181 KiB of CoreLib instantiations.
  Those can't be named without the other assembly; they show as `[Assembly]0x06xxxxxx` under
  "Compiled from other assemblies". Resolving them from sibling files would be a host feature.
- Size tree: `binaryProvider/ClrSizeAnalysis.kt` (`clrSizeClaims` + `clrSizeAnalysis`), used by
  `PeBinaryProvider.sizeAnalysis` for managed images (native PE unchanged). Claims are made per
  category in priority order and clipped linearly against earlier coverage (Windows exception dir ==
  RuntimeFunctions). Implicit-size blobs (unwind+GC info, fixup signatures, GC ref maps) run to the
  next blob / next claimed byte in a second pass. Gaps between two code claims → "Alignment and
  read-only data" (jump tables/literal pools live there); other gaps: trailing 0x00/0xCC fill →
  Padding (crossgen pads x86/x64 `.text` with int3), rest → Unattributed. RuntimeFunctions has a
  4-byte 0xFFFFFFFF terminator outside its section size.
- Validated against crossgen2 `--map --mapcsv` for all four fixture targets: every node lands in the
  right group, x64 `.text` fully attributed. Real images: Unattributed in `.text` = 0 (ComputeSharp),
  6 KiB / 10.4 MiB (PaintDotNet.Core), 75 KiB / 11.8 MiB (CoreLib); CoreLib tree in ~160 ms (JVM).
- Fixtures `sample-r2r{,-win-x64,-win-x86,-linux-arm}.dll` + perfmaps from
  `src/clr/r2r/build-r2r-fixtures.sh` (crossgen2 from the NuGet cache; deterministic).

## Review fixes (2026-10-20)

- IL bodies are parsed only when ImplFlags code type is IL (`implFlags & 3 == 0`); C++/CLI native,
  OPTIL and runtime methods have RVAs that are not IL.
- Member lists (TypeDef FieldList/MethodList, MethodDef ParamList) go through `forEachMemberList`:
  runs are clamped to never overlap, so malformed starts cannot make ownership O(rows × list length);
  a non-ascending list emits `clr.unordered_member_lists`. A row's run still ends at the next row's
  *declared* start (II.22), so a bogus start empties the preceding row's list.
- IntelliJ lists tab placeholders in a `remember` initializer (EDT, `discoverPanelNames`); providers
  without `availableTabNames` fall back to building every tab there. PE now advertises names cheaply
  (`NativeTableTabsProvider.peTabBuilders` is the one ordered name→builder table; `singleTab` resolves
  each in the background) and `SizeTableTabsProvider` advertises `Top` for PE — otherwise opening a
  managed PE decoded metadata *and* the whole R2R size tree on the EDT. `TableTabsProvider.tabPriority`
  lets cheap names keep Low priority (Debug Context stays last). ELF/Mach-O still use the fallback.
- IL rendering in IntelliJ uses one app-wide `limitedParallelism(1)` dispatcher, not one per panel:
  an un-cancellable `clrIlTypeRows` outlives a tab switch, and the resolver is shared per provider.
- Web host: every table tab (any format) and `computeExtraTabs` are built synchronously in
  composition, and IL types render inside `remember` on expand (same as the Smali view). Left as is:
  the lazy metadata parse is shared and paid once whichever caller runs first; making it async is a
  web-host-wide change, not a CLR one.
- Manual QA: `idea-plugin/QA/14-dotnet.md`.

## .resources files

- `ClrResources.kt`: `ClrResourcesParser.parse(bytes, range)` — standalone files (range = whole file)
  or blobs embedded in an assembly (a ManifestResource `dataRange`). Format per
  `System.Resources.ResourceReader`: ResourceManager header (`0xBEEFCACE`, version, skip size; v1
  holds reader + resource set type names), reader header (version, counts, type table, `PAD`
  alignment to 8 *relative to the blob start*, hashes, name offsets, absolute data section offset),
  UTF-16 names each followed by a data offset, then type-coded values (`ResourceTypeCode`; user
  types from 0x40 index the type table). Serialized values have no length, so they run to the next
  value. Version 1 values are not decoded.
- `ClrResourcesBinaryProvider` extends `GenericBinaryProvider` (like ARSC), detected by magic in
  `nativeOrGenericBinaryProvider`; format id `dotnet-resources`. Tabs: `ClrResourcesTableTabs.kt`
  (Header, Resources; cheap names + `singleTab`). Embedded `*.resources` rows in the assembly's
  `.NET Metadata → Resources` tab expand via `clrResourceChildRows` (decoded on first expansion).
- Review fixes: name offsets are decoded once (several entries sharing one name entry let a 4 MB file
  retain ~100 MB) and at most 200,000 resources (`clr.resources.shared_names` / `.truncated`).
  `Kind=Local` DateTimes hold the UTC instant (`ToBinary`), wrapped by `TicksCeiling` before year 1, and
  are shown as `…Z, Kind=Local`. Embedded sets are decoded when the Resources descriptor is built (a
  background job in IntelliJ), because tree tables call `childrenProvider` for every root row while composing.
- Webapp: the parser-worker protocol (`ParserWorkerPresentationProtocol.supportedFormats`) must list every
  id `nonWasmFormat` emits, or the worker posts PARSE_FAILED; `arsc`, `parquet`, `arrow` were missing too.
- Hosts: IntelliJ `HexanaFileEditorProvider` extension; VS Code `package.json` pattern,
  `KNOWN_EXTENSIONS`, `isNativeBinary` magic, `HEXANA_DIFF_EXTENSIONS`, `OPENABLE_ENTRY_EXTENSIONS`;
  webapp worker format id; scripting `.d.ts` regenerated.
- Verified against the runtime's own `ResourceReader` dump of the fixture, and on real embedded sets:
  CoreLib (1,405 strings), WinForms dialogs written by `DeserializingResourceReader` (user types).
  Fixture written with Mono's `ResourceWriter` — .NET 8+ refuses to serialize user types.

## Assembly diff

Two managed images compare through `ClrDiffProvider` (Types / Methods tabs, IL side by side); see
[[entities/binary-diff-provider]] → ".NET assembly diff". It reuses `ClrIlText` (`clrIlMethodRows`,
`clrIlTypeHeaderRows`) and needs the same single-threaded token resolution.

## Fixture and measurements

- `parsing/native-fixtures/test-data/pe/sample-clr.exe` from `src/clr/Sample.cs` (Roslyn `csc`,
  outside the Docker generator; see the fixture README). Expectations cross-checked with `monodis`
  (metadata) and `ikdasm` (IL). A base64 copy in `parsing/pe` commonTest runs the parser, IL
  decoder and resolver on JS and WasmJS too.
- JVM: `System.Private.CoreLib.dll` (15 MB, 38.5k methods, ReadyToRun) parses in ~190 ms with
  zero undecodable signatures; `FSharp.Core.dll` in ~50 ms.

## Blazor WebAssembly (Webcil)

Webcil assemblies inside `.wasm` wrappers reuse this decoder through synthesized PE sections; the CLR tabs and IL
views take `ClrAssemblySource` rather than `PeBinaryProvider`. See [[entities/blazor-wasm-feasibility]].

