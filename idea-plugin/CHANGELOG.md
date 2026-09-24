# Changelog

## Unreleased

### Added
- Arrow IPC **streams** open as streams. Previously every `.arrow` file was read as an IPC *file*, so a stream (conventionally `.arrows`) showed no Schema or Messages tabs; both extensions are now accepted and the format is resolved from the content.
- Binaries whose name says nothing about their format open by content: a WebAssembly module written without a `.wasm` extension gets the full Wasm editor instead of a raw hex view, and so does a Parquet or Arrow export a tool dumped extensionless.
- **Per-function native binary diff.** **Compare Binary With…** on two ELF, Mach-O or COFF files adds a **Functions** tab next to Size Impact: functions are paired by name, renames are recognized by identical code, and a function that only moved because the code before it grew is reported as moved rather than modified. Selecting a function shows its disassembly side by side, with shifted addresses ignored when lining up instructions. PE images (`.exe`, `.dll`) and archives keep the section size comparison only.

### Changed
- One diff editor for every format: WASM module, Component Model and native comparisons share the same tab strip, tables (now searchable and sortable) and side-by-side detail pane.
- **Compare Binary With…** on two `.wasm` files opens the WASM diff instead of an empty native one; **Compare WASM With…** on a file that is not WebAssembly says so instead of opening a native diff.
- The "Send anonymous usage analytics" setting takes effect immediately instead of at the next IDE restart. Turning it off flushes whatever was already captured under consent and then tears the client down; turning it on starts reporting straight away.
- Provider selection moved to one shared factory (`createBinaryProvider` in `binaryProvider`) that the IDE, the VS Code extension and the browser app all call. The two copies had drifted: Parquet, Arrow and `.pb` Protobuf-message files resolved only in the IDE, and tar archives only in the IDE once gzipped. Every host now supports every format the provider layer does.

### Deprecated

### Removed

### Fixed
- Opening a native binary with tens of thousands of symbols (e.g. an LLVM tool) no longer spends seconds inferring function sizes: the Mach-O and COFF "size = gap to the next symbol" rule was quadratic. Affects the Disassembly tabs and the diff.

### Security
- Opening a gzipped file no longer inflates it without a bound: a payload over 128 MiB inflated is refused and the file is shown as the compressed bytes it is, instead of a small archive being able to exhaust the IDE's heap.

## 0.20 - 2026-09-16

### Added
- **Breakpoints in binary views**, set without a debug session and materialized when one starts. Click the gutter in the **WAT (virtualized)** or native **Disassembly** view; the breakpoint is anchored to the binary's content hash (not its path) and survives restarts. The gutter icon reports what the runtime did with it: registered, instruction-precise, degraded, or stale after a rebuild. Supported for GraalWasm/Node.js/browser, wasmtime, WAMR and firmware ELF (QEMU/Renode) sessions. Listed under "Hexana Binary Breakpoints" in the Breakpoints dialog; conditions and hit counts are not supported yet.
- **Debugger Console** tab: an interactive command line to the underlying debugger — LLDB commands for wasmtime/WAMR/wasmtime-gdbstub sessions, GDB commands for firmware QEMU/Renode. Execution-control commands are routed through the IDE's own actions so the console cannot desync it. Not available for GraalWasm/CDP sessions.
- Custom script tabs: format-agnostic `binary` API (`format`, `size`, `path`, `dir`, `structure`, `strings`, `sizeAnalysis`, `binary.tabs`/`binary.tab(name)`) plus `table(...)`/`row(...)` output, and `open(path)` parses another file and returns the same members as `binary`
- Nodes in the WASM component **Dependencies** graph are navigable: double-click a core module or nested component to open it in its own tab, an import or export to select its bytes in the hex view
- PCAP: support for TLS decryption based on a user-provided SSL key log file
- **Smali** tab for Android `.dex` files showing baksmali-style Dalvik bytecode with resolved operands and decoded switch/fill-array payloads; clicking an instruction row moves the hex view to that code unit
- Basic support for viewing Android binary resource tables (`resources.arsc`)
- **Structural regions in the hex view for every format Hexana parses** — previously WebAssembly and ELF only. Region tinting, labels, prev/next navigation and the **Structure** tool window now also cover ZIP/JAR and TAR entries, `ar` members, COFF sections, DEX id tables, PCAP/PCAP-NG packets, Parquet row groups, Arrow batches, `resources.arsc` packages, class files and protobuf descriptors. Where a region matches a table the editor renders, its label switches to that tab.
- **Runtime compatibility for WebAssembly binaries.** Declare target runtimes under **Settings | Build, Execution, Deployment | WebAssembly | Runtime Compatibility** and every `.wasm` file is checked against them: the proposals badge turns into a warning for unsupported proposals and opens a new **Compatibility** tab. With nothing declared the tab lists which runtimes support each proposal. Verdicts come from WebAssembly's `features.json`; the declaration lives in `.hexana/runtimes.json`, shared with the VS Code extension.

