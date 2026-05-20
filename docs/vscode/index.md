---
title: Hexana VS Code Extension — Documentation (0.1.0)
description: User and contributor documentation for the Hexana VS Code extension, version 0.1.0 (released 2026-05-20).
version: "0.1.0"
released: 2026-05-20
audience: [users]
source-branch: release/vscode-0.1.0
---

# Hexana — VS Code Extension Documentation

**Hexana** is a Visual Studio Code extension by JetBrains for inspecting and running WebAssembly binaries. Open any `.wasm` file and Hexana replaces the default editor with a Compose-for-Web webview: a virtual-scrolling hex viewer alongside up to **11 structural-analysis tabs** (Summary, Exports, Imports, Functions, Data, Custom, Top, Monos, Garbage, Modules, WAT). Run modules through **Wasmtime**, **WAMR**, or **GraalVM**, **debug** them (experimental, LLVM 22.1+), resolve Component Model dependencies automatically, and drive analysis from an AI tool over the on-demand-downloaded **MCP server**. This documentation set describes **release 0.1.0** (2026-05-20) sourced from branch `release/vscode-0.1.0`.

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
| [`changelog.md`](changelog.md) | All | Release notes, currently pinned at 0.1.0. |

## Version and compatibility

- **Extension version**: 0.1.0 (marked `preview` on the marketplace).
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

## Source of truth

This documentation set describes the **0.1.0** preview release. For unreleased work, see the commit history on `master` after `release/vscode-0.1.0`.
