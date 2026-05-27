---
title: Hexana — Documentation
description: Hexana is a WebAssembly and binary analysis toolkit by JetBrains, available as an IntelliJ Platform plugin and a Visual Studio Code extension.
hide:
  - navigation
---

# Hexana

**Hexana** is a WebAssembly and binary analysis toolkit by **JetBrains**, available in two flavours:

<div class="grid cards" markdown>

-   ### For JetBrains IDEs

    Full IntelliJ Platform plugin — multi-tab `.wasm` editor with an editable virtualised WAT view, WAT and WIT language support, MCP server with Hexana tools, Java-side completion for GraalWasm and Chicory, JavaScript / TypeScript type inference for `WebAssembly.instantiate`, run and debug on Wasmtime / WAMR / GraalVM. Experimental ELF / Mach-O / PE binary support, JVM artifact viewers (`.class`, `.jar`, `.war`, `.apk`, `.jit`), and a switchable disassembler backend (bytecode AOT or Cranelift native).

    **Current release**: 0.10
    Supported IDEs: IntelliJ IDEA 2024.1+, RustRover, WebStorm, CLion, PyCharm, Rider, PhpStorm.

    [Read the docs →](jetbrains/index.md)

-   ### For Visual Studio Code

    VS Code extension with a Compose-for-Web custom editor, virtual-scrolling hex viewer, 11 structural-analysis tabs, Component Model dependency resolution, experimental debugging, an on-demand MCP server, and Run on Wasmtime / WAMR / GraalVM. ELF / Mach-O / PE binaries open with the same hex + structure layout.

    **Current release**: 0.2.0
    Supports: VS Code 1.102+, Cursor, VSCodium, Code OSS, and other VS Code-based editors via Open VSX.

    [Read the docs →](vscode/index.md)

</div>

## What both products share

Hexana's WASM analysis core is a single Kotlin Multiplatform codebase used by both the JetBrains plugin and the VS Code extension:

- **WASM parser** supporting Core Wasm, Component Model, GC, SIMD, Threads, Tail Call, Reference Types, Bulk Memory, Multi-Value, and Legacy Exception Handling.
- **Binary analysis** — imports, exports, function and type catalogues, size profiling, dead-code detection, monomorphisation analysis, custom-section inspection (including DWARF for the JetBrains plugin).
- **Component Model awareness** — both products detect components, list nested modules, and resolve component dependencies.
- **Native binary support** (experimental) — ELF, Mach-O, PE detection from magic bytes; the same hex + structure layout applies to native binaries as to WebAssembly modules.
- **Run support** through Wasmtime, WAMR, and GraalVM with automatic proposal-flag detection.

## Choosing between the two

| You want… | Use |
|---|---|
| The deepest WIT editing experience | JetBrains plugin |
| Editable WAT with inline row editing | JetBrains plugin |
| MCP server for AI assistants | Both — bundled in JetBrains plugin; on-demand download in VS Code extension |
| Java-side completion (GraalWasm, Chicory) | JetBrains plugin |
| JS / TS `WebAssembly.instantiate` type inference | JetBrains plugin |
| Native-binary disassembly with backend choice (AOT or Cranelift) | JetBrains plugin |
| `.jar` / `.class` / `.jit` viewers | JetBrains plugin |
| Debugger with breakpoints | Both — experimental in both |
| DWARF source mapping | Both |
| Run on WAMR or GraalVM | Both |
| Lightweight `.wasm` inspector in VS Code | VS Code extension |
| Compose-for-Web custom editor in VS Code / Cursor / VSCodium | VS Code extension |
| Use Hexana from Cursor or VSCodium | VS Code extension |
| Both | Install both — they don't conflict |

## AI-indexer note

This documentation site exposes [`llms.txt`](llms.txt) at the root for LLM consumption. See the `ai-docs-optimizer` agent definition in the repo for the conventions applied.

## Links

- [GitHub repository](https://github.com/JetBrains/hexana)
- [Issue tracker](https://github.com/JetBrains/hexana/issues)
- [JetBrains Marketplace listing](https://plugins.jetbrains.com/plugin/29090-hexana)
- [VS Code Marketplace listing](https://marketplace.visualstudio.com/items?itemName=JetBrains.hexana-wasm)
- [Open VSX listing](https://open-vsx.org/extension/JetBrains/hexana-wasm)