### Changed
- The `.debug_abbrev`, `.debug_info` and `.debug_line` tabs are grouped under a single **DWARF** tab, and now searchable and scrollable; a binary carrying only one of them keeps its plain `.debug_*` tab

### Deprecated

### Removed

### Fixed
- The bundled `hexana-scripting-raw.d.ts` reference no longer lists Kotlin's generated `component1()`, `component2()`, ... destructuring accessors
- `binary.name` in a script tab is now the file name in every host. The VS Code extension passed the full filesystem path where the IntelliJ plugin and the `run-script` CLI passed a bare name, so one script reported two different things for the same file depending on where it ran

### Security
- Script tabs now run sandboxed. Up to and including 0.18 a script tab ran with full host access and could call arbitrary Java; it now uses the same sandbox as the `run-script` CLI and the MCP tool — no host classes, processes, threads or native access, plus a timeout and bounded output. The one way a script reaches the file system is `open(path)`, which parses a file into the same read-only `binary` API and nothing else; arbitrary file reads stay unavailable

## 0.19 - 2026-08-26

### Added
- **Run and Debug for firmware ELFs** under QEMU and Renode. Opening a Zephyr-class ELF offers Run/Debug buttons in the editor header when a target profile resolves from the ELF header plus the `zephyr.dts` and `.config` found next to it (the same build-tree discovery the Memory tab uses). QEMU boards (`qemu_cortex_m3`, `mps2/an521`, `qemu_riscv32`, `qemu_cortex_a53`, ...) launch with the exact machine flags from a curated table; MCU-class boards run under Renode, on a built-in platform where Renode ships one (nRF52840 DK, STM32F4 Discovery) or on a platform generated from the devicetree by dts2repl. The emulator choice is annotated with a recommendation and persists in the new **Hexana Firmware** run configuration; a combination the target cannot support fails at validation time naming the reason and the alternative, never as a hung emulator. Console output streams to the Run tool window behind a one-line emulation notice (unmodeled peripherals read zero, timing differs, radios are not simulated), and a startup watchdog reports "did not reach console output" naming the first unimplemented-peripheral access from the Renode log. Stop kills the whole emulator process tree; nothing is left behind on IDE exit. Debug attaches GDB in DAP mode (preferring the Zephyr SDK's `arm-zephyr-eabi-gdb-py`; minimum GDB 14 with Python) to the emulator's frozen gdb stub with a configurable stop-at-entry policy (entry, `main`, or none); an LLDB fallback covers machines without a suitable GDB. Emulator tooling is acquired on demand from the run configuration's Tools section with consent-gated, checksum-verified, cancellable downloads: the Renode 1.16.1 portable build, a per-architecture Zephyr SDK GDB toolchain, and a standalone CPython for dts2repl (all desktop platforms); each tool is also overridable by path in Settings | Build | Firmware Emulator. QEMU comes from the system package manager.
- **Memory** tab for ELF binaries: a section-level Flash/RAM budget view answering "how full is my device". One block per memory region with used/capacity in human units (exact bytes on hover) and a fill bar that turns yellow at 80% and red at 95%; ROM regions listed before RAM. Initialized data is annotated in both places it lives ("load image" in flash, "runtime copy" in RAM), so the totals stay explainable; `.bss` counts only toward RAM, and `noinit` sections are marked. Region capacities come from a linker map (`<name>.map`, `zephyr.map`, or any other `*.map` sibling that matches the ELF) or a Zephyr `zephyr.dts` found next to the ELF automatically, with a footer naming the source; a map whose section addresses do not match the ELF (stale build) is detected and ignored with a warning. Without any source the tab still shows the full ROM/RAM breakdown from the ELF's load segments, capacity-free, and a notification strip offers "Link capacity source..." (a `.map`, a `.dts`, or manual flash/RAM sizes; remembered per file). The budget measures address-space bytes, complementing the **Top** tab's file-byte profile: debug info that never reaches the device is excluded, and a zero-file-byte `.bss` heap arena shows up as the RAM consumer it is. Section rows navigate to the hex view. The tab appears for executable and shared-object ELFs, including fully stripped ones (no symbols or DWARF needed).
- The **Wasm** debugger tab now works for component-model binaries: every embedded core module is indexed on its own, so the current-instruction highlight, per-instruction stepping and operand stack follow the module the runtime reports the stop in instead of assuming a single core module.

### Fixed
- Cortex-M firmware ELFs (Zephyr, ZMK and similar) now disassemble as Thumb-2. The M profile recorded in `.ARM.attributes` selects the Thumb decoder for the whole file, and bit 0 of Thumb function symbol addresses (the interworking marker, not part of the real address) is stripped, so functions start at their even addresses and 32-bit wide encodings (`bl.w`, `mul.w`) decode exactly as objdump shows. Previously these files decoded as A32 from odd start addresses, producing garbage for every function. A-profile ELFs keep per-symbol mode selection: A32 by default, Thumb for symbols carrying the marker bit.
- The **Debug Info** tab reported "Debug info present, but Hexana did not resolve any source line rows" for GCC-built ELFs even though `.debug_info` and `.debug_line` were present (every Zephyr/ZMK Cortex-M firmware, DWARF4 and DWARF5 alike). GCC emits DWARF version 2/3 line-program headers even in `-gdwarf-4/5` builds, and the line parser misread them with the version 4 layout, failing on the first such unit and discarding the whole section (clang and rustc emit v4/v5 headers, which always worked). Version 2/3 headers now parse, an unparseable line-table unit is skipped instead of taking the rest of the section down with it, and address-to-line resolution matches addr2line.

## 0.18 - 2026-08-19

### Added
- Managed wasi-sdk installation. Wasm debugging needs an LLDB built with the wasm process plugin, and the wasi-sdk distribution ships one. The WebAssembly settings page now has a JDK-style selector that lists installed wasi-sdk versions and ends with a "Download wasi-sdk..." action opening a version chooser fed from the GitHub releases (download sizes shown, sha256 verified against the release digest, cancellable, versions installed side by side). The SDK lands under the IDE's common data directory, so one download serves every project and every JetBrains IDE on the machine. The LLDB selection mechanism is unchanged: the managed SDK takes effect only through an explicit "Use as LLDB path" action (in the settings row and in the install notification) that writes the ordinary LLDB path setting. When a debug session cannot find LLDB, a notification offers the download; when LLDB is found but no wasi-sdk is installed and no explicit LLDB path is set, a one-time notification suggests it.
- Per-instruction stepping in the **Wasm** debugger tab under wasmtime. While the tab is selected, Step Over and Step Into advance exactly one wasm instruction, including instructions the old native path could never stop on (`local.get`, `i32.const`); with any other tab selected, stepping stays at source-line granularity. This matches the behavior the tab already had under Node.js.
- The **Wasm** tab's operand stack shows live values under wasmtime. Each slot is read from the runtime at every stop (wasmtime's gdbstub serves the operand stack over the debug protocol; wasmtime 44+) and rendered next to its statically reconstructed origin, e.g. `7 (i32.add @0x7c)`. The 0.17 release could only show the origins, because the native debug path had no access to the values Cranelift compiles away. On runtimes that do not answer, the pane keeps the origin-only display.
- The **Wasm** debugger tab now also appears when debugging under WAMR. WAMR's gdbstub reports wasm byte offsets directly, so the current-instruction highlight and per-instruction stepping (Step Over and Step Into while the tab is selected) work the same way as under wasmtime's gdbstub. The operand stack shows live values for slots that mirror a local or a global, read over the debug protocol; WAMR's stub has no operand-stack accessor, so computed intermediates (arithmetic results, call returns) keep their statically reconstructed origin. Requires an iwasm built with `WAMR_BUILD_DEBUG_INTERP=1`: a stock iwasm (Homebrew, release tarballs) lacks the `-g` option, and the session fails early with rebuild instructions.
- Exact current-instruction highlight in the **Wasm** tab. Instructions without immediates (`i32.add`, `end`, `drop`, ...) have no recorded offsets in the parser model; their rows now get offsets deduced from neighboring instructions, and the function's closing `end`, a legitimate pause target with the return values on the stack, is rendered as a row. Previously the highlight snapped to the nearest earlier offset-bearing row, which per-instruction stepping made visibly wrong.

