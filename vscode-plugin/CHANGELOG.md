# 0.14.0 - 27.10.2026
- **replay HTTP requests from a PCAP**: right-click an HTTP request in a capture's "Packets" tab and choose "Replay HTTP Request…" to edit the method, URL, headers and body, send it again, and see the response below; the dialog stays open for the next attempt. Cleartext requests are reassembled across TCP segments; HTTPS requests work once an SSL key log decrypts them. Redirects are reported rather than followed, and connection headers and `Content-Length` are recomputed
- "Open Request as .http File" (in the menu and the replay dialog) writes the request to an `.http` file for the **REST Client** extension, with a binary body in a file next to it read with `<`; without REST Client installed the file opens as text and the extension is offered
- **Run** button for on-disk `.apk` files when a local Android SDK has the emulator (as installed by Android Studio): reuses a running emulator or boots an existing virtual device (pick one if there are several), installs the APK with `adb` and launches it. Without a virtual device it points you to Android Studio's Device Manager
- **Classes** tab for `.apk` files listing every class across the archive's DEX files (`classes.dex`, `classes2.dex`, …), with a filter per DEX file. The DEX files are read only once the tab is opened, in the background with a progress indicator. Right-click a class and choose **Reveal in DEX** to open its DEX file with the class selected on its **Classes** tab; an archive entry that is already open is brought forward instead of opened again
- **.NET assemblies**: PE files with a CLR runtime header (`.dll` / `.exe` built for .NET or .NET Framework) are labelled `PE32 (.NET)` and get managed-code tabs: ".NET" (runtime header flags, platform target, entry point, target framework, ReadyToRun, assembly identity), "Types" (expand a type for its fields and methods with C#-style signatures), "Methods" (selecting one highlights its IL body) and "Assembly Refs", plus a ".NET Metadata" tab with the metadata streams, tables, type and member references, user strings and embedded resources
- **IL Disassembly** tab for .NET assemblies: an ILDasm-style listing with one foldable section per type; fields and methods with `.maxstack`, `.locals`, instructions and `.try` clauses, with call targets, fields, types and string literals resolved to names. Types are decoded only when expanded; search finds types and methods, and clicking an instruction's offset selects its bytes
- **Top** tab for .NET assemblies breaks every section down by what owns its bytes: ReadyToRun native code per method (including generic instantiations, and code compiled in from other assemblies grouped by assembly), ReadyToRun tables, IL method bodies, metadata streams and tables, managed resources and PE data directories, with padding and anything left over shown separately
- **.NET `.resources` files** open in Hexana with "Header" and "Resources" tabs: every entry with its type and decoded value (strings, numbers, dates, byte arrays and streams; serialized objects show their type and size). Resource sets embedded in an assembly expand to their entries in its ".NET Metadata → Resources" tab
- **PDB debug symbols** open in Hexana, recognized by content (a Protein Data Bank `.pdb` text file stays a text file; a `.pdb` inside a NuGet package opens too). Windows PDBs get "PDB", "Streams", "Modules", "Source Files" and "Section Contributions" tabs; .NET Portable PDBs get "Portable PDB", "Documents", "Streams" and "Tables"
- **Source lines from PDBs**: a matching PDB next to a PE image (same GUID and age for a Windows PDB, GUID and timestamp for a Portable PDB) is loaded, and a "Debug Info" tab summarizes it. For .NET assemblies a matching Portable PDB — or one embedded with `-debug:embedded` — adds `.line` directives to "IL Disassembly" and a "Source" column to "Methods"; "Debug Context" says which PDB was loaded, or why the one found was not

# 0.13.0 - 01.10.2026
- **hex diff**: comparisons get a second view next to the size and functions diff (renamed from "Hexana diff" — both views are Hexana's), switchable at the top of the diff — the two binaries side by side in hex, changed bytes highlighted (amber modified, red removed, green added), long unchanged stretches collapsed into expandable "N unchanged rows" lines, previous / next difference buttons, sections tinted in colors that never clash with the diff's own, one section bar for both sides marking which sections changed (click to jump), and the files' table tabs (Headers, Sections, Symbols, Imports, …) diffed row by row below. Works for any pair; a pair of different kinds gets the byte comparison alone
- VS Code's own "Compare Selected" on two binaries Hexana opens now shows Hexana's diff, opened on the hex diff; "Hexana: Compare Files" opens it on the size and functions diff, as before
- "Compare WASM" and "Compare Binary" are merged into one "Hexana: Compare Files" command that picks the comparison from the files' contents; keybindings bound to the old commands keep working
- comparing two ELF, Mach-O or COFF files adds a "Functions" tab: functions are paired by name (renames recognized by identical code), and selecting one shows the two versions' bytes side by side. PE images and archives keep the section size comparison only
- "Compare WASM" gains the "Dispatch" tab (per-signature `call_indirect` candidates that entered or left the table) and Component Model comparison: submodules are paired, and clicking one opens its own comparison with a "Back" link
- comparison tables are searchable and sortable, and every tab loads in the background, so a large binary no longer freezes the view while it is compared

# 0.12.0 - 23.09.2026
- "Hexana Binary Editor" is now offered for **any** file under "Reopen Editor With…", so a binary with an extension Hexana does not claim (an Arrow stream written as `.stream`, a firmware image as `.dat`) can be opened in it on request; files Hexana does claim keep opening in it automatically
- support for Apache Parquet and Apache Arrow IPC files: Schema, Row Groups / Record Batches, Metadata and Data tabs, the same ones the IntelliJ plugin shows
- support for `.tar` archives, and for gzipped ones (`.tar.gz`, `.tgz`) — entries are listed and can be opened in their own tab, including entries that are themselves gzipped
- support for opaque Protobuf wire messages (`.pb`) decoded without a schema
- Arrow IPC *streams* open as streams (conventionally `.arrows`); previously every `.arrow` file was read as an IPC file, so a stream showed no tabs
- binaries whose name says nothing about their format open by content: an extensionless WebAssembly module, Parquet or Arrow export gets its full editor instead of a hex dump

# 0.11.0 - 16.09.2026
- `.debug_abbrev`, `.debug_info` and `.debug_line` grouped under a single "DWARF" tab with search; clicking a row selects its bytes
- WASM component "Dependencies" graph is navigable: double-click a module or nested component to open it, an import/export to select its bytes
- custom script tabs: format-agnostic `binary` API (`format`, `size`, `path`, `dir`, `structure`, `strings`, `sizeAnalysis`, `binary.tabs`/`binary.tab(name)`) plus `table(...)`/`row(...)` output. `open(path)` parses another file into the same members as `binary`, so a script can compare two files — the extension host reads it and the script resumes with it, so call `open` at the top level of the script rather than inside a function
- PCAP: support for TLS decryption based on a user-provided SSL key log file
- "Smali" tab for Android `.dex` files showing baksmali-style Dalvik bytecode with resolved operands; clicking a row selects the code unit in the hex view
- basic support for viewing Android binary resource tables (`resources.arsc`)
- runtime compatibility check for wasm binaries: declare target runtimes via "Hexana: Configure Target Runtimes" and see unsupported proposals in the editor header and a new "Compatibility" tab; shared with the IntelliJ plugin via `.hexana/runtimes.json`

# 0.10.0 - 26.08.2026
- fixed the "Debug Info" tab resolving zero source line rows for GCC-built ELF binaries (Zephyr and similar firmware, DWARF4 and DWARF5 alike): GCC emits DWARF v2/v3 line-program headers even in -gdwarf-4/5 builds and the line parser rejected them; they now parse and address-to-line resolution matches addr2line
- wasmtime debugging switched to the built-in gdbstub (wasmtime 44+): the module runs under `wasmtime run -g` and LLDB attaches through the wasm process plugin, the same model the IntelliJ plugin uses; DWARF is read directly from the wasm binary, removing the JIT-loader and trap-advance workarounds of the native path; when the resolved wasmtime is older, the extension also checks wasmtime's own installer location (`~/.wasmtime/bin`), and only then falls back to native debugging with a visible warning
- source-level stepping in wasm debug sessions (nodejs and browser runtimes): a step now repeats V8's per-instruction wasm step until the DWARF source line or stack depth changes, instead of pausing on every instruction of the same line; frames without DWARF info keep single-instruction steps
- wasm disassembly in VS Code's built-in Disassembly View ("Open Disassembly View" while paused in a nodejs/browser wasm debug session): one WAT-style instruction per row with module-absolute byte offsets as addresses and interleaved DWARF source locations; while the view is focused, stepping switches to per-instruction granularity, like the IntelliJ Wasm tab
- "Wasm" debugger view ("Hexana: Open Wasm View", in the bottom panel next to the Debug Console) mirroring the IntelliJ Wasm tab: WAT-style disassembly of the paused function with the current instruction highlighted on the left, V8's operand stack (slot, type, value) on the right; while the view is visible each step moves one wasm instruction, otherwise stepping stays source-line based; nodejs/browser wasm sessions only (compiled runtimes like wasmtime have no operand stack)
- the same view works for wasmtime and WAMR debug sessions ("Hexana: Open WebAssembly Disassembly"): under the gdbstub (wasmtime 44+, WAMR) the paused wasm instruction is highlighted exactly from the frame's program counter, and while the view is visible each step moves one wasm instruction, matching the Node.js behavior; the legacy native wasmtime path degrades to line granularity via the DWARF line table
- the operand stack works for wasmtime and WAMR too: slot types and origins come from static reconstruction of the paused function (exact by wasm validation), and concrete values are read from the debug stub ("value (origin)" rows) — via the wasmtime 44+ gdbstub stack accessor, or for WAMR via its locals/globals packets for slots that provably mirror a local or global
- the "Wasm" debugger view also works for component-model binaries: disassembly, the current-instruction highlight and the operand stack resolve against the embedded core module the debug stub reports, instead of assuming a single core module
- managed wasi-sdk downloads: "Hexana: Download wasi-sdk..." lists releases from GitHub (sizes shown, sha256-verified, cancellable) and installs versions side by side into the extension's global storage, so one download serves all workspaces; "Hexana: wasi-sdk Status" shows the active version with update and version-switch actions; the SDK takes effect only through an explicit "Use as LLDB path" button that writes the ordinary hexana.lldbPath setting (lldb resolution order unchanged)
- "Problems" tab for WASM binaries listing regions the parser could not decode (unknown opcodes, unsupported proposal constructs) with location, offset and message; clicking a row jumps to the function in the WAT tab, rows without a function select the bytes in the hex view
- "Problems" tab for WASM Component binaries: core-instance entries the parser cannot decode (unknown encoding tag, corrupt entry) are listed with offset and message instead of breaking the component dependency graph, which now labels the affected instances
- parsing support for the custom-descriptors proposal type encodings (exact reference types, descriptor/describes clauses); WasmGC modules using them previously failed to parse
- function bodies with a single undecodable instruction now keep everything decoded up to that point instead of turning into one unparsed blob
- basic support for pcap/pcapng files – viewing packets, opening http response body in a new tab
- support for viewing tar entries directly in Hexana
- support for gzip - compressed files of supported kinds (like .wasm.gz) can be opened directly in Hexana
- conditional breakpoints in wasm debug sessions (nodejs runtime); conditions are evaluated by V8, expressions referencing wasm-level names work directly
- inlined call frames in the debug stack trace, reconstructed from DWARF and marked "(inlined)"; variables and evaluation stay on the physical frame
- variables stored in linear memory (structs, arrays, enums; typical for unoptimized builds) are located via DWARF, read from the module's memory and decoded per type in the Variables view when debugging with nodejs
- fixed the wrong file opening on pause or breakpoints when several crates share a source file name; DWARF paths now match by longest path suffix instead of base name

# 0.9.0 - 05.08.2026
- size diagram ("Top" tab) for wasm and native binaries that shows largest by size entities
- optimized performance of large table views
- nested components of a WASM Component open in their own view from the new "Components" tab, like embedded core modules; opening works recursively for components inside components

# 0.8.0 - 29.07.2026
- semantic highlighting of sections and navigation between sections for WASM and Native binary files
- WASM Component dependency diagram showing dependencies within a WASM Component ("Dependencies" tab, components only)
- "Save as" for nested WASM modules and JAR/ZIP entries, which allows saving the content to a separate file

# 0.7.0 - 22.07.2026
- added an action to compare two WASM files showing details about size changes, and for functions - a diff in their WAT bodies
- added an action to compare two navite binary files showing details about size changes per section
- fixed behavior with archive files and nested WASM modules after reopening/restarting - now their content is properly restored
- data inspector panel showing different representations of the selected byte

# 0.6.1 - 15.07.2026

- component-model binaries now use the inline virtualized WAT view for embedded core modules, with a selector when a component contains multiple modules
- improved performance of WASM table tabs, especially the Functions tab on large modules
- updated the bundled MCP server packaging to the split CLI release-product layout

# 0.6.0 - 08.07.2026

- support for viewing compiled Protocol Buffers descriptor sets (.desc / .protoset), with Files/Messages/Fields/Enums/Services tabs
- support for wazero in WASM run configurations (running only modules, not individual functions)
- support for showing custom scriptable info tabs – write a JS script that uses 'print' to show information and 'binary' to access binary data; use "+" to add a script tab – a dialog with instructions and an API reference will be shown; csv and tsv are shown as tables
- improved experience with table views – now all tables use the same table control and have proper built-in search
- improved performance of table view rendering
- added a button to ease sharing your feedback and reporting issues
- MCP server setup now uses JetBrains downloads index native artifacts by default on supported platforms and no longer silently switches to GitHub when downloads metadata is unavailable

# 0.5.0 - 24.06.2026

- virtualized WAT (WebAssembly Text) view, providing faster navigation between sections
- support for viewing Java classes and DEX files
- ZIP64 files support
- stability improvements for debugging with NodeJS

# 0.4.0 - 17.06.2026

- hex editor - allow editing of individual bytes (without changing the file size)
- support for WASM debugging with NodeJS/Chrome

# 0.3.0 - 11.06.2026

- added NodeJS and browser as a wasm runtime in run configurations (no debug yet)
- improved debugging on Windows (better breakpoint detection and overall stability)
- added support for JAR/ZIP files

# 0.2.1 - 28.05.2026

- fix mcp downloading archive problem

# 0.2.0 - 27.05.2026

- support for ELF/Mach-O/PE binary formats
- improved logic for automatic discovery of lldp-dap executable on Linux
- mcp improved

# 0.1.0 - 20.05.2026

- supported WAMR and GraalVM for running wasm modules (in addition to wasmtime)
- experimental debugging support using wasmtime or WAMR (requires llvm 22.1 or newer; works only for targets debuggable with lldb)
- added MCP server

# 0.0.2 - 07.05.2026

- fixed minor vsix packaging issues
- supported publication to OpenVSIX

# 0.0.1 - 06.05.2026

- hex viewer
- information about WASM files (core modules and components)
  - imports / exports / modules / functions / top usage
  - ability to open submodules in a new tab
- running wasm modules and individual exported functions using wasmtime
