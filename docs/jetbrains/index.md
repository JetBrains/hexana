---
title: Hexana IntelliJ Plugin — Documentation (0.9)
description: User and contributor documentation for the Hexana IntelliJ Platform plugin, version 0.9 (released 2026-05-07).
version: "0.9"
released: 2026-05-07
audience: [users]
---

# Hexana — IntelliJ Plugin Documentation

**Hexana** is an IntelliJ Platform plugin (plugin ID `org.jetbrains.hexana`, vendor JetBrains) for WebAssembly and binary analysis. It parses `.wasm` modules, renders WAT and WIT, integrates with WASM runtimes (Wasmtime, WAMR, GraalVM), exposes a Model Context Protocol (MCP) server for AI-assisted exploration, and ships Java-side code completion for the GraalWasm and Chicory APIs. This documentation set describes **release 0.9** (2026-05-07).

## What's in this directory

| File | Audience | Purpose |
|---|---|---|
| [`getting-started.md`](getting-started.md) | Users | Install Hexana, open a `.wasm` file, find the main views. |
| [`features.md`](features.md) | Users | Complete capability reference grouped by surface. |
| [`file-types.md`](file-types.md) | Users | `.wasm`, `.wat`, `.wit`, generic binary (`.bin`, `.elf`, `.exe`). |
| [`wit-language.md`](wit-language.md) | Users | WIT (WebAssembly Interface Types) language support. |
| [`run-and-debug.md`](run-and-debug.md) | Users | Run configurations, runtime selection (Wasmtime / WAMR / GraalVM), experimental debugging. |
| [`mcp-tools.md`](mcp-tools.md) | AI-tool users | Hexana's 17 MCP tools, one section per tool. |
| [`java-integration.md`](java-integration.md) | Java/JVM users | GraalWasm and Chicory completion and inspections. |
| [`js-integration.md`](js-integration.md) | JS / TS users | `WebAssembly.instantiate` imports completion and `.instance.exports` type inference. |
| [`settings.md`](settings.md) | Users | `Settings → Tools → Hexana` and `Settings → Build, Execution → WASM Runtime`. |
| [`troubleshooting.md`](troubleshooting.md) | Users | Common failure modes and resolutions. |
| [`changelog-0.9.md`](changelog-0.9.md) | All | Release notes for 0.9, pinned. |
| [`llms.txt`](llms.txt) | LLM agents | Curated index for AI assistants reading the docs. |

## Version and compatibility

- **Plugin version**: 0.9 (built from `release/0.9` branch, commit `6a108799`).
- **`since-build`**: defined by `gradle.properties` → `defaultSinceBuild`. Hexana targets IntelliJ Platform 2024.1+.
- **Optional integrations**: the JavaScript plugin (enables JS interop for `instance.exports`) and the Java module (enables GraalWasm / Chicory completion). Both are loaded when the host IDE bundles them.
- **Required dependency**: `com.intellij.mcpServer` — Hexana registers its toolset against the platform MCP server.

## Supported IDEs

IntelliJ IDEA, RustRover, WebStorm, CLion, PyCharm, Rider, PhpStorm, and any other IDE on IntelliJ Platform 2024.1+ that bundles or installs `com.intellij.mcpServer`. RustRover and CLion users get the natural fit for binary tooling; WebStorm and PhpStorm users get JS interop.

A companion **VS Code extension** is published separately on `marketplace.visualstudio.com` and `open-vsx.org`. See the [VS Code section](../vscode/index.md) of this site for that product's documentation.

## How to read this set

- **First-time user**: [`getting-started.md`](getting-started.md) → [`features.md`](features.md).
- **Already opened a `.wasm`, want to run it**: [`run-and-debug.md`](run-and-debug.md).
- **Driving Hexana from an AI assistant**: [`mcp-tools.md`](mcp-tools.md).
- **Plugin doesn't load / file doesn't open**: [`troubleshooting.md`](troubleshooting.md).

## Source of truth

This documentation set describes the **0.9** release. For unreleased work, see `idea-plugin/CHANGELOG.md` in the repository root. For the master-branch state, see commit history after `6a108799`.