### Changed
- Wasmtime debugging switched to wasmtime's built-in gdbstub guest debugger: the session starts `wasmtime run -g <port>` and attaches LLDB over the gdb-remote protocol (`process connect --plugin wasm`). LLDB reads DWARF directly from the wasm module, so the JIT loader, the native-address-to-wasm-offset translation and the trap workarounds of the old native path are gone. The practical wins: arguments and locals show concrete values (Cranelift compiled them away before; the Variables view could only show storage-location hints), stepping is per wasm instruction, and the stack shows guest frames only. Requires wasmtime 44 or newer (older versions fail early with an update hint) and an LLDB with the wasm process plugin (wasi-sdk ships one, see the managed download above; recent LLVM builds include it as well).
- Improved support for Component Model in WAT files: parsing, reference resolution, navigation
- **Find Usages** for WAT declarations now works in the embedded WAT editor
- Custom WAT annotation sections such as `(@producers (processed-by "wit-component" "0.227.1") (language "Rust" ""))` no longer render as errors. Annotation contents are free-form by design, so an unrecognised word inside one is now annotation text rather than a bad character, and the whole section reads in the metadata colour. Unrecognised words outside an annotation are still flagged.

### Fixed
- Evaluating an expression in a wasmtime debug session no longer kills the LLDB process. LLDB's expression compiler cannot generate code for wasm targets and aborts on any expression that parses (an upstream LLVM defect, present in wasi-sdk and stock LLVM builds alike). Hexana now evaluates variable paths (`x`, `s.field`, `p->x`, `arr[0]`) through `frame variable`, which is unaffected, and rejects other expressions with an explanatory message instead of forwarding them.

