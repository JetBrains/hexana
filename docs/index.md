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

    Full IntelliJ Platform plugin — multi-tab `.wasm` editor, WAT and WIT language support, MCP server with 17 tools, Java-side completion for GraalWasm and Chicory, JavaScript / TypeScript type inference for `WebAssembly.instantiate`, run and debug on Wasmtime / WAMR / GraalVM.

    **Current release**: 0.9 (2026-05-07)
    Supported IDEs: IntelliJ IDEA 2024.1+, RustRover, WebStorm, CLion, PyCharm, Rider, PhpStorm.

    [Read the docs →](jetbrains/index.md)

-   ### For Visual Studio Code

    VS Code extension with a Compose-for-Web custom editor, virtual-scrolling hex viewer, 11 structural-analysis tabs, Component Model dependency resolution, and Wasmtime run support.

    **Current release**: 0.0.2 preview
    Supports: VS Code 1.85+, Cursor, VSCodium, Code OSS, and other VS Code-based editors via Open VSX.

    [Read the docs →](vscode/index.md)

</div>

## What both products share

Hexana's WASM analysis core is a single Kotlin Multiplatform codebase used by both the JetBrains plugin and the VS Code extension:

- **WASM parser** supporting Core Wasm, Component Model, GC, SIMD, Threads, Tail Call, Reference Types, Bulk Memory, Multi-Value, and Legacy Exception Handling.
- **Binary analysis** — imports, exports, function and type catalogues, size profiling, dead-code detection, monomorphisation analysis, custom-section inspection (including DWARF for the JetBrains plugin).
- **Component Model awareness** — both products detect components, list nested modules, and resolve component dependencies.
- **Run support** through Wasmtime, with automatic proposal-flag detection.

## Choosing between the two

| You want… | Use |
|---|---|
| The deepest WIT editing experience | JetBrains plugin |
| MCP server for AI assistants | JetBrains plugin |
| Java-side completion (GraalWasm, Chicory) | JetBrains plugin |
| JS / TS `WebAssembly.instantiate` type inference | JetBrains plugin |
| Debugger with breakpoints | JetBrains plugin |
| DWARF source mapping | JetBrains plugin |
| Run on WAMR or GraalVM | JetBrains plugin |
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
