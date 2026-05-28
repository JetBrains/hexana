---
title: Running and Debugging WebAssembly from Hexana for VS Code
description: How to run and debug .wasm modules through Wasmtime, WAMR, or GraalVM — core modules with auto-generated import stubs and Component Model binaries with dependency composition.
version: "0.2.0"
---

# Running and Debugging WebAssembly from Hexana for VS Code

Hexana ships **Run** and **Debug** buttons in the editor toolbar. You can invoke a module through one of three runtimes — **Wasmtime**, **WAMR**, or **GraalVM** — and (experimental) attach an `lldb`-backed debugger when running on Wasmtime or WAMR. Both core WebAssembly modules and Component Model binaries are supported, with different orchestration for each.

## Requirements

| Tool | Required for | Install |
|---|---|---|
| **[wasmtime](https://wasmtime.dev/)** | Wasmtime runtime + Wasmtime debug | `curl https://wasmtime.dev/install.sh -sSf \| bash`, or via your package manager. |
| **[WAMR](https://github.com/bytecodealliance/wasm-micro-runtime)** | WAMR runtime + WAMR debug | Build from source or grab a release binary. |
| **[GraalVM](https://www.graalvm.org/) + GraalWasm** | GraalVM runtime | Install GraalVM and the GraalWasm component; or let Hexana auto-detect. |
| **[wasm-tools](https://github.com/bytecodealliance/wasm-tools)** | Component Model composition (preferred) | `cargo install wasm-tools`. |
| **[wac](https://github.com/bytecodealliance/wac)** | Component Model composition (alternative) | `cargo install wac-cli`. |
| **LLVM 22.1+** (for debug only) | Wasmtime or WAMR debugging | Required because Hexana uses `lldb` 22.1+ wire-protocol features. |

At least one runtime must be on `PATH` (or pointed at via the corresponding setting, e.g. `hexana.wasmtimePath`). `wasm-tools` / `wac` must be on `PATH` only when running component-model binaries with unresolved imports.

## Core WebAssembly modules

### How the Run flow works

1. Click **Run** in the editor toolbar of an open `.wasm`.
2. A dialog opens listing all exported functions and a **runtime picker** (Wasmtime / WAMR / GraalVM). Pick an entry point and the runtime.
3. Optionally supply program arguments. Hexana parses the argument string using a shell-aware splitter (quoted strings are preserved as single arguments).
4. Hexana checks for unresolved imports:
   - **All imports resolved**: invokes the chosen runtime directly.
   - **Unresolved imports**: Hexana generates **import stubs** — functions that satisfy the import signatures and do nothing (or trap, depending on the kind) — and supplies them via the runtime's preload / linking mechanism.
5. A VS Code terminal opens with the runtime's command line, runs the export, and shows output.

The Run dialog is always shown so you can confirm runtime, export, and arguments before launch.

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
   - **Invocation** runs the chosen runtime on the composed component.
5. If imports remain unresolved, Hexana falls back to import-stub generation (same as core modules).

### Composition tool selection

| Tool | When Hexana picks it |
|---|---|
| `wasm-tools compose` | First choice. Mainstream Bytecode Alliance tool, broader support. |
| `wac plug` | Fallback when `wasm-tools` is not on `PATH`. |

You can install only one — Hexana adapts. If neither is installed and your binary has unresolved component imports, the Run flow stops with an actionable error pointing you at the install commands.

## Run dialog

The dialog has four sections:

- **Runtime** — dropdown of detected runtimes (Wasmtime / WAMR / GraalVM). Unavailable runtimes are greyed out with a tooltip explaining why.
- **Export** — dropdown of all exported functions (core modules) or the component's primary entry interface (components).
- **Arguments** — free-text field. Shell-style quoting is supported via Hexana's `splitShellArgs` Kotlin/JS utility. Backslash escapes work for spaces; single and double quotes group arguments.
- **Environment** (implicit) — Hexana passes through your current shell environment to the chosen runtime.

Click **Run** (or **Debug** to launch under the debugger — see below) to start; the dialog closes and a terminal opens with the live invocation.

## Debugging (experimental)

Click **Debug** instead of **Run** to launch the module under `lldb`. Supported on **Wasmtime** and **WAMR**; not yet on GraalVM.

- **Breakpoints** — set them in source files associated with the module (Hexana maps PCs to source via DWARF). Breakpoints inside nested modules of a Component Model binary are supported on Wasmtime only.
- **Stepping** — step over, into, and out; continue past hit breakpoints.
- **Variables** — local-variable inspection works for compilers that emit DWARF with reasonable location expressions (Rust, C/C++, Emscripten with `-g`).
- **Requirements** — LLVM 22.1 or newer must be on `PATH`, and the WASM binary must be compiled with debug info that `lldb` can interpret.

Known limitations:

- GraalVM debug is not yet wired.
- Some compilers' DWARF (especially aggressive-CGU Rust builds) produces source paths that need fuzzy matching; if a breakpoint does not bind, check the **Debug Console** for the path Hexana tried to resolve and file a tracker issue.

## Terminal output

Each run gets its own VS Code terminal named after the run target (export name or component path). Stderr and stdout are shown verbatim — Hexana does not parse or filter the runtime's output. Use the terminal's normal search and copy.

Killing the terminal kills the runtime process.

## Configuration

| Setting | Default | Effect |
|---|---|---|
| `hexana.wasmtimePath` | `""` (use PATH) | Absolute path to a specific Wasmtime executable. Useful for testing pre-release builds or pinning to a vendored copy. |
| `hexana.mcp.javaHome` | `""` (use JAVA_HOME / PATH) | Absolute path to a JDK 21+ home directory. Only relevant when the MCP server is enabled (used by AI tooling); not required for Run / Debug. |

There is no setting to override `wasm-tools`, `wac`, WAMR, or GraalVM paths — all must be on `PATH` (GraalVM is auto-detected from common install locations as well).

## What this version does not do

- **GraalVM debug is not yet wired.** Debug works on Wasmtime and WAMR only.
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
