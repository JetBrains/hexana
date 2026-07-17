# Changelog

## Unreleased

### Added
- Action to show a diff between two native binary files with size changes per section

### Changed
- Hexana's IntelliJ MCP toolset now uses schema version 2 and advertises/enforces bounded list inputs such as `indices.maxItems`, allowing MCP clients to validate oversized requests before invoking tools.

### Deprecated

### Removed

### Fixed
- Fixed a UX problem with vertical splitter – now it's draggable when the pointer is over its upper half too
- WASM Diff action now properly works when two files are selected in the project view (previously it was always asking for the second file)

### Security

## 0.13 - 2026-07-08

### Added
- Open Apache Parquet and Apache Arrow IPC files for inspection. Both are recognised by content magic (`PAR1` for Parquet, `ARROW1` for the Arrow IPC file format, the `0xFFFFFFFF` continuation marker for the Arrow IPC stream format) rather than by extension. A Parquet file opens to **Schema**, **Row Groups** (each expanding to its column chunks with path, type, codec, and value count), and **Metadata** tabs; an Arrow file opens to **Schema**, a **Record Batches** or **Messages** tab depending on format, and **Metadata**.
- Parquet and Arrow files gain a **Data** tab that decodes the actual contents into a table (one column per schema leaf field, one row per record) and links the table to the hex view: selecting a row highlights that record's bytes in the hex editor as disjoint spans, at the best granularity each value allows (an exact per-value range for uncompressed PLAIN data, a page or chunk box for compressed or encoded data). The first row group (Parquet) or first record batch (Arrow) is decoded, capped at 10,000 rows, with a note when more rows or batches exist. Decoding covers page compression (SNAPPY, GZIP, ZSTD, and LZ4 on the JVM), the Parquet encodings (PLAIN, dictionary, RLE/bit-packed, DELTA variants, BYTE_STREAM_SPLIT) with Dremel definition levels for nullable columns, and Arrow buffer reconstruction for primitive, boolean, string/binary, and the temporal, interval, and decimal types. A file that is recognised as Parquet or Arrow but decodes to no rows (empty or malformed) still shows the Data tab with an explanatory empty state instead of hiding it.
- Parquet INT96 values (legacy 12-byte timestamps) are decoded and shown as their signed 96-bit integer in base-10, matching the BigInteger representation other Parquet tooling exposes.
- Open compiled Protocol Buffers descriptor sets (`.desc` / `.protoset`, produced by `protoc --descriptor_set_out`) for inspection. They are recognised by content structure (a leading length-delimited field whose embedded `FileDescriptorProto` name ends in `.proto`) as well as by the `.desc` / `.protoset` extensions. The file opens to **Files**, **Messages**, **Fields**, **Enums**, and **Services** tabs. Messages and enums are flattened with dotted qualified names (`Outer.Inner`), fields show their number, label, type, and any message or enum type reference, and services list each method with its input and output types and streaming flags. Raw schema-less wire messages are not covered by this auto-detection (a bare `.pb` message carries no field names or types and cannot be detected reliably); use the **Open As Protobuf Message** action below for those.
- **Open As Protobuf Message** (project-view context menu) decodes any file as a raw Protocol Buffers wire message, best-effort and without a schema. Because a bare wire message has no magic bytes, this is an explicit action rather than auto-detection. The decoder walks the wire format and shows one row per field with its positional path, field number, guessed type, value, and byte range. Length-delimited fields are disambiguated (a payload that parses cleanly as a nested message is expanded into indented child rows; otherwise it is shown as a UTF-8 string when printable, or a hex byte preview). Varint, 64-bit, and 32-bit fields are rendered with their numeric and floating-point interpretations. Selecting a row highlights that field's bytes in the hex view. Decoding is fault-tolerant: malformed or non-protobuf input degrades to a marker row instead of failing, so the action is safe to try on any file. Field names and declared types are not recovered (they are not present in a schema-less message).
- Support for wazero in WASM run configurations (running only modules, not individual functions)
- Support for showing custom scriptable info tabs – write a JS script that uses 'print' to show information and 'binary' to access binary data; use "+" to add a script tab – a dialog with instructions and an API reference will be shown; csv and tsv are shown as tables
- Added a Structure view for WASM modules, components, and for ELF files

