---
title: Hexana Settings Pages (0.9)
description: The two Settings pages Hexana registers — Tools → Hexana, and Build, Execution, Deployment → WASM Runtime.
version: "0.9"
---

# Settings

Hexana 0.9 registers two `applicationConfigurable` entries (IDE-wide, not per-project).

## Settings → Tools → Hexana

- **ID**: `hexana.settings`
- **Provider**: `HexanaConfigurableProvider`
- **Title**: bundled key `hexana.settings.displayName`

General Hexana plugin settings. Surfaces include:

- File-type associations (managed by the platform, surfaced for visibility).
- Hex view appearance.
- Diagnostic / logging toggles for the plugin.

The exact field set in 0.9 is the responsibility of `HexanaConfigurableProvider`; consult that class for the source of truth.

## Settings → Build, Execution, Deployment → WASM Runtime

- **ID**: `wasm.runtime.settings`
- **Instance**: `WasmRuntimeConfigurable`
- **Title**: bundled key `wasm.runtime.settings.displayName`
- **Backing service**: `WasmRuntimeSettings` (application-level)

Configures runtimes for the WASM run/debug configurations covered in [`run-and-debug.md`](run-and-debug.md). Fields:

- **Default runtime** — Wasmtime, WAMR, or GraalVM. Used when a run configuration does not override it.
- **Wasmtime path** — absolute path to the `wasmtime` executable.
- **WAMR path** — absolute path to `iwasm` (or the WAMR binary you ship).
- **GraalVM home** — absolute path to a GraalVM installation that includes the GraalWasm component. Empty means use Hexana's built-in GraalVM image.

These values persist into the IDE's options storage and survive restarts.

## Settings → Editor → Inspections → WebAssembly

Hexana contributes inspections in two language groups:

- **WIT** — five inspections (empty definition, world name uniqueness, missing semicolon, gate, use-declaration missing names). See [`wit-language.md`](wit-language.md).
- **WebAssembly** (Java group) — four inspections (unresolved export, export argument count mismatch, export argument type mismatch, unresolved import). See [`java-integration.md`](java-integration.md).

Each can be enabled, disabled, or have its severity raised/lowered like any other IDE inspection.

## Settings → Editor → Code Style → WIT

Contributed by `WitCodeStyleSettingsProvider`. Standard indent, tabs, and wrapping options.

## Settings → Tools → Server (MCP)

The platform MCP server lives in the bundled `com.intellij.mcpServer` plugin and exposes its own Settings page. Hexana adds tools to that server but does not introduce its own MCP settings page in 0.9. See [`mcp-tools.md`](mcp-tools.md) for the tools Hexana contributes.

## Settings → Plugins → Marketplace → "Hexana"

Not a Hexana surface — it is the Plugin Manager — but a relevant entry point. Use it to enable, disable, update, or uninstall Hexana, and to read the listing description and "What's New" content for each release.
