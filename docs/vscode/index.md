---
title: Hexana VS Code Extension — Documentation
description: User and contributor documentation for the Hexana VS Code extension.
version: "0.13.0"
audience: [users]
---

# Hexana — VS Code Extension Documentation

**Hexana** is a Visual Studio Code extension by JetBrains for inspecting and running WebAssembly binaries, and since 0.2.0 native binaries (ELF, Mach-O, PE); since 0.3.0, JVM archives (`.jar`, `.zip`, `.war`, `.apk`) as well. Open any `.wasm` file and Hexana replaces the default editor with a Compose-for-Web webview: a virtual-scrolling hex viewer alongside up to **15 structural-analysis tabs** (Summary, Exports, Imports, Functions, Data, Custom, Top, Monos, Garbage, Modules, WAT, Dependencies, DWARF, Compatibility, and Problems). Native binaries and archives open with the same hex + structure layout. Since 0.4.0 the hex viewer also supports **in-place byte editing** (overwrite-only, the file size never changes). Since 0.5.0 the WAT tab uses **virtual scrolling** for faster navigation in large modules, and the editor also opens Java `.class` and Android DEX files directly, as well as ZIP64 archives. Since 0.6.0 compiled **Protocol Buffers descriptor sets** (`.desc` / `.protoset`) open with five dedicated tabs, and **scriptable "+" tabs** let you attach custom JS analysis to any open file. Since 0.6.1 Component Model binaries use the virtualized WAT renderer for their embedded core modules. Since 0.7.0 a **binary diff** action compares two WASM or native binary files, and a **data inspector** panel decodes the selected byte on the fly. Since 0.8.0 sections are **semantically highlighted** with direct navigation between them, Component Model binaries have a **Dependencies** diagram tab, and nested modules and archive entries support **Save as**. Since 0.9.0 nested components open recursively and the Top tab works for native binaries. Since 0.10.0 Wasmtime 44+ uses the built-in **gdbstub** debug path, **source-level stepping** and a **"Wasm" debugger view** (WAT disassembly + operand stack) are available for all debug backends, VS Code's **Disassembly View** shows WAT-style instructions with DWARF source locations, and **wasi-sdk** downloads are managed in-extension. Since 0.11.0 DWARF sections are grouped in a single **DWARF tab**, the Dependencies graph is navigable, a **Compatibility tab** checks proposals against configured runtimes, and `.dex` files have a **Smali tab**. Since 0.12.0 **Apache Parquet, Arrow, tar archives, and opaque Protobuf wire messages** open natively, **content-based opening** handles extensionless files, and **"Reopen Editor With"** works for any file. Since 0.13.0 the diff view adds a **hex diff** panel and a **Functions diff** for ELF/Mach-O/COFF, and VS Code's "Compare Selected" opens Hexana's diff. Run modules through **Wasmtime**, **WAMR**, **GraalVM**, **wazero** (0.6.0+), **Node.js**, or the **browser**, **debug** them (experimental), resolve Component Model dependencies automatically, and drive analysis from an AI tool over the on-demand-downloaded **MCP server**.

> Looking for the IntelliJ Platform plugin? See the [JetBrains IDEs section](../jetbrains/index.md). The VS Code and JetBrains products share the same WASM parser core but differ in language-support depth, run integrations, and target audience.

## What's in this directory

| File | Audience | Purpose |
|---|---|---|
| [`getting-started.md`](getting-started.md) | Users | Install the extension, open a `.wasm` file, find the main panels. |
| [`features.md`](features.md) | Users | Complete capability reference. |
| [`analysis-tabs.md`](analysis-tabs.md) | Users | Per-tab reference for all 12 analysis panels. |
| [`run-support.md`](run-support.md) | Users | Run Core Wasm and Component Model binaries through Wasmtime, WAMR, or GraalVM, including composition with `wasm-tools` / `wac` and the experimental debug path. |
| [`component-model.md`](component-model.md) | Users | Component Model support — dependency resolution and nested-module navigation. |
| [`settings.md`](settings.md) | Users | VS Code settings Hexana contributes (`hexana.wasmtimePath`, `hexana.enableStatistics`, `hexana.mcp.javaHome`). |
| [`troubleshooting.md`](troubleshooting.md) | Users | Common failure modes and resolutions. |
| [`changelog.md`](changelog.md) | All | Release notes. |

## Version and compatibility

- **Extension version**: 0.13.0.
- **VS Code requirement**: `^1.102.0` — required for the MCP-server registration API used by the on-demand MCP integration. Older 0.0.x releases ran on 1.85+.
- **Optional Java**: 21 or newer for the MCP server on JVM-fallback platforms (e.g. Windows). On macOS arm64 and Linux x64 the server uses a bundled native executable and needs no JDK. The server is downloaded on demand on first MCP use; set `hexana.mcp.javaHome` to point at a specific JDK if `JAVA_HOME` / `PATH` do not surface one.
- **Optional LLVM**: 22.1 or newer for the `lldb`-backed debug path (WAMR and Wasmtime older than 44). Wasmtime 44+ uses the built-in gdbstub and does not require LLVM.
- **Distribution**: Visual Studio Marketplace (`marketplace.visualstudio.com/items?itemName=JetBrains.hexana-wasm`) and Open VSX (`open-vsx.org`).
- **Companion**: a JetBrains IntelliJ plugin, documented [here](../jetbrains/index.md).

## Supported editors

The extension targets the VS Code Custom Editor API, Webview API, and MCP-server-registration API. It works in:

- **Visual Studio Code** 1.102+
- **VS Code Insiders**
- **Cursor** (a fork of VS Code) — provided it exposes the MCP-server-registration API at the 1.102 baseline
- **Code OSS / VSCodium** when installed from Open VSX
- **Windsurf**, **Continue.dev**, and other VS Code-based editors that respect the standard extension API at the 1.102 baseline

Some features that depend on VS Code's terminal, filesystem, or MCP providers may behave differently across forks; report fork-specific issues at the tracker.

## How to read this set

- **First-time user**: [`getting-started.md`](getting-started.md) → [`features.md`](features.md).
- **Already opened a `.wasm`, want to run it**: [`run-support.md`](run-support.md).
- **Working with Component Model binaries**: [`component-model.md`](component-model.md).
- **Plugin doesn't load / file doesn't open**: [`troubleshooting.md`](troubleshooting.md).
