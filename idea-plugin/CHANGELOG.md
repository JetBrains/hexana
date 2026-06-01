# Changelog

## Unreleased

### Added
- GraalVM Web Image: `.wasm` modules compiled from Java via Web Image (`native-image --tool:svm-wasm`) are detected by their `interop`/`jsbody`/`compat` import fingerprint. Functions whose names demangle to a Java method get a "Java" badge — in the Functions tab and inline on each code-section entry in the WAT (virtualized) view — that opens the corresponding method's bytecode. Available when the module retained function names (built with debug names); stripped/minified and Graal-internal functions are left unbadged
- jitview: the `.jit` view splits into Combined / Bytecode / Machine Code tabs, each with multiline selection and copy (full text / bytecode / hex / instruction bytes), a per-tab Go-To-Offset, and a symbol-name header showing the current method; the Machine Code tab can copy offset + instruction bytes
- jitview: search compiled methods by inlined callee with `in:<method>` — surfaces every nmethod that inlined the given method, using the JVMTI inline records in the dump
- JIT Viewer: new "Instrument JMH forks" option attaches the dump agent to each forked JMH benchmark JVM via a bundled `-javaagent`, writing one `ClassName_method.jit` per benchmark; because it rides on JVM options (not JMH's `-prof`) it works for any JMH run type — the IDE gutter run, the Gradle JMH plugin, and `org.openjdk.jmh.Main`
- WAT (virtualized): custom sections now render as a foldable entry — an `offset hex name` header with the section's raw content below, shown in selectable `u8`/`u16`/`u32`/`u64`/`ascii`/`utf8` encodings (the same representation as Data entries)

### Changed
- WAT (virtualized): WasmGC `(rec …)` recursive type groups now render as a collapsible group (collapsed by default) instead of a flat list of types; reference-type targets show as `$typeN` (e.g. `(ref null $type4923)`)

### Deprecated

### Removed

### Fixed
- Top (size analysis): switching to the Top tab no longer freezes the IDE on large binaries — the top-level size tree and data-segment parse ran inside composition on the EDT; they now build off-thread with a loading indicator
- Functions tab: the Name column and the unreachable `●` marker were keyed by the defined-function row index rather than the global function index, so for modules with function imports both were shifted by the import count; they now resolve the correct name and reachability for every row
- WAT (virtualized): opening a large WasmGC binary no longer hangs the IDE. The type section's single `(rec …)` group (tens of thousands of types) was rendered as one multi-megabyte row, freezing the UI thread in text layout; it now folds to a collapsible group with each type as a separate virtualized row, and the view's model is built off the UI thread

### Security

## 0.10 - 2025-05-27

### Added
- Goto Symbol / Search Everywhere → Symbols now surfaces `.wit` declarations, component-model exports, and regular `.wasm` exports; picking a `.wasm` export opens the file and selects the matching row in the Exports tab
- Editable binary documents — inline WAT row editing, hex-cell editing, length-changing append+redirect, IDE-keymap Undo/Redo, Discard / Finalize buttons, sidecar persistence with restore-on-reopen — plus a virtualized WAT viewer with per-entry Function / Data / Import / Export breakouts (foldable xxd bodies, `u8`/`u16`/`u32`/`u64`/`ascii`/`utf8` view modes for Data), Go-To-Declaration / Go-To-Symbol / Back / Forward navigation through the IDE keymap, and a section shortcut bar.
- Java archives & class files: hex view on top + tabs/list below for `.jar`/`.zip`/`.war`/`.apk` (searchable, sortable class list) and `.class` (header / WAT-style foldable methods with decoded bytecode / constant pool); project-view `Open in... → Hexana` action for archives, including entries under External Libraries. [#91](https://github.com/JetBrains/hexana/issues/91)
- JIT Viewer run-configuration tab — opt-in attachment of the bundled JVMTI agent to any Java run configuration; configurable per-run dump file (`default.jit`) that auto-opens in Hexana when the run completes.
- WASM proposals used by a binary now are displayed in the information bar, and corresponding arguments are passed to the runtimes to enable them for run configurations
- Experimental support for [ELF, Mach-O, PE](https://github.com/JetBrains/hexana/issues/90)
- Experimental support for llvm-objdump disassembly

### Changed
- Improved support for GraalVM for run configurations – now we support builds of GraalVM without the embedded wasm runner
- Fixed issues with debug not hitting the breakpoints when the information is encoded in the .debug_loc section in DWARF
- Fixed WASM debug backwards compatibility with IntelliJ IDEA 2025
- Improved MCP

## 0.9.1 - 2026-05-20

### Added

- Goto Symbol / Search Everywhere → Symbols now surfaces `.wit` declarations, component-model exports, and regular `.wasm` exports; picking a `.wasm` export opens the file and selects the matching row in the Exports tab

## 0.9 - 2026-05-07

### Added
- Experimental support for WASM debugging (requires llvm 22.1 or newer; works only with wasmtime and wamr; works only for targets debuggable with lldb)
- Added support for WAMR for running and debugging
- Added an option to specify a custom GraalVM home dir for running (previously only built-in Graal was used)
- UX: added an information bar showing file size (hover to see some stats), module kind and with run/debug buttons
- UX: improvements for the 'Top' tab: added headers, sorting, scrolling
- UX: when opening a nested module, you now see a backreference allowing to easily navigate back to the containing module
- Java: [RedHat/Chicory Java completion and inspections](https://github.com/JetBrains/hexana/issues/22)
- Java: [Oracle/GraalWasm Java completion and inspections](https://github.com/JetBrains/hexana/issues/20)
### Changed

### Deprecated

### Removed

### Fixed

### Security

## 0.8.2 - 2026-04-30

### Added
- [Support Legacy EH](https://github.com/JetBrains/hexana/issues/85)
- WAT/MCP rendering reference-types and bulk-memory instructions

### Fixed
- run configurations now properly work on Windows
- wasm parser fixes (vector, table)
- Element segment type 6 now reads the reference-type per WebAssembly 3.0 spec §5.5.12 (2026-04-24)
- Fixed data race on shared CommonByteBuffer, that cause sporadical UnParsedOpcodeExceptions.

## 0.8.1 - 2026-04-23

### Fixed

- Minor bug fixes and improvements

## 0.8 - 2026-04-21

### Added
- [Code-Size Profiler for WebAssembly Binaries](https://github.com/JetBrains/hexana/issues/70)
- [DWARF section detection and parsing](https://github.com/JetBrains/hexana/issues/49)
- [Explorer integration](https://github.com/JetBrains/hexana/issues/52)
- [Source mapping via DWARF](https://github.com/JetBrains/hexana/issues/48)
- [JS interop - code completion and type inference of instance.exports.](https://github.com/JetBrains/hexana/issues/18)
- [JS interop - code completion of namespaces and property names for imports](https://github.com/JetBrains/hexana/issues/17)
- [Run configurations for WASM - run with Wasmtime or GraalVM (only run is supported for now, no debug yet)](https://github.com/JetBrains/hexana/issues/69)

### Changed

- MCP tools descriptions optimized

### Fixed
- [IJPL-242167 IAE: WasmModuleKt.fromSections via WasmIndex](https://youtrack.jetbrains.com/issue/IJPL-242167)
- [ClassCastException in WitStaticFuncItemMixin](https://github.com/JetBrains/hexana/issues/84)

## 0.7.1 - 2026-04-09

### Fixed

- [IJPL-242167 IAE: WasmModuleKt.fromSections via WasmIndex](https://youtrack.jetbrains.com/issue/IJPL-242167)
- [Fixed handling of WASM/WAT files via http (local debug scenario)](https://github.com/JetBrains/hexana/issues/79)
- [Fixed unbalanced tree in WAT parsing](https://github.com/JetBrains/hexana/issues/78)
- [Fixed handling of empty ranges in WIT folding](https://github.com/JetBrains/hexana/issues/80)
- [Editor control in the WAT tab now properly handles IDE zoom](https://github.com/JetBrains/hexana/issues/81)
- [Text selection now works in both hex and text panels of the hex view, and also it's possible to use arrow keys](https://github.com/JetBrains/hexana/issues/82)
- [Big WAT files are now displayed in an editor with line numbers, text selection, search and scrolling](https://github.com/JetBrains/hexana/issues/83)

## 0.7 - 2026-03-31

### Added
- [WAT: offsets-based line numbers](https://github.com/JetBrains/hexana/issues/35)
- [Search in table view for imports/exports/functions (start typing to search)](https://github.com/JetBrains/hexana/issues/7)
### Changed
- WIT: added very basic editing experience (keyword completion, code formatting)
- Table UX improvements for imports/exports/functions: arrow keys for navigation, scrolling, layout fixes

### Fixed
- [Issues with KDoc when Hexana is enabled](https://github.com/JetBrains/hexana/issues/67)
- shared limit support for memory type was added.
- [NIE on reading go wasm module](https://github.com/JetBrains/hexana/issues/77)

## 0.6 - 2026-03-17

### Added
- [Parse Component Model binary format (component sections, instances, type definitions)](https://github.com/JetBrains/hexana/issues/64)
- [Display component structure in the explorer tree (imports, exports, interfaces, worlds)](https://github.com/JetBrains/hexana/issues/63)
- [Support for WIT definitions language (code model, go to definition, find usages, documentation on hover)](https://github.com/JetBrains/hexana/issues/9)
- [Navigation from WIT files to export definitions in WASM files (currently only exports are supported, more to come later)](https://github.com/JetBrains/hexana/issues/11)

### Fixed

- [[mcp] Several MCP functions incorrectly working with indices of MCP request](https://github.com/JetBrains/hexana/issues/66)
- [[mcp] importFunctions() must ignore non-function imports](https://github.com/JetBrains/hexana/issues/65)

## 0.5 - 2026-01-19

### Added

- basic WIT files highlighting
- UX improvement for table views
- table view for function and custom sections
- [Introduce Basic Search capability in Binary View](https://github.com/JetBrains/hexana/issues/3)

### Fixed

- [tag type import parsing](https://github.com/JetBrains/hexana/issues/4)
- [Non-Standard WASM parsing should not lead to failure](https://github.com/JetBrains/hexana/issues/1)

## 0.4 - 2025-12-15

### Added

- "Structure view" for WASM files
- Basic Text Selection for Hex View

### Fixed

- navigation from hex view to the data definition in WAT
- global usages in unfolded WAT syntax

## 0.3 - 2025-12-08

### Added

- `summarize_module` — Returns a compact numeric summary (functions, imports, exports, memories, tables, types, module size)
- `list_functions` — Paginated list of function metadata with offset/limit support
- `list_exported_functions` — All exported functions with resolved signatures (paginated)
- `list_imports` — All imported functions with module/name/signature
- `functions_for_indices` — Detailed function records for specific funcidx values
- `get_instructions_for_functions` — Full decoded instruction stream for selected functions
- `get_locals_for_functions` — Local variable declarations for selected functions
- `list_types` — All type definitions (func, struct, array, rec)
- `get_types_for_indices` — Selective type retrieval by typeidx
- `list_memory` — All linear memory declarations with limits
- `get_memory_for_indices` — Selective memory retrieval by memidx
- `list_globals` — All global variables with type/mutability/init expression
- `get_globals_for_indices` — Selective global retrieval by globalidx
- `list_data_segments` — Data segments with metadata, pagination, filtering (recommended for large modules)
- `list_data` — Raw base64-encoded data segment payloads (use with caution on large modules)
- `list_element_segments` — All element segments (table initializers, funcref arrays)
- `list_exports` — All exports (functions, memories, tables, globals) with kind/index
- UTF-8 text preview
- navigation from function definition in wat to hex view 
- unfolded syntax support
- navigation from hex view to the function and data definition

## 0.2 - 2025-12-03

### Added

- Wasm data section navigation support
- Basic WAT convertation (for small files) and navigation
- Show list of export and import names
- Copy highlighted data section as ASCII ot kotlin listOf<UByte>
- ability to open any file in hex view
- action navigating to the given offset
