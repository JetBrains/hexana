# PDB debug symbols (Windows PDB + Portable PDB)

Step 1 of the plan in [[entities/pdb-support-feasibility]]: open both kinds of `.pdb` directly.
Line info, PE ↔ PDB matching and companion loading are the next steps.

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

## Next (step 2)

C13 `DEBUG_S_LINES`/`FILECHKSMS` + `/names` for Windows; `MethodDebugInformation` sequence points for
Portable; embedded Portable PDB (type 17, `MPDB` + deflate — common code has no sync inflate on
`wasmJs`); a neutral `NativeSourceLocationProvider` interface replacing the DWARF-typed
`nativeSourceLocationProvider`; host-side companion loading verified by GUID+age / GUID+stamp.

## Files

- `debugInfo/pdb/src/commonMain/.../pdb/{Msf,PdbModel,PdbParser}.kt`, test `jvmTest/.../PdbFixtureTest.kt`
- `parsing/pe/.../clr/{ClrPortablePdb,ClrTableSchema,ClrMetadataParser}.kt`, test `jvmTest/.../ClrPortablePdbTest.kt`
- `binaryProvider/.../PdbBinaryProvider.kt`; `plugins-shared/.../PdbTableTabs.kt` (+ `PdbTableTabsTest`)
- `idea-plugin/.../PdbFileType.kt`, `NativeBinaryFileTypeDetector.kt`; `vscode-plugin/src/NativeBinaryFileTypeDetector.ts`
