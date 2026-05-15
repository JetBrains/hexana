# AGENTS.md for `wasm-modules`

This directory contains Rust sources that are compiled into `.wasm` binaries and then embedded into the JVM-side `graalwasm/` modules.

## Source To Artifact Mapping

- `wasm-modules/wasmprinter_wrapper` produces `graalwasm/wasmprinter/src/main/resources/wasmprinter_wrapper.wasm`
- `wasm-modules/wat2wasm` produces `graalwasm/wat2wasm/src/main/resources/wat2wasm_full.wasm`
- `wasm-modules/disassembler` produces `disassembly/capstone-wasm/src/commonMain/resources/disassembler.wasm` (KMP-shared resource; consumed by `disassembly/capstone-wasm` via Chicory on JVM and host WebAssembly on js/wasmJs; see `wasm-modules/disassembler/README.md` for the export contract)

If you change the Rust source, update the embedded resource in `graalwasm/` in the same change.

## Build Paths

Preferred reproducible path:

- Use the Docker flow from `wasm-modules/README.md`

The repo already documents:

- `docker build -t hexana-wasm-modules:build wasm-modules`
- `docker run ... hexana-wasm-modules:build`

There is also a local nightly-Rust flow documented in `wasm-modules/wasmprinter_wrapper/README.md`.

## Contracts With JVM Wrappers

The Kotlin wrappers in `graalwasm/` expect stable exported functions and memory names.

Current expectations:

- `graalwasm/wasmprinter/.../WasmPrinter.kt` expects:
  - export `memory`
  - export `alloc_input`
  - export `print_wat`
- `graalwasm/wat2wasm/.../Wat2Wasm.kt` expects:
  - export `memory`
  - export `alloc_input`
  - export `wat2wasm`
- `disassembly/capstone-wasm/.../CapstoneWasmModule.*.kt` expects:
  - export `memory`
  - export `alloc`, `dealloc`, `free_result`
  - exports `decode_x86`, `decode_x86_64`, `decode_arm`, `decode_arm64`, `decode_riscv32`, `decode_riscv64`

Both wasmprinter and wat2wasm rely on host callbacks under `env`; the
disassembler module has no host imports.

## Practical Rules

- Do not edit `target/` output by hand.
- Do not leave regenerated `.wasm` files only in `wasm-modules/out`; copy the intended artifacts into `graalwasm/.../src/main/resources`.
- If the exported ABI changes, update the Kotlin wrapper code and the affected plugin tests together.
