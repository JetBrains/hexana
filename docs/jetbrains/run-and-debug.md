---
title: Running and Debugging WebAssembly Modules with Hexana (0.9)
description: How to run .wasm files from the IDE using Wasmtime, WAMR, or GraalVM, and how to use Hexana's experimental WASM debugger.
version: "0.9"
---

# Running and Debugging WebAssembly

Hexana 0.9 ships full IDE run-configuration support for WebAssembly modules and experimental debugging via Wasmtime and WAMR. This page covers configuration, runtime selection, proposal detection, and the debugger.

## Which runtimes does Hexana support?

| Runtime | Run | Debug | Notes |
|---|---|---|---|
| **Wasmtime** | Yes | Yes (experimental) | Debug requires LLVM 22.1 or newer; uses `lldb`. |
| **WAMR** (WebAssembly Micro Runtime) | Yes | Yes (experimental) | Same LLVM/`lldb` requirement for debug. Added in 0.9. |
| **GraalVM** (GraalWasm) | Yes | No | Run-only. Built-in GraalVM image bundled; custom install supported. |

## How do I configure a runtime?

1. Open **Settings → Build, Execution, Deployment → WASM Runtime**.
2. Choose a default runtime.
3. For each runtime you intend to use, provide the path:
   - **Wasmtime** — path to the `wasmtime` executable.
   - **WAMR** — path to `iwasm` (or your WAMR binary).
   - **GraalVM** — path to a GraalVM home directory that includes the GraalWasm component. Leave empty to use Hexana's built-in GraalVM image.

Hexana persists these in an application-level service (`WasmRuntimeSettings`).

## How do I run a .wasm file?

Three paths:

- **From the information bar** — open the `.wasm` in Hexana's editor and click **Run**. Hexana creates an ad-hoc run configuration on the fly via `WasmRunConfigurationProducer`.
- **From the Run menu** — `Run → Run…` → **+** → **WASM** → pick a `.wasm` and runtime.
- **From the Project tool window** — right-click a `.wasm` → **Run**.

The created configuration is of type `WasmRunConfigurationType` and is editable like any other IDE run configuration. Configuration options:

- Module file path (the `.wasm`).
- Runtime (override of the project default).
- Working directory.
- Program arguments passed to the WASM module.
- Environment variables (per the chosen runtime's conventions).

## How are WASM proposals handled?

Hexana 0.9 detects which WebAssembly proposals a module uses by inspecting its sections and instruction stream, and propagates the right flags to the runtime automatically. Detection covers:

- **Threads** (shared memory + atomics) — requires the runtime's `shared-memory` flag.
- **SIMD** (128-bit vector ops).
- **GC** (struct, array, ref types).
- **Tail Call**.
- **Exception Handling** (including Legacy EH `try`/`catch`/`throw`/`rethrow`/`delegate`/`catch_all`, added 0.8.2).
- **Reference Types** and **Bulk Memory** (rendered in WAT/MCP from 0.8.2).
- **Multi-Value** returns.
- **Component Model** (when the file is a component, not a core module).

Per-runtime flag translation lives in the runtime command-line builders (`WasmRunCommandLine`, `WamrCommandLineState`, `GraalWasmCommandLineState`). For Wasmtime this becomes `--wasm-features=<list>`. For WAMR, an equivalent flag set. For GraalVM, the WASM context options are set programmatically.

Hexana surfaces the detected proposals as **badges** in the editor's information bar so you can see which proposals are in play before running.

## Experimental debugging

Hexana 0.9 introduces an experimental WASM debugger registered via `WasmDebugRunner` and `xdebugger.breakpointType` (`WasmLineBreakpointType`).

### Requirements

- LLVM 22.1 or newer (for `lldb`).
- A debug-capable runtime — **Wasmtime** or **WAMR**.
- A `.wasm` file that includes DWARF debug information (typically a debug build from a producing toolchain — Rust `cargo build`, Emscripten `-g`, Clang `-g --target=wasm32`).

### Setting breakpoints

Open the WAT view for your `.wasm` and click in the gutter on the line you want to break on. The breakpoint is stored as a `WasmBreakpointProperties` and back-mapped to a source line via DWARF when the debug session starts.

### Running a debug session

Click **Debug** in the information bar, or use the existing **Debug** button on any `WasmRunConfiguration`. Under the hood:

1. `WasmDebugRunner` chooses the right command-line state (`WamrDebugCommandLineState` for WAMR; the Wasmtime debug command-line for Wasmtime).
2. `WasmDwarfInjector` resolves DWARF sections in the binary and feeds them to `lldb`.
3. `LldbCommunicator` brokers messages between the IDE's `XDebugProcess` (`WamrDebugProcess` or `WasmDebugProcess`) and the `lldb` instance.
4. `WasmBreakpointHandler` registers, removes, and updates breakpoints during the session.
5. `WasmDebuggerEvaluator` handles expression evaluation in the Variables / Watches panel.

### What works in 0.9

- Setting and clearing breakpoints on WAT lines.
- Step-over / step-into / step-out.
- Inspecting local variables when DWARF maps them.
- Pause and resume.

### Limitations in 0.9

- The debugger is **experimental** — expect rough edges, especially around step-into across host-function boundaries.
- GraalVM does not yet support the debug runner.
- Source-line mapping requires DWARF; modules built without debug info cannot be source-stepped.
- Conditional breakpoints and watchpoints are not yet wired.

## How do I run on Windows?

Run configurations on Windows were fixed in 0.8.2. As of 0.9 they work the same way as on macOS and Linux: install the runtime, point Hexana at it in Settings, click **Run**.

## How does the run configuration find its dependencies?

`WasmDependencyResolver` resolves any sibling modules referenced by imports (e.g. a Component-Model component that imports from a peer module) and stages them into the working directory so the runtime can resolve them at instantiation time. This is most relevant for component-model files; for plain core modules it is a no-op.

## See also

- [`features.md`](features.md) — full feature reference.
- [`troubleshooting.md`](troubleshooting.md) — runtime issues, debug-session failures.
- [`settings.md`](settings.md) — the WASM Runtime settings page.
