# PDB debug symbols (Windows PDB + Portable PDB)

Plan: [[entities/pdb-support-feasibility]]. Step 1 opened both kinds of `.pdb` directly; step 2
(below, "Source lines") matches a PDB to its PE image and uses its line info.

## Two formats, one extension

| | Windows PDB | Portable PDB |
|---|---|---|
| Written by | MSVC `link`, `lld-link`, `clang-cl` | Roslyn (`-debug:portable`), F# |
| Container | MSF 7.00 blocks + stream directory | ECMA-335 metadata root (`BSJB`, version `PDB v1.0`) |
| Decoder | `debugInfo/pdb` (`PdbParser`) — new KMP module | `parsing/pe/.../clr` (`ClrMetadataParser.parsePortablePdb`) |
| Identity in the image | CodeView `RSDS` GUID + age (match the **DBI** age) | `RSDS` GUID + debug entry `timeDateStamp` = PDB id stamp; entry minor version `0x504D` (`PeDebugEntry.isPortablePdbCodeView`) |
| Provider | `PdbBinaryProvider` | `PortablePdbBinaryProvider` |

Both extend the sealed `PdbSymbolsBinaryProvider` (`pdbGuid`), so step 2's matching can treat them
uniformly. Format ids: `pdb`, `portable-pdb` (`binaryFormatId`, webapp worker, scripting docs).

## Decisions worth remembering

- **Content only, never the extension.** `.pdb` is also the Protein Data Bank *text* format. IntelliJ
  registers `PdbFileType` with **no extensions** and types files in `NativeBinaryFileTypeDetector`
  through `PdbSymbolsBinaryProvider.isPdbSymbolsFile` (same check as the factory). VS Code: not in the
  `customEditors` pattern nor `KNOWN_EXTENSIONS`; `isNativeBinary` sniffs MSF / `BSJB`+`PDB v`, which
  also opens `.pdb` entries of `.nupkg`/`.snupkg`; the diff gate uses `CONTENT_TYPED_EXTENSIONS`.
- **MSF reads are on demand.** `MsfStream` maps stream offsets through the block list; nothing but the
  directory is materialized. Decoders only ever get streams for which `isFullyReadable()` holds, so the
  `!!` reads inside a checked stream size cannot fail on a truncated file.
- **Portable PDB = CLR metadata + `#Pdb`.** Debug tables 0x30–0x37 got real schemas (they used to stop
  table layout with `clr.unknown_table`) plus the `HasCustomDebugInformation` coded index (5 bits,
  27 tables). Their `MethodDef`/coded index widths depend on the *assembly's* row counts, which only
  the `#Pdb` stream's referenced-tables mask provides — merged into `ColumnSizing` by max.
- Document names are a blob of separator + compressed `#Blob` indices of UTF-8 parts.
- `buildList { size }` inside `MsfStream` resolves to the list, not the stream — use `this@MsfStream`.
- `PdbFile.identity` uses the DBI age (the info-stream age is bumped by incremental links).

## Tabs (`plugins-shared/PdbTableTabs.kt`, cheap names via `availableTabNames`)

- Windows: **PDB** (format, blocks, GUID/age/signature, features, DBI version/machine/toolchain/flags,
  TPI/IPI counts, named streams, warnings), **Streams** (role from `PdbFile.streamRoles`), **Modules**,
  **Source Files**, **Section Contributions**. Structure ranges: superblock, block map, every stream
  block run (capped at 4096).
- Portable: **Portable PDB** (PDB id, entry point, languages, assembly table rows), **Documents**,
  **Streams** + **Tables** (the CLR tabs, now `internal`).
- PE **Debug** tab labels types 17 (Embedded Portable PDB) and 19 (PDB Checksum).

## Fixtures and oracles

- `parsing/native-fixtures/test-data/pe/sample.pdb` (+ `sample.exe`), oracle `sample.pdb.llvm-pdbutil.txt`.
- `sample-clr-portable.exe` + `.pdb` (deterministic `csc -debug:portable`, pathmapped), oracle
  `sample-clr-portable.pdb.dump.txt` from `src/clr/pdb/DumpPortablePdb.cs` (`dotnet run`, uses
  `System.Reflection.Metadata`; it also prints per-method sequence-point counts for step 2).
- Both parser tests include random-mutation "never throws" loops.

## Source lines (step 2)

```
PE image ──CodeView RSDS──► PePdbReference.Windows(guid, age) | .Portable(guid, stamp)   (entry minor 0x504D)
   │                                     ▲
   │  host, before tabs:                 │ pdbMatch(): GUID + DBI age / GUID + #Pdb stamp
   │  PdbCompanionLoader.candidatePaths ─┴─ attach(bytes) ─► PeBinaryProvider.attachDebugSymbols
   │      IntelliJ: load(pe, JVM_FILE_SYSTEM) in BinaryEditorView (next to the pcap key-log restore)
   │      VS Code webview: host.readBinaryFile (script open() channel), "Loading debug symbols…" gate
   │      webapp: none (no file access) — embedded PDBs still work
   ▼
debugSymbols = File(symbols, path) | Embedded(type 17, MPDB + deflate)   rejection → debugSymbolsRejection
   ├─ nativeSourceLocationProvider (Windows PDB): C13 rows at imageBase + section RVA + offset
   │      → Disassembly source lines, PE "Debug Info" tab
   └─ portablePdb: sequencePoints(methodRow)
          → IL view `.line a,b : c,d 'doc'` (doc only when it changes), Methods "Source" column
```

- **`NativeSourceLocationProvider` is the neutral interface** (`NativeDwarfSourceLocationProvider` is
  one implementation). `AddressLineTable` does the lookups by binary search — the DWARF provider used a
  linear `findLast` per instruction before, so it got faster too.
- **Candidate order:** recorded file name next to the image, `<stem>.pdb`, `<name>.pdb`, then the
  recorded absolute path (a local build). Skipped entirely when the PDB is embedded or already attached.
- **Attach before tabs, never after.** Tab discovery is cheap (`availableTabNames` checks
  `debugSymbols != null`, which reads only the debug directory), and tabs are not rebuilt later. IntelliJ
  inserts tabs without placeholders before the script panels, so "Debug Info" appears anyway.
- **Debug Context** PDB row: `found` (loaded, with identity), `does not match` (reason from `PdbMatch`),
  `unverified` (not searched / not loaded), `missing`. The nearby-file scan no longer adds a second PDB row.
- **Inflate moved** from `parsing/pcap` (internal) to `parsing/codec` (`Inflate`, `inflateComplete`), now
  shared by TLS decompression and embedded Portable PDBs on every target.
- `!` in `llvm-pdbutil dump -l` marks rows **without** the is-statement bit; clang never sets it.
- Deterministic Roslyn builds give `-debug:portable` and `-debug:embedded` variants of the same source the
  **same PDB id** — they legitimately match each other.
- Sequence point blob: δIL (0 after the first record = document change), ΔLines, ΔColumns (unsigned if
  ΔLines is 0, else signed); both 0 = hidden; start line/column absolute for the first visible point, then
  signed deltas. Compressed signed ints sign-extend from 6/13/28 bits.

## Not done yet

- Native function names from PDB symbols (`S_PUB32`, `S_GPROC32`) for PE disassembly; CodeView in COFF
  `.debug$S`; TPI types / locals.
- ReadyToRun native code → IL offset (R2R DebugInfo section) to show source lines in R2R disassembly.
- VS Code reads go through the 100 MiB script-`open()` cap, so larger PDBs are not loaded there.
- MCP/CLI do not attach PDBs (`resolve_source_locations` is Wasm-only).

## Files

- `debugInfo/pdb/src/commonMain/.../pdb/{Msf,PdbModel,PdbParser}.kt`, test `jvmTest/.../PdbFixtureTest.kt`
- `parsing/pe/.../clr/{ClrPortablePdb,ClrTableSchema,ClrMetadataParser}.kt`, test `jvmTest/.../ClrPortablePdbTest.kt`
- `binaryProvider/.../PdbBinaryProvider.kt`; `plugins-shared/.../PdbTableTabs.kt` (+ `PdbTableTabsTest`)
- `idea-plugin/.../PdbFileType.kt`, `NativeBinaryFileTypeDetector.kt`; `vscode-plugin/src/NativeBinaryFileTypeDetector.ts`
- Step 2: `debugInfo/pdb/.../PdbLines.kt` (`PdbLinesFixtureTest`), `parsing/pe/.../clr/ClrPortablePdb.kt`
  (sequence points, `ClrEmbeddedPortablePdb`), `binaryProvider/.../PeDebugSymbols.kt` (`PeDebugSymbolsTest`),
  `NativeDwarfSourceLocationProvider.kt` (interface + `AddressLineTable`), `plugins-shared/.../PdbCompanionLoader.kt`
  (`PdbCompanionLoaderTest`), `NativeDebugCompanionScanner.kt`, `NativeTableTabs.kt` (PE Debug Info),
  `ClrIlText.kt` / `ClrTableTabs.kt`, `idea-plugin/.../BinaryEditorView.kt`, `vscode-plugin/webview/.../Main.kt`