### Changed
- Improved experience with table views – now all tables use the same table control and have proper built-in search
- Improved performance of table view rendering

## 0.12.1 - 2026-07-01

### Changed
- Jar view – an option to view all files and not only classes
- Automatic editor association for java class files without .class extension
- If there are more information tabs than fits the view, now the list of tabs is scrollable
- Tables showing information for native binary formats now have a correct layout (previously the rendering was broken)

## 0.12 - 2026-06-24

### Added
- Static-library archives open for inspection: `.a` (Unix `ar`, with ELF or Mach-O members) and `.lib` (Windows COFF import libraries). The archive opens to a **Members** list showing each object file's name, its detected format (ELF / Mach-O / PE / COFF / short-import), and size; the archive's own bookkeeping entries (symbol tables, the long-names table) are hidden. Archives are recognised by their `!<arch>\n` content magic rather than by file extension, so a non-archive `.lib` (a plain MSVC import library) is left to its normal handler. Clicking a member extracts that object file to the IDE temp folder and opens it in the matching disassembly view — and because the extracted member is a real file on disk, both the Capstone and the llvm-objdump disassembly panels are available. The member's editor tab is titled `archive-name/member-name`, with the member index appended when two members share the same name
- Object-file members of a static-library archive disassemble per function in the virtualized (Capstone) view. Relocatable objects — ELF (`.o`), Windows COFF (`.obj`/`.o`), and Mach-O — lay out their function symbols section-relative rather than at virtual addresses, so their file offsets are now resolved through the owning section; the disassembly lists each named function instead of only the whole `.text` section. Symbol-stripped members still fall back to disassembling the code sections
- Compare two `.wasm` modules: **Compare WASM With…** (project-view context menu or Tools menu) opens a diff editor. The **Size Impact** tab shows per-section and per-function byte deltas sorted by impact; the **Entities** tab classifies every entity kind (imports, exports, functions, globals, tables, memories, types, data and element segments) as added / removed / modified / moved, with a supply-chain banner that highlights newly-introduced imports (host namespaces such as `env` / `wasi_*` are flagged). Functions are matched structurally (content hash + call graph), so renaming or renumbering does not show up as spurious churn. Selecting a function opens an on-demand side-by-side WAT comparison rendered with symbolic names — so an unchanged caller of a renumbered function reads identically — with standard red/green line-level highlighting. Table rows are colour-coded by change kind, with aligned columns
- The **Size Impact** tab now pairs functions with the same semantic matcher as the Entities tab instead of by position, so inserting or removing a function no longer mis-attributes byte deltas to unrelated functions. Each function row shows the match classification (identical / moved / modified) and, for non-exact matches, the confidence; a pure renumber is reported as *moved* (zero bytes) rather than a spurious size change
- The diff detects minified import/export names (for example from Binaryen's `--minify-imports-and-exports`) and shows a banner noting that matches are structural. Dropping a symbol-map sidecar — `<file>.symbols` next to the `.wasm`, as emitted by `--emit-symbol-map` — restores the original names so functions match by name again; the banner then reports that names were restored. Both map shapes are read: function-index (`index:name`) and import/export (`minified:original`, including Binaryen's `original => minified` console form)
- Cross-module function matching is more robust to optimization: a second hashing round substitutes already-matched call targets, so a rewritten body that still calls the same functions is paired, and calls to imported functions now help discriminate matches
- Edit mode UX improvements: highlighting of changes, option to overwrite the file
- Kotlin/Wasm modules are detected by their `kotlin.wasm.internal.*` import fingerprint and tagged with a "Kotlin/Wasm" badge on the Functions tab and the virtualized WAT view. Clicking a function's badge navigates to its Kotlin source declaration, resolved from the plain fully-qualified names the Kotlin/Wasm backend writes into the name section (constructors, property accessors, and `$default` / `$lambda` synthetics are handled; overloads that share a name resolve to the method declaration). Detection keys on the import field-name prefix rather than the host module, so it works across Kotlin versions (current `js_code`, older `env`) and does not misfire on GraalVM Web Image or Emscripten, which also emit WasmGC
- **Compare WASM With…** now accepts Component Model binaries, not only core modules. Comparing two components opens a submodule view that lists each component's embedded submodules (core modules and nested components, found recursively), flags which differ (identical / modified / added / removed), and on click opens a dedicated tab comparing that pair: a core-module pair reuses the existing Size Impact / Entities / WAT diff, and a nested-component pair recurses into another component diff. Submodules are paired by content hash with a positional fallback
- Component Model binaries gain a **WAT (virtualized)** tab. A component's code lives in its embedded core modules, so the tab discovers every embedded core module — recursively, through nested sub-components — and renders the selected one with the same structured, lazily-parsed virtualized WAT view that core modules use (three-column offset / bytes / mnemonic, per-function navigation, folding); a selector switches between modules when there is more than one. This also gives in-memory component views (for example a submodule opened from a diff) a WAT view they previously lacked
- The **Top** size-profiler tab now breaks a Component Model binary down completely: every nested sub-component and embedded core module is attributed with its correct size and expands to its internal sections and functions, recursively and on demand. Previously a composed component — whose code sits inside nested sub-components — reported most of its bytes as a single opaque block, because the profiler read only the first nested component section
- The core-module **Exports** tab gains per-row affordances. Each entry shows its full name as a tooltip, so a name truncated to fit the column is still readable on hover. A **Copy Name** context-menu action copies the export's full name to the clipboard. A **Navigate to Function in WAT** context-menu action — shown only on function exports, which are the only kind with a body to jump to — switches to the **WAT (virtualized)** tab and scrolls to that function's body
- The core-module **Imports** tab gains matching per-row affordances. Each entry shows its module-qualified name (`module.field`) as a tooltip, and a **Copy Name** context-menu action copies that qualified name to the clipboard. For function imports, the context menu also offers **Find Usages**: a flat list of the functions that call the import (resolved from the call graph), each entry navigating to that caller's body in the **WAT (virtualized)** tab. The list is capped at 15 entries; beyond that — or before the call graph has finished computing in the background — a single entry opens the Caller Paths panel, which lists every usage. The call graph is built off the EDT so opening the menu stays responsive on large modules
- Basic support for Android DEX files (with classes and members)
- ZIP64 files support

### Changed
- Hexana's project-view actions (Hex view, Open Archive, **Compare WASM With…**, and the in-editor Hex/offset/structure actions) are now grouped under a single **Hexana** submenu instead of being scattered across the right-click menu. In IntelliJ IDEA the submenu sits at the top level of the project-view popup; in RustRover and CLion — whose project-view popup is overridden to a fixed action list that silently drops third-party top-level contributions — it appears under **Open In ▸ Hexana**, the one group those IDEs still surface. Each entry continues to gate its own visibility, so only the actions that apply to the selected file appear
- **Compare WASM With…** can now be invoked from Search Everywhere and the keymap, not only from a selected `.wasm`: when run without a `.wasm` selection it prompts for the first module, then the second, instead of doing nothing
- Stability improvements for debugging with NodeJS

### Fixed
- Size-profiler (**Top**) tree nodes expand again. Nodes whose children are generated lazily — now any nested component / embedded core module, and a core module's sections expanding into their functions — silently showed nothing when expanded: the lazy children-generator was gated by the tree-build coroutine's cancellation flag, which is already cancelled by the time a node is expanded, so it returned no children. The generator no longer inherits that flag
- The virtualized WAT view no longer throws `IllegalArgumentException: Key … was already used` (and freezes) when scrolling or navigating a module whose name section repeats a function name. Kotlin/Wasm emits the same name (`<init>`, property accessors, `$default`, overloads) at many function indices; per-function chunk identity now disambiguates such collisions. This also fixes navigation that could land on the wrong same-named function, and fold state that collapsed across same-named functions. Custom-section headers with duplicate names are disambiguated the same way

## 0.11.1 - 2026-06-17

