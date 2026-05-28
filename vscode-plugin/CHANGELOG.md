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