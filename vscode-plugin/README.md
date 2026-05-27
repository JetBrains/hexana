# Hexana

A VS Code extension for viewing and analyzing binary files with a focus on WebAssembly and Native binary formats.
Open any `.wasm` file to explore its structure, inspect hex data, and run modules — all inside VS Code.
Open any ELF/Mach-O/PE file and explore its structure and inspect hex data.

## Features

### Custom Binary Editor

Opens WASM and Native binary files in a dedicated read-only editor instead of the default hex view.
For WASM, it automatically detects Core Wasm modules, Component Model binaries, and generic Wasm files.

### Hex Viewer

Virtual-scrolling hex dump with byte selection (click, shift-click, drag), keyboard navigation, and text search.

### Structural Analysis

A tabbed analysis panel with views depending on the binary type.

For example, for WASM files we show the following tabs:
- **Summary** — section table and binary statistics.
- **Exports** — exported items with kind, name, index, and function signatures.
- **Imports** — imported items with kind, module, and name.
- **Functions** — function index, name, and signature.
- **Data** — data segments overview.
- **Custom** — custom sections list.
- **Top** — largest contributors by size.
- **Monos** — monomorphisation analysis.
- **Garbage** — unreferenced/dead code detection.
- **Modules** — clickable nested module list with drill-down (component model).
- **WAT** — WebAssembly Text representation, opened in a native VS Code editor tab with syntax highlighting.

All analysis tables support column sorting and text search.

### Run Support (WASM only)

Run Wasm modules directly from the editor toolbar via [wasmtime](https://wasmtime.dev/), with a dialog for choosing exports and program arguments. Supports both core modules (with automatic import stub generation) and component model binaries (with dependency resolution and composition).

### WASM Component Model

- Automatic component dependency resolution by scanning workspace directories for matching `.wasm` files, including transitive dependencies.
- Navigate nested modules inside a component binary by opening them in separate editor tabs.

## Requirements

- **VS Code** 1.102 or later.
- **[wasmtime](https://wasmtime.dev/)** (optional) — needed for the Run button to execute Wasm modules.
- **[wasm-tools](https://github.com/bytecodealliance/wasm-tools)** or **[wac](https://github.com/bytecodealliance/wac)** (optional) — used for component composition when running component model binaries with unresolved imports.
- **Java 21 or later** (optional) — needed for the Hexana MCP server. The server is downloaded on demand; set `hexana.mcp.javaHome` if Java is not available through `JAVA_HOME` or `PATH`.

## Links

- [Source code](https://github.com/JetBrains/hexana)
- [Issue tracker](https://github.com/JetBrains/hexana/issues)

## License

See [LICENSE](https://www.jetbrains.com/legal/docs/toolbox/user/).

This extension collects telemetry data, which is used to help understand how to improve the product. Please refer to [JetBrains Privacy Notice](https://www.jetbrains.com/legal/docs/privacy/privacy/) for more information about data collection and use.
You can opt out of telemetry by setting the `hexana.enableStatistics` setting to `false`.