### Added
- Compare two `.wasm` modules: **Compare WASM With…** (project-view context menu or Tools menu) opens a diff editor. The **Size Impact** tab shows per-section and per-function byte deltas sorted by impact; the **Entities** tab classifies every entity kind (imports, exports, functions, globals, tables, memories, types, data and element segments) as added / removed / modified / moved, with a supply-chain banner that highlights newly-introduced imports (host namespaces such as `env` / `wasi_*` are flagged). Functions are matched structurally (content hash + call graph), so renaming or renumbering does not show up as spurious churn. Selecting a function opens an on-demand side-by-side WAT comparison rendered with symbolic names — so an unchanged caller of a renumbered function reads identically — with standard red/green line-level highlighting. Table rows are colour-coded by change kind, with aligned columns
- The **Size Impact** tab now pairs functions with the same semantic matcher as the Entities tab instead of by position, so inserting or removing a function no longer mis-attributes byte deltas to unrelated functions. Each function row shows the match classification (identical / moved / modified) and, for non-exact matches, the confidence; a pure renumber is reported as *moved* (zero bytes) rather than a spurious size change
- The diff detects minified import/export names (for example from Binaryen's `--minify-imports-and-exports`) and shows a banner noting that matches are structural. Dropping a symbol-map sidecar — `<file>.symbols` next to the `.wasm`, as emitted by `--emit-symbol-map` — restores the original names so functions match by name again; the banner then reports that names were restored. Both map shapes are read: function-index (`index:name`) and import/export (`minified:original`, including Binaryen's `original => minified` console form)
- Cross-module function matching is more robust to optimization: a second hashing round substitutes already-matched call targets, so a rewritten body that still calls the same functions is paired, and calls to imported functions now help discriminate matches
- Edit mode UX improvements: highlighting of changes, option to overwrite the file
- Kotlin/Wasm modules are detected by their `kotlin.wasm.internal.*` import fingerprint and tagged with a "Kotlin/Wasm" badge on the Functions tab and the virtualized WAT view. Clicking a function's badge navigates to its Kotlin source declaration, resolved from the plain fully-qualified names the Kotlin/Wasm backend writes into the name section (constructors, property accessors, and `$default` / `$lambda` synthetics are handled; overloads that share a name resolve to the method declaration). Detection keys on the import field-name prefix rather than the host module, so it works across Kotlin versions (current `js_code`, older `env`) and does not misfire on GraalVM Web Image or Emscripten, which also emit WasmGC

### Fixed
- The virtualized WAT view no longer throws `IllegalArgumentException: Key … was already used` (and freezes) when scrolling or navigating a module whose name section repeats a function name. Kotlin/Wasm emits the same name (`<init>`, property accessors, `$default`, overloads) at many function indices; per-function chunk identity now disambiguates such collisions. This also fixes navigation that could land on the wrong same-named function, and fold state that collapsed across same-named functions. Custom-section headers with duplicate names are disambiguated the same way

## 0.11 - 2026-06-11

### Added
- NodeJS and Browser (for debug - Chrome only) are supported as wasm runtimes for run/debug wasm configurations
- GraalVM Native Image: native executables and shared libraries produced by `native-image` (ELF, Mach-O, PE) are detected by their SubstrateVM fingerprint — the `com.oracle.svm.core.VM=GraalVM …` identity string, `.svm_*`/`__svm*` sections, and the `__svm_version_info` marker — and tagged with a "Native Image" badge (tooltip shows version, edition, and target platform). Detection survives release stripping because the identity string lives in read-only data
- GraalVM Native Image SBOM: binaries built with `--enable-sbom` (default-on in Oracle GraalVM for JDK 25+) get a "SBOM" badge and a dedicated SBOM tab that renders the embedded CycloneDX bill of materials — a metadata header (subject, timestamp, generating tool) plus a searchable, sortable table of components (type, group, name, version, licenses, PURL), with a "View raw JSON" action for the decompressed document
- SBOM vulnerability reachability (GraalVM Native Image only): the SBOM tab can match components against the OSV vulnerability database and overlay known CVEs with CVSS severity, the fixed version, and an advisory link. Because native-image's dead-code elimination prunes the image, a vulnerable component shown here was actually retained; binaries built with `--enable-sbom=class-level` add a compiled-in class/method tree and refine reachability to "vulnerable class retained" vs "eliminated by DCE". Matching is offline by default — the database is downloaded once and the component list never leaves your machine — with an opt-in setting to also query osv.dev online. Off until enabled in Settings → Tools → Hexana

