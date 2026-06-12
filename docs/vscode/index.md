---
title: Hexana VS Code Extension — Documentation
description: User and contributor documentation for the Hexana VS Code extension.
version: "0.3.0"
audience: [users]
---

# Hexana — VS Code Extension Documentation

**Hexana** is a Visual Studio Code extension by JetBrains for inspecting and running WebAssembly binaries — and, since 0.2.0, native binaries (ELF, Mach-O, PE); since 0.3.0, JVM archives (`.jar`, `.zip`, `.war`, `.apk`) as well. Open any `.wasm` file and Hexana replaces the default editor with a Compose-for-Web webview: a virtual-scrolling hex viewer alongside up to **11 structural-analysis tabs** (Summary, Exports, Imports, Functions, Data, Custom, Top, Monos, Garbage, Modules, WAT). Native binaries and archives open with the same hex + structure layout. Run modules through **Wasmtime**, **WAMR**, **GraalVM**, **Node.js**, or the **browser**, **debug** them (experimental, LLVM 22.1+, Wasmtime / WAMR only), resolve Component Model dependencies automatically, and drive analysis from an AI tool over the on-demand-downloaded **MCP server**.

> Looking for the IntelliJ Platform plugin? See the [JetBrains IDEs section](../jetbrains/index.md). The VS Code and JetBrains products share the same WASM parser core but differ in language-support depth, run integrations, and target audience.

## What's in this directory

| File | Audience | Purpose |
|---|---|---|
| [`getting-started.md`](getting-started.md) | Users | Install the extension, open a `.wasm` file, find the main panels. |
| [`features.md`](features.md) | Users | Complete capability reference. |
| [`analysis-tabs.md`](analysis-tabs.md) | Users | Per-tab reference for all 11 analysis panels. |
| [`run-support.md`](run-support.md) | Users | Run Core Wasm and Component Model binaries through Wasmtime, WAMR, or GraalVM, including composition with `wasm-tools` / `wac` and the experimental debug path. |
| [`component-model.md`](component-model.md) | Users | Component Model support — dependency resolution and nested-module navigation. |
| [`settings.md`](settings.md) | Users | VS Code settings Hexana contributes (`hexana.wasmtimePath`, `hexana.enableStatistics`, `hexana.mcp.javaHome`). |
| [`troubleshooting.md`](troubleshooting.md) | Users | Common failure modes and resolutions. |
| [`changelog.md`](changelog.md) | All | Release notes. |

## Version and compatibility

- **Extension version**: 0.3.0.
- **VS Code requirement**: `^1.102.0` — required for the MCP-server registration API used by the on-demand MCP integration. Older 0.0.x releases ran on 1.85+.
- **Optional Java**: 21 or newer for the MCP server. The server is downloaded on demand on first MCP use; set `hexana.mcp.javaHome` to point at a specific JDK if `JAVA_HOME` / `PATH` do not surface one.
- **Optional LLVM**: 22.1 or newer for the experimental debug path (Wasmtime + WAMR, `lldb` under the hood).
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
