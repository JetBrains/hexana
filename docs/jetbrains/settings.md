---
title: Hexana Settings Pages
description: The Settings pages and Registry keys Hexana exposes — Tools → Hexana, Build/Execution → WASM Runtime, and disassembler-backend toggles in the Registry.
version: "0.11"
---

# Settings

Hexana registers two `applicationConfigurable` entries (IDE-wide, not per-project) and exposes additional behaviour through the platform Registry.

## Settings → Tools → Hexana

- **ID**: `hexana.settings`
- **Provider**: `HexanaConfigurableProvider`
- **Title**: bundled key `hexana.settings.displayName`

General Hexana plugin settings. Fields include:

- **`wasm-tools` path** and **Use built-in `wasm-tools`** — the binary toolchain Hexana shells out to for some WAT/component operations.
- **`llvm-objdump` path** — the executable used by the experimental llvm-objdump disassembly source.
- **Show offsets** — toggle offset gutters in the structured views.
- **Send usage statistics** — the analytics opt-out (subject to the IDE's statistics consent).
- **SBOM vulnerability matching** — see below.

The exact field set is the responsibility of `HexanaConfigurableProvider`; consult that class for the source of truth.

### SBOM vulnerability matching

For GraalVM Native Image binaries that carry an embedded CycloneDX SBOM (see [`features.md`](features.md#graalvm-native-image-011)), two checkboxes control vulnerability lookup. Both are **off by default**.

| Setting | Default | Effect |
|---|---|---|
| **Look up vulnerabilities** (`lookupVulnerabilities`) | `false` | Match SBOM components against a **locally downloaded** OSV database. The database is fetched once; the component list never leaves your machine. |
| **Also query osv.dev online** (`vulnerabilitiesOnline`) | `false` | Additionally query `api.osv.dev` over the network. **This sends the component coordinate list to osv.dev.** Enabled only when *Look up vulnerabilities* is on. |

When matching is on, the **SBOM** tab overlays known CVEs with CVSS severity, the fixed version, and an advisory link. See the [0.11 release notes](changelog-0.11.md#sbom-vulnerability-reachability).

## Settings → Build, Execution, Deployment → WASM Runtime

- **ID**: `wasm.runtime.settings`
- **Instance**: `WasmRuntimeConfigurable`
- **Title**: bundled key `wasm.runtime.settings.displayName`
- **Backing service**: `WasmRuntimeSettings` (application-level)

Configures runtimes for the WASM run/debug configurations covered in [`run-and-debug.md`](run-and-debug.md). Fields:

- **Default runtime** — Wasmtime, WAMR, GraalWasm, Node.js, or Browser. Used when a run configuration does not override it.
- **Wasmtime path** — absolute path to the `wasmtime` executable.
- **WAMR path** — absolute path to `iwasm` (or the WAMR binary you ship).
- **GraalVM home** — absolute path to a GraalVM installation that includes the GraalWasm component. Empty means use Hexana's built-in GraalVM image.
- **Node.js path** — absolute path to the `node` executable. Empty resolves `node` from `PATH` and common install locations. (Browser runs need no path — Hexana opens the IDE's configured browser; browser *debug* requires Chrome. See [`run-and-debug.md`](run-and-debug.md).)

These values persist into the IDE's options storage and survive restarts.

## Settings → Editor → Inspections → WebAssembly

Hexana contributes inspections in two language groups:

- **WIT** — five inspections (empty definition, world name uniqueness, missing semicolon, gate, use-declaration missing names). See [`wit-language.md`](wit-language.md).
- **WebAssembly** (Java group) — four inspections (unresolved export, export argument count mismatch, export argument type mismatch, unresolved import). See [`java-integration.md`](java-integration.md).

Each can be enabled, disabled, or have its severity raised/lowered like any other IDE inspection.

## Settings → Editor → Code Style → WIT

Contributed by `WitCodeStyleSettingsProvider`. Standard indent, tabs, and wrapping options.

## Settings → Tools → Server (MCP)

The platform MCP server lives in the bundled `com.intellij.mcpServer` plugin and exposes its own Settings page. Hexana adds tools to that server but does not introduce its own MCP settings page. See [`mcp-tools.md`](mcp-tools.md) for the tools Hexana contributes.

## Registry keys (experimental toggles)

Some Hexana behaviour is exposed only through the platform Registry — these are toggles for experimental code paths that don't yet warrant a dedicated Settings field.

Open the Registry from **Help → Find Action…** (`Cmd+Shift+A` on macOS, `Ctrl+Shift+A` on Linux / Windows), type `Registry…`, and filter for `hexana.`.

| Registry key | Default | Effect |
|---|---|---|
| `hexana.disassembly.backend.redline` | `false` | Use the Cranelift native disassembler backend instead of the bytecode AOT default. See [`disassembler-backends.md`](disassembler-backends.md). |
| `hexana.disassembly.perf.logging` | `false` | Emit `[disasm-perf]` timing lines to `idea.log` for every decoded chunk. Useful when comparing the two disassembler backends on a specific binary. |

Registry-key changes take effect immediately on the next operation that reads them (next disassembly panel open, next decode chunk). No IDE restart is required.

## Settings → Plugins → Marketplace → "Hexana"

Not a Hexana surface — it is the Plugin Manager — but a relevant entry point. Use it to enable, disable, update, or uninstall Hexana, and to read the listing description and "What's New" content for each release.