### Changed
- WASM debugging – better handling of breakpoints on Windows (if it didn't attach for you previously it should now)

## 0.10.3 - 2026-06-10

### Fixed

- Problems with statistics

## 0.8.3 - 2026-06-10

### Fixed

- Fixed the problem with the directory whose name is ended on .wasm
- Tabs UX improvements

## 0.10.2 - 2026-06-03

### Added
- GraalVM Web Image: `.wasm` modules compiled from Java via Web Image (`native-image --tool:svm-wasm`) are detected by their `interop`/`jsbody`/`compat` import fingerprint. Functions whose names demangle to a Java method get a "Java" badge — in the Functions tab and inline on each code-section entry in the WAT (virtualized) view — that opens the corresponding method's bytecode. Available when the module retained function names (built with debug names); stripped/minified and Graal-internal functions are left unbadged
- jitview: the `.jit` view splits into Combined / Bytecode / Machine Code tabs, each with multiline selection and copy (full text / bytecode / hex / instruction bytes), a per-tab Go-To-Offset, and a symbol-name header showing the current method; the Machine Code tab can copy offset + instruction bytes
- jitview: search compiled methods by inlined callee with `in:<method>` — surfaces every nmethod that inlined the given method, using the JVMTI inline records in the dump
- JIT Viewer: new "Instrument JMH forks" option attaches the dump agent to each forked JMH benchmark JVM via a bundled `-javaagent`, writing one `ClassName_method.jit` per benchmark; because it rides on JVM options (not JMH's `-prof`) it works for any JMH run type — the IDE gutter run, the Gradle JMH plugin, and `org.openjdk.jmh.Main`
- WAT (virtualized): custom sections now render as a foldable entry — an `offset hex name` header with the section's raw content below, shown in selectable `u8`/`u16`/`u32`/`u64`/`ascii`/`utf8` encodings (the same representation as Data entries)

### Changed
- WAT (virtualized): WasmGC `(rec …)` recursive type groups now render as a collapsible group (collapsed by default) instead of a flat list of types; reference-type targets show as `$typeN` (e.g. `(ref null $type4923)`)
- Binary editor: switching tabs now cancels the previous tab's in-flight off-thread analysis (for example the Top size tree) instead of letting it run to completion, so navigating away frees CPU and background threads for the tab you just opened

### Fixed
- Top (size analysis): switching to the Top tab no longer freezes the IDE on large binaries — the top-level size tree and data-segment parse ran inside composition on the EDT; they now build off-thread with a loading indicator
- Functions tab: the Name column and the unreachable `●` marker were keyed by the defined-function row index rather than the global function index, so for modules with function imports both were shifted by the import count; they now resolve the correct name and reachability for every row
- WAT (virtualized): opening a large WasmGC binary no longer hangs the IDE. The type section's single `(rec …)` group (tens of thousands of types) was rendered as one multi-megabyte row, freezing the UI thread in text layout; it now folds to a collapsible group with each type as a separate virtualized row, and the view's model is built off the UI thread
- Top (size analysis): restoring a Top tab on startup no longer freezes the IDE on large modules — the size tree's depth metrics were still computed during composition on the EDT even though the tree data was already built off-thread; they now compute off-thread together with the tree
- WAT: the WAT text conversion was rebuilt on every recomposition and its background coroutine was never cancelled when leaving the tab; it is now created once and cancelled on tab switch

## 0.10.1 - 2026-05-29

### Fixed
- Now we list entries for regular ZIP files instead of showing "no classes"
- Added a missing scrollbar to the classes/entries list for JAR/ZIP files
- Fixed problems with JIT binaries

## 0.10 - 2026-05-27

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