## 0.17 - 2026-08-12

### Added
- **Problems** tab for WASM binaries. When the parser hits bytes it cannot decode (for example instructions from a not-yet-supported proposal), it no longer discards the whole function body: decoding keeps everything up to the failure and records the rest as one unparsed region, and every such region shows up as a row in the new tab with its kind, location, byte offset, size and message. Clicking a row jumps to the affected function in the **WAT (virtualized)** tab; the context menu also offers the plain **Wat** tab, which now scrolls to the nearest annotated line. Rows not tied to a function select the failed byte range in the hex view. The tab appears only when the module actually has parse problems.
- Type-section encodings from the custom-descriptors proposal (Phase 3): exact reference types (`(ref null (exact $t))`) and `descriptor` / `describes` clauses now parse with correct alignment. WasmGC modules emitted with these encodings (recent Kotlin/Wasm and J2CL toolchains) previously failed to parse at all: the type section silently misparsed from the first exact type onward and every later function body degraded to an unparsed blob or a NullPointerException.
- Basic support for pcap/pcapng files - viewing packets, opening http response body in a new tab
- Support for viewing tar entries directly in Hexana
- Support for gzip - compressed files of supported kinds (like .wasm.gz) can be opened directly in Hexana
- **Wasm** tab in the debugger when debugging a wasm module under Node.js or a browser. The left pane disassembles the paused function to WAT (same mnemonics and symbolic `call $name` operands as the WAT view, block bodies indented) with the current instruction selected and following every step; the right pane shows the live operand stack with typed entries. Stepping granularity follows the tab: while it is selected, one step is one wasm instruction; otherwise a step runs until the source line changes. V8 natively steps wasm one instruction at a time, so a single source line previously took several Step Over clicks to cross.
- Conditional breakpoints in wasm debug sessions. Condition expressions are translated from DWARF source variable names to V8's internal wasm-local names before registration, so conditions use the same names the Variables view shows.
- Inlined call frames in the debugger stack. When a pause lands inside code the compiler inlined, the stack shows synthetic frames suffixed `(inlined)`, reconstructed from DWARF: the innermost inlined function at the pause location, each caller at its recorded call site. Variables and evaluation stay attached to the physical frame; stepping remains physical.
- Variables stored in linear memory now appear when debugging under Node.js. V8 only surfaces wasm locals and globals, so anything on the shadow stack or at a fixed address (structs, arrays, enums; typical for parameters and locals in unoptimized builds) used to be invisible. They are now located via DWARF, read from the module's memory over the debugger protocol and decoded per their DWARF types: structs expand to members at their recorded offsets, enums render by enumerator name, arrays are bounded, reads are capped per variable.
- The **Wasm** debugger tab now also appears when debugging under wasmtime. The left pane disassembles the paused function with the current wasm instruction selected: wasmtime serializes a native-address-to-wasm-offset table into every compiled module, and the stopped pc is translated through it, so the highlight is per instruction even though LLDB only sees native code (instructions that emit no machine code, like `local.get` or `i32.const`, are never pause targets and are skipped, which mirrors what the CPU executes). The right pane shows a symbolic operand stack: Cranelift compiles the wasm stack machine away, so the concrete values are not recoverable from the process, but the stack's depth, types and origins are reconstructed statically per wasm's validation rules. Each slot names where its value came from: a local (with its DWARF source name when known), a constant (the one case with a real value), or the producing instruction (`i32.add @0x52`, `call make @0x341f`). When the shape cannot be reconstructed (unsupported instructions, SIMD/atomics in the function), the pane says so explicitly instead of showing a wrong stack.

