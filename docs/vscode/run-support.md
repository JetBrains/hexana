---
title: Running WebAssembly from Hexana for VS Code (0.0.2)
description: How to run .wasm modules through Wasmtime — core modules with auto-generated import stubs and Component Model binaries with dependency composition.
version: "0.0.2"
---

# Running WebAssembly from Hexana for VS Code

Hexana 0.0.2 ships a Run button in the editor toolbar that invokes **Wasmtime** in a VS Code terminal. Both core WebAssembly modules and Component Model binaries are supported, with different orchestration for each.

## Requirements

| Tool | Required for | Install |
|---|---|---|
| **[wasmtime](https://wasmtime.dev/)** | All run scenarios | `curl https://wasmtime.dev/install.sh -sSf \| bash`, or via your package manager. |
| **[wasm-tools](https://github.com/bytecodealliance/wasm-tools)** | Component Model composition (preferred) | `cargo install wasm-tools`. |
| **[wac](https://github.com/bytecodealliance/wac)** | Component Model composition (alternative) | `cargo install wac-cli`. |

Wasmtime must be on `PATH`, or you must set `hexana.wasmtimePath` in your VS Code settings. `wasm-tools` / `wac` must be on `PATH` only when running component-model binaries with unresolved imports.

## Core WebAssembly modules

### How the Run flow works

1. Click **Run** in the editor toolbar of an open `.wasm`.
2. A dialog opens listing all exported functions. Pick one as the entry point.
3. Optionally supply program arguments. Hexana parses the argument string using a shell-aware splitter (quoted strings are preserved as single arguments).
4. Hexana checks for unresolved imports:
   - **All imports resolved**: invokes Wasmtime directly.
   - **Unresolved imports**: Hexana generates **import stubs** — functions that satisfy the import signatures and do nothing (or trap, depending on the kind) — and supplies them via Wasmtime's `--preload` mechanism.
5. A VS Code terminal opens with the Wasmtime command line, runs the export, and shows output.

### Generated import stubs

For an unresolved import like `env.print_i32(i32) -> ()`, Hexana synthesises a minimal stub component that re-exports a noop with the matching signature, then preloads that stub at Wasmtime invocation. The user-facing effect: the run succeeds even when the WASM was compiled against a host the user hasn't fully wired up.

This is **most useful for inspection runs** — confirm that a function is callable and see what it computes from constant inputs — and **least useful when the host functions are part of the program's semantics** (e.g. an export whose result depends on what `env.read_file` returns).

## Component Model binaries

### How the Run flow works

1. Click **Run** on a component binary.
2. Hexana inspects the component's imports and tries to satisfy them by scanning the workspace.
3. **Dependency resolution** (see [`component-model.md`](component-model.md) for the full algorithm) walks every `.wasm` in the workspace, identifies components that export the interfaces the target imports, and chains them transitively.
4. If all imports are resolved through workspace components:
   - **Composition** runs `wasm-tools compose` (or `wac plug` if `wasm-tools` is unavailable) to produce a single self-contained component.
   - **Invocation** runs Wasmtime on the composed component.
5. If imports remain unresolved, Hexana falls back to import-stub generation (same as core modules).

### Composition tool selection

| Tool | When Hexana picks it |
|---|---|
| `wasm-tools compose` | First choice. Mainstream Bytecode Alliance tool, broader support. |
| `wac plug` | Fallback when `wasm-tools` is not on `PATH`. |

You can install only one — Hexana adapts. If neither is installed and your binary has unresolved component imports, the Run flow stops with an actionable error pointing you at the install commands.

## Run dialog

The dialog has three sections:

- **Export** — dropdown of all exported functions (core modules) or the component's primary entry interface (components).
- **Arguments** — free-text field. Shell-style quoting is supported via Hexana's `splitShellArgs` Kotlin/JS utility. Backslash escapes work for spaces; single and double quotes group arguments.
- **Environment** (implicit) — Hexana passes through your current shell environment to Wasmtime.

Click **Run** to start; the dialog closes and a terminal opens with the live invocation.

## Terminal output

Each run gets its own VS Code terminal named after the run target (export name or component path). Stderr and stdout are shown verbatim — Hexana does not parse or filter Wasmtime's output. Use the terminal's normal search and copy.

Killing the terminal kills the Wasmtime process.

## Configuration

| Setting | Default | Effect |
|---|---|---|
| `hexana.wasmtimePath` | `""` (use PATH) | Absolute path to a specific Wasmtime executable. Useful for testing pre-release builds or pinning to a vendored copy. |

There is no setting to override `wasm-tools` or `wac` paths in 0.0.2 — both must be on `PATH`.

## What this version does not do

- **No debugging.** The JetBrains plugin supports experimental debugging since 0.9 (Wasmtime + WAMR + LLDB). The VS Code extension does not in 0.0.2.
- **No WAMR or GraalVM runtimes.** Wasmtime only.
- **No proposal-flag selection UI.** Hexana detects which proposals the binary uses and passes the appropriate `--wasm-features` flags automatically, but there is no UI to override.
- **No run-configuration persistence.** Each Run is ad-hoc; arguments are not saved between runs. The dialog remembers the last set within the editor session but discards them on close.
- **No host-function injection.** You cannot supply your own implementations for unresolved imports; you get auto-generated stubs or nothing.

## Troubleshooting

If **Run** is greyed out:

- Check Wasmtime is installed and on `PATH`, or that `hexana.wasmtimePath` points at the executable.
- Reload the editor (`Cmd/Ctrl+Shift+P` → **Developer: Reload Window**) so the extension re-detects.

If Run fails on a component binary with "unable to compose":

- Install `wasm-tools` (`cargo install wasm-tools`).
- Confirm all required dependency `.wasm` files are present in the workspace.
- See [`component-model.md#dependency-resolution`](component-model.md) for the resolution algorithm.

See [`troubleshooting.md`](troubleshooting.md) for the full list.

## See also

- [`features.md`](features.md), [`component-model.md`](component-model.md), [`settings.md`](settings.md), [`troubleshooting.md`](troubleshooting.md).
- The Bytecode Alliance docs for [wasmtime](https://docs.wasmtime.dev/), [wasm-tools](https://github.com/bytecodealliance/wasm-tools), and [wac](https://github.com/bytecodealliance/wac).
