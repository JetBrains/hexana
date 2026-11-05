# Changelog

All notable changes to the Hexana Webapp are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- GGUF and safetensors model files open with "Header", "Metadata" (GGUF only) and "Tensors" tabs, recognized by content; scripts read them as `binary.raw.parsedGguf` / `binary.raw.parsedSafetensors`.
- Navigable nodes in the component "Dependencies" graph: double-clicking a core module or a nested component opens it in its own tab, and double-clicking an import or an export selects that entity's bytes in the hex view. The byte ranges travel with the graph the parser Worker already produces, so no reparse happens on the Window. Core and component instances stay inert — the parser records no per-entry offset for them.
- .NET assemblies: a ".NET" overview, "Types", "Methods", "Assembly Refs" and ".NET Metadata" tabs, an "IL Disassembly" view (one foldable section per type, with resolved call targets and `.try` clauses), and a "Top" breakdown of every section by what owns its bytes — ReadyToRun native code per method, ReadyToRun tables, IL bodies, metadata and resources.
- .NET `.resources` files open with "Header" and "Resources" tabs listing every entry with its decoded value; resource sets embedded in an assembly expand in its ".NET Metadata → Resources" tab. Scripts read the entries through `binary.raw.parsedResources`.
- PDB debug symbols open with their tabs: Windows PDBs ("PDB", "Streams", "Modules", "Source Files", "Section Contributions") and .NET Portable PDBs ("Portable PDB", "Documents", "Streams", "Tables"). The parser Worker reports them as `pdb` and `portable-pdb`.
- .NET assemblies with an embedded Portable PDB (`-debug:embedded`) show ILDasm-style `.line` directives in "IL Disassembly" and a "Source" column in "Methods".

### Fixed

- Android `resources.arsc`, Apache Parquet and Arrow files no longer fail with "Failed to parse the file in the Worker": the parser Worker produced a format the Window's protocol did not accept, and dropped the whole presentation.

## [0.1.1] - 2026-08-28

### Changed

- Replaced the About popup with an accessible Release notes drawer that shows the bundled release history.
- Expanded custom scripts with a shared binary API and structured table output.

## [0.1.0] - 2026-08-14

### Added

- Initial public Webapp release.
