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
  offset inside IL finds its type. Tab name `CLR_IL_TAB_NAME` = "IL".
- Hosts: IntelliJ `ui/panel/ClrIlPanel.kt` (VirtualizedChunkPanel; gutter click scrolls and selects
  the row's bytes), web `web-ui/.../ClrIlContent.kt` via `computeExtraTabs` for a managed
  `PeBinaryProvider`. Both are near-copies of the Smali hosts; a shared "foldable class listing"
  host component would remove that duplication.
- Native **Disassembly** tabs (IntelliJ only) are hidden when `PeBinaryProvider.isPureIl`: CLI
  header says IL-only, no native entry point, and no ReadyToRun header — read from the CLI header
  alone so `discoverHardcodedPanelNames` stays cheap. The UI-test probe mirrors this list.
- Measured (JVM): rendering every type of CoreLib = 845k rows in ~0.5 s, worst type
  (`System.Array`) 12 ms, zero unresolved tokens/undefined opcodes; FSharp.Core 268k rows, 0 bad.

## Fixture and measurements

- `parsing/native-fixtures/test-data/pe/sample-clr.exe` from `src/clr/Sample.cs` (Roslyn `csc`,
  outside the Docker generator; see the fixture README). Expectations cross-checked with `monodis`
  (metadata) and `ikdasm` (IL). A base64 copy in `parsing/pe` commonTest runs the parser, IL
  decoder and resolver on JS and WasmJS too.
- JVM: `System.Private.CoreLib.dll` (15 MB, 38.5k methods, ReadyToRun) parses in ~190 ms with
  zero undecodable signatures; `FSharp.Core.dll` in ~50 ms.