### Fixed
- Opening the **Dependencies** tab of a Component Model binary crashed with `NotImplementedError` when the core-instance section contained an entry the parser cannot decode. Such entries are now recorded as parse problems instead: an unknown instance-encoding tag consumes only the tag byte, and a decode failure mid-entry skips the section remainder as one corrupt region. Both show up in the **Problems** tab, which now also covers component binaries, with kind, byte offset, size and message; clicking a row selects the bytes in the hex view. The dependency graph and the component WAT text label the affected instances explicitly instead of failing.
- Navigating to a function in the **WAT (virtualized)** tab (from the Problems tab, go-to-function or search) landed on the wrong function in modules whose type section contains collapsed `(rec …)` groups: every collapsed group member before the target shifted the scroll one row further (clicking func 25 landed on func 83 in a 216-type WasmGC module). The scroll index now accounts for group-collapsed rows.
- The WAT view's hex byte column garbled every line after an unparsed opcode region: the renderer advanced one byte instead of the region's full size.
- Debugger pauses and breakpoints could open the wrong file in projects where several crates share source file names (`lib.rs`, `mod.rs`): DWARF paths were matched by base name only. Both the display and the breakpoint direction now prefer the longest matching path suffix.
- In unoptimized wasm builds the Variables view labeled compiler scratch locals with source variable names (the shadow stack pointer showed up as a struct variable): the positional name fallback no longer claims variables whose DWARF location is linear memory. V8's operand-stack object also no longer appears in Variables under a stolen variable name; its entries live in the **Wasm** tab.
- Wasmtime breakpoint stops were silently skipped for crates that keep sources at the crate root (`<crate>/lib.rs` with no `src/` directory, common for standalone examples and moved/downloaded fixtures): the stop-location verification assumed the `<crate>/src/<file>` layout and auto-resumed past the breakpoint as a suspected wrong-file match. A matching crate directory directly above the file now confirms the stop; colliding generic tails like `src/lib.rs` across different crates are still rejected.
- Placing a breakpoint in a wasm source file no longer queries the DWARF file index on the UI thread (it tripped the platform's slow-operation assertion). The index snapshot is cached in the background, preloaded at project open and refreshed immediately when any `.wasm` file in the project changes, so a breakpoint can be set right after rebuilding a module without waiting or clicking twice.
- Starting a wasm debug session no longer logs a "[Split debugger] RunContentDescriptor should not be used" error on 2026.1+: all debug runners now start sessions through the platform's session-builder API where available, keeping the old path on IDEs that predate it.

## 0.16 - 2026-08-05

### Added
- The **WAT (virtualized)** tab of a Component Model binary no longer goes empty when the component embeds no core modules. Glue-only components (types, imports, exports, instances, aliases) now render their component-level constructs in the same virtualized panel core modules get: foldable per-section headers, search by name or offset, and a clickable offset gutter that jumps to the byte in the hex view. Imports and exports are anchored to their exact byte ranges; sections the parser cannot decode per record (for example canon) show an explicit "not decoded" row instead of failing the view. Rendering works from the in-memory buffer, so it also covers nested components opened from the Components tab, and the plain **Wat** tab now converts in-memory nested views directly from their bytes instead of failing with "No such file or directory".
- Nested components of a Component Model binary now open in their own editor view. A **Components** tab lists the embedded component the way **Modules** lists embedded core modules, and clicking the row opens it. Opening is recursive: modules and components inside an already-opened nested view open the same way, and byte offsets accumulate across levels so every nested view is labelled with its absolute offset in the root file. Nested in-memory views don't offer Run actions (there is no file on disk a run configuration could execute) and gain the full tab set of a disk-opened file, including the plain Wat tab and, for core modules, DWARF panels.

## 0.15 - 2026-07-29

### Added
- `call_indirect` dispatch candidates in the WAT view. Every `call_indirect` / `return_call_indirect` row now shows its statically known dispatch candidates: the functions in the table whose signature matches the call site's type (matching is structural, so candidates declared under different but identical type entries are merged). Click the instruction row (or press Go to Declaration) for a candidates popup that navigates to any candidate, or expand the inline `show N candidates` row beneath the instruction for the full list as click-navigable rows. A signature with no table entries is called out explicitly: that call site can only trap. Candidates are the element-segment entries (active and passive); functions written into the table at runtime via `table.set` are not visible to static analysis, so the lists say "statically known", not "all possible targets".
- **Dispatch** tab in the WASM module diff, next to **Size Impact** and **Entities**: per canonical signature, which dispatch candidates entered or left the function table between the two builds. Cross-build candidate identity uses the same content-hash function matcher as the **Entities** tab (names are not trusted: wasm-bindgen closure shims rename every build), and each change distinguishes a brand-new or removed function from an existing function that merely entered or left the table, the quiet mechanism that pins code `wasm-opt` cannot remove. Clicking a row opens the side-by-side WAT of the candidate. As an example of what it surfaces: running `wasm-opt -Oz` on a workers-rs module leaves the element segments byte-identical in count while duplicate-function elimination makes hundreds of table slots share one body, and the tab shows exactly that shrinkage per signature.
- **Go to Source** context menu items across the WASM views. Right-clicking a row in the **Functions** or **Exports** tab, or an instruction row in the WAT view, now offers `Go to Source (dir/file.rs:123)` when the module carries debug information; clicking opens the file at that line. Function rows resolve to the function's first mapped line (the signature), WAT rows resolve per instruction. Locations come from DWARF when present, otherwise from a source map. The WAT row menu also carries the dispatch-candidates action on `call_indirect` rows.
- Source maps are now discovered through the module's `sourceMappingURL` custom section, the form Kotlin/Wasm and Emscripten emit: a path is resolved relative to the wasm file (absolute paths accepted) and `data:` URLs with the inline map are decoded, with the existing `<file>.map` sidecar convention as the fallback. Remote http(s) URLs are not fetched.
- Exception-handling visibility for the exception-handling proposal (`try_table` / `throw` / `throw_ref`). Exception instructions now render with their real WAT text (`throw 0`, `try_table (catch 0 1) (catch_all 2)`, `throw_ref`) plus a trailing comment decoding each index: the tag a `throw` raises with its payload signature, and every catch clause's tag and target label. Clicking a `throw` row resolves where it is caught, the enclosing `try_table` and clause (tag-specific or `catch_all`), or reports that it escapes the function, and for an escaping throw names the exported entry points its exception reaches uncaught. A **Tags** tab decodes the module's exception tags (index, origin, attribute, payload) the way the **Types** tab decodes types. An **Exceptions** tab shows the module's panic surface: exported entry points whose exceptions escape to the host, with the tags (and any dynamic `throw_ref` / host-import throws) that get out. Analysis is a sound static under-approximation: `throw_ref` and imported-function throws are tracked as a dynamic category, and a `catch_all` on the path correctly makes a throw "caught".
- Semantic section labels are now clickable when there is a corresponding information tab below, and clicking on a label opens the corresponding tab.
- "Save as" for nested wasm modules and jar/zip entries, allows saving the content to a separate file.
- Size diagram ("Top" tab) for wasm and native binaries that shows largest by size entities.

### Changed
- The call graph now models indirect calls through one hub node per canonical signature (`<indirect: (i32, i32) -> i32>`) instead of the single `<indirect calls>` node introduced in 0.14. Each indirect-calling function connects to the hubs of the signatures it actually dispatches through, and each hub fans out only to the table functions matching that signature, so the **Dominators** and **Caller Paths** views read "retained by indirect callers of this signature" instead of one undifferentiated bucket. The edge count keeps the same linear bound as before (call sites plus table entries, now partitioned), preserving the scaling fix that made indirect-call-heavy Emscripten modules analyzable.

### Fixed
- The call-graph **Dominators** tree rendered empty in 0.14. The compact dominator storage introduced there used -1 as its "no parent" marker, which is also the virtual root's node id, so every function dominated directly by the root was dropped from the tree; function-level CFG dominators were unaffected, which is how it slipped through. The tree now renders fully again.
- Source locations resolve on Rust-produced modules. The DWARF lookup found compilation units by their low/high-pc attributes, which rustc does not emit (it uses range lists), so the hex view's "Open in sources" never appeared for Rust artifacts; the lookup now also consults the line tables directly. Two more mismatches fixed along the way: callers pass module-absolute offsets while wasm DWARF addresses are code-section-relative (queries are now converted, and DWARF-based navigation was one line off), and a module that parses but has no DWARF, the Kotlin/Wasm case, never fell back to a loaded source map (the fallback now happens per lookup).
- Rows of nested tables are sorted again according to the parent sorting rules (it was broken recently). This, for instance fixes the sorting of the "Top" tab.
- Fixed a UI freeze that had rarely been happening when switching between "Decompiled" and other tabs multiple times.

## 0.14.1 - 2026-07-29

### Added
- Semantic section labels are now clickable when there is a corresponding information tab below, and clicking on a label opens the corresponding tab.
- "Save as" for nested wasm modules and jar/zip entries, allowing to save the content to a separate file.

### Fixed
- Rows of nested tables are sorted again according to the parent sorting rules (it was broken recently). This, for instance fixes the sorting of the "Top" tab.
- Fixed a UI freeze that had rarely been happening when switching between "Decompiled" and other tabs multiple times.

## 0.14 - 2026-07-22

### Added
- Action to show a diff between two native binary files with size changes per section
- WASM Component dependency diagram showing dependencies within a WASM Component ("Dependencies" tab, components only)
- Data inspector panel showing different representations of the selected byte
- Semantic highlighting of sections and navigation between sections for WASM and Native binary files

### Changed
- Hexana's IntelliJ MCP toolset now uses schema version 3 and advertises/enforces bounded list inputs such as `indices.maxItems`, allowing MCP clients to validate oversized requests before invoking tools.
- The embedded IntelliJ MCP provider now exposes the 29-tool default profile. Advanced inventory and raw-detail tools such as `list_binary_functions`, `search_binary_functions`, `query_binary`, `resolve_component_dependencies`, `list_component_surface`, `list_custom_sections`, `list_code_section_offsets`, `functions_for_indices`, `get_locals_for_functions`, `get_instructions_for_functions`, `get_memory_for_indices`, `get_globals_for_indices`, `list_types`, `list_value_types`, `list_reference_types`, `get_types_for_indices`, `list_data`, and `query_artifacts` remain available from the standalone MCP server's `advanced` profile.
- The call graph models indirect calls through a single synthetic `<indirect calls>` node: each function containing a `call_indirect` gets one edge to it, and it fans out to the function table. In the **Dominators** view, functions reachable only through indirect calls now group under that node instead of scattering across the tree. On indirect-call-heavy C++/Emscripten modules this also replaces a callers-times-table product of edges (hundreds of millions on real modules) with a linear count, which is what made such modules impossible to analyze before (see Fixed).

### Fixed
- Fixed a UX problem with vertical splitter – now it's draggable when the pointer is over its upper half too
- WASM Diff action now properly works when two files are selected in the project view (previously it was always asking for the second file)
- Opening the **Functions** tab of a large module no longer freezes the whole IDE. Its module-wide inputs (function signatures, and the call-graph plus dominator analysis behind the unreachable-function badges) were computed at first cell render on the UI thread; they now run during background tab loading while the tab shows its placeholder, so the editor stays responsive and the tab appears fully rendered.
- Tabs whose content resolves to nothing no longer show an endless loading spinner. A tab header is shown optimistically while its content loads in the background; when the analysis finds no data (for example Garbage on a module with no findings, or Monos without monomorphized groups) the placeholder is now removed, and a Garbage analysis with a single non-empty subtab (shown as a promoted "Garbage: ..." tab) now replaces its placeholder instead of leaving it spinning next to the content.
- Large modules, including 64-bit memory (memory64 / wasm64) builds, no longer exhaust the IDE heap when analyzed. Dominator results are stored in one compact int buffer instead of boxed hash maps, the dominance frontier (a near-quadratic structure nothing in the product consumed) is no longer computed, and indirect-call edges are linear instead of multiplicative (see Changed). A 50 MB memory64 module with 42k functions, OpenUSD's Emscripten-built `usdviewweb.wasm`, previously drove the IDE past 10 GB of heap into OutOfMemoryError; it now opens, renders the Functions tab, and completes garbage/dominator analysis within a default 2 GB heap, with UI stalls kept near one second (guarded by an EDT-responsiveness driver test that ships with a bundled memory64 fixture).

## 0.13.1 - 2026-07-15

### Added
- added a "Decompiled" tab for .class files showing the decompiled Java source code

### Changed
- improved performance of information tabs and file structure

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
