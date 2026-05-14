---
title: Hexana VS Code Extension — Documentation (0.0.2)
description: User and contributor documentation for the Hexana VS Code extension, version 0.0.2.
version: "0.0.2"
audience: [users]
source-branch: release/vscode-0.0.2
---

# Hexana — VS Code Extension Documentation

**Hexana** is a Visual Studio Code extension by JetBrains for inspecting WebAssembly binaries. Open any `.wasm` file and Hexana replaces the default editor with a Compose-for-Web webview: a virtual-scrolling hex viewer alongside up to **11 structural-analysis tabs** (Summary, Exports, Imports, Functions, Data, Custom, Top, Monos, Garbage, Modules, WAT). Run modules directly through Wasmtime. Resolve Component Model dependencies automatically. Drill into nested modules. This documentation set describes **release 0.0.2** sourced from branch `release/vscode-0.0.2`.

> Looking for the IntelliJ Platform plugin? See the [JetBrains IDEs section](../jetbrains/). The VS Code and JetBrains products share the same WASM parser core but differ in language-support depth, run integrations, and target audience.

## What's in this directory

| File | Audience | Purpose |
|---|---|---|
| [`getting-started.md`](getting-started.md) | Users | Install the extension, open a `.wasm` file, find the main panels. |
| [`features.md`](features.md) | Users | Complete capability reference. |
| [`analysis-tabs.md`](analysis-tabs.md) | Users | Per-tab reference for all 11 analysis panels. |
| [`run-support.md`](run-support.md) | Users | Run Core Wasm and Component Model binaries through Wasmtime, including composition with `wasm-tools` / `wac`. |
| [`component-model.md`](component-model.md) | Users | Component Model support — dependency resolution and nested-module navigation. |
| [`settings.md`](settings.md) | Users | The two VS Code settings Hexana contributes. |
| [`troubleshooting.md`](troubleshooting.md) | Users | Common failure modes and resolutions. |
| [`changelog.md`](changelog.md) | All | Release notes. |

## Version and compatibility

- **Extension version**: 0.0.2 (marked `preview` on the marketplace).
- **VS Code requirement**: `^1.85.0` (December 2023 and newer).
- **Distribution**: Visual Studio Marketplace (`marketplace.visualstudio.com/items?itemName=JetBrains.hexana-wasm`) and Open VSX (`open-vsx.org`).
- **Companion**: a JetBrains IntelliJ plugin, documented [here](../jetbrains/).

## Supported editors

The extension targets the VS Code Custom Editor API and Compose-for-Web webviews. It works in:

- **Visual Studio Code** 1.85+
- **VS Code Insiders**
- **Cursor** (which is a fork of VS Code)
- **Code OSS / VSCodium** when installed from Open VSX
- **Windsurf**, **Continue.dev**, and other VS Code-based editors that respect the standard extension API

Some features that depend on VS Code's terminal or filesystem providers may behave differently across forks; report fork-specific issues at the tracker.

## How to read this set

- **First-time user**: [`getting-started.md`](getting-started.md) → [`features.md`](features.md).
- **Already opened a `.wasm`, want to run it**: [`run-support.md`](run-support.md).
- **Working with Component Model binaries**: [`component-model.md`](component-model.md).
- **Plugin doesn't load / file doesn't open**: [`troubleshooting.md`](troubleshooting.md).

## Source of truth

This documentation set describes the **0.0.2** preview release. For unreleased work, see the commit history on `master` after the `release/vscode-0.0.2` tag.
