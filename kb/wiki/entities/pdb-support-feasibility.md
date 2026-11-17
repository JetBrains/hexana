# PDB support — feasibility (2026-10-21)

Investigation of reaching "DWARF-level" support for Windows PDB. Step 1 (open `.pdb` files, both kinds) is done — see [[entities/pdb-symbols]].

## What "DWARF level" actually means today

- **Wasm** gets the rich stack: `.debug_*` outline tabs ([[entities/dwarf-outline-model]]), CDP debugger
  (breakpoints, inline frames, variables, `DwarfTypeInfo` memory decode), DWARF function names,
  `DwarfIndex` file index, MCP `resolve_source_locations` / `resolve_wasm_frames`, SARIF.
  All DWARF-typed; irrelevant to PDB (PDB never describes Wasm).
- **ELF / Mach-O** get only: `NativeDwarfSourceLocationProvider` (VA → file:line, file:line → VAs,
  `NativeDebugInfoSummary`) via `NativeBinaryProvider.nativeSourceLocationProvider`
  (`binaryProvider/.../BinaryProvider.kt:319`), consumed by `NativeDisassemblyPanel.kt:621` and the
  "Debug Info" tab (`NativeTableTabs.kt`). No `.debug_*` tree tabs for native, no companion loading,
  no compressed-section support.
- **PE today**: `PeParser` reads `IMAGE_DEBUG_DIRECTORY` + CodeView `RSDS`/`NB10` (GUID, age, path);
  `NativeDebugCompanionScanner` finds `stem.pdb` nearby but marks it Unverified. Nothing reads the PDB.

So the native parity target is small: PDB line info → `nativeSourceLocationProvider` for PE.

## Seams

- `HexanaSourceLocationProvider` is neutral (two backends already: DWARF, source map) but Wasm-offset based.
- `nativeSourceLocationProvider` methods are neutral; only the return type is the concrete DWARF
  class → extract an interface (`NativeSourceLocationProvider`) and keep DWARF as one impl.
- **New concern DWARF never had:** PDB is always a *separate file*. Providers are built from
  bytes+name with no file access, so companion loading must come from the host (the scanner
  already has a `FileSystem` abstraction) and be verified by GUID+age against the PDB Info stream.
- Mapping: PDB lines are `section:offset` → RVA (PE section headers) → VA (+ image base).

## Format work (estimated, commonMain KMP like `debugInfo/dwarf`)

| Piece | Needed for | Size |
|---|---|---|
| MSF container (superblock, block map, stream directory, stream view) | everything | small |
| PDB Info stream (version, GUID, age, named-stream map → `/names`) | verification, string table | small |
| DBI stream (header, module info, section contribs/map, file info) | locating module streams | medium |
| Module stream C13 subsections (`DEBUG_S_LINES`, `FILECHKSMS`) + `/names` | **line info (parity)** | medium |
| Symbol records (`S_GPROC32`/`S_LPROC32`, `S_PUB32`, `S_END`) | function names in PE disassembly (PE images usually have no COFF symbols — a win beyond DWARF parity) | medium |
| TPI/IPI type records (`LF_*`), `S_LOCAL`/`S_DEFRANGE_*` | types/variables — **not** at native DWARF level today | large, defer |

Same CodeView record format appears in COFF objects' `.debug$S` / `.debug$T` (present in
`sample.coff`), so the C13/symbol decoders cover objects too.

## Fixtures / oracle

- `parsing/native-fixtures/test-data/pe/sample.exe` + `sample.pdb` (lld-link, MSF 7.00, 4K blocks)
  are a **matched pair** (GUID `{AEBD2BE3-8B00-6D1E-4C4C-44205044422E}`, age 1). PDB has TPI/IPI/DBI,
  publics, globals, one real module with C13 lines for `/src/src/sample.c`.
- `llvm-pdbutil dump -l / -symbols / -publics` (Homebrew LLVM) gives golden output for tests,
  mirroring how `*.llvm-readobj.txt` snapshots are used.
- Gap: no MSVC-linked PDB (`link.exe` emits more record kinds, `/DEBUG:FASTLINK` PDBs have no
  line info, huge PDBs). Fixture generator is Linux clang; MSVC samples would need to be checked in.

## Risks

- Size: real PDBs are 100 MB–GBs; must stream-on-demand over `CommonByteBuffer`, not decode
  eagerly (the DWARF `.debug_info` eager-parse gap must not be repeated).
- Robustness: malformed block maps / cyclic stream directories — follow the CLR hardening pattern
  ([[entities/clr-metadata]]) with a mutation test.
- Portable PDB (.NET, `BSJB` metadata, also embedded-deflated as debug-directory type 17) is a
  *different* format; cheap to add on top of the existing ECMA-335 decoder, separate track.
