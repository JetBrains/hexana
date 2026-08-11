# Unreleased
- "Problems" tab for WASM binaries listing regions the parser could not decode (unknown opcodes, unsupported proposal constructs) with location, offset and message; clicking a row jumps to the function in the WAT tab, rows without a function select the bytes in the hex view
- parsing support for the custom-descriptors proposal type encodings (exact reference types, descriptor/describes clauses); WasmGC modules using them previously failed to parse
- function bodies with a single undecodable instruction now keep everything decoded up to that point instead of turning into one unparsed blob
- basic support for pcap/pcapng files – viewing packets, opening http response body in a new tab
- support for viewing tar entries directly in Hexana
- support for gzip - compressed files of supported kinds (like .wasm.gz) can be opened directly in Hexana

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
