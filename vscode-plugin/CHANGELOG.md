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
