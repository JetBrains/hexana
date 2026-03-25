# AGENTS.md for `wasm-modules`

This directory contains Rust sources that are compiled into `.wasm` binaries and then embedded into the JVM-side `graalwasm/` modules.

## Source To Artifact Mapping

- `wasm-modules/wasmprinter_wrapper` produces `graalwasm/wasmprinter/src/main/resources/wasmprinter_wrapper.wasm`
- `wasm-modules/wat2wasm` produces `graalwasm/wat2wasm/src/main/resources/wat2wasm_full.wasm`

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

Both modules also rely on host callbacks provided under `env`.

## Practical Rules

- Do not edit `target/` output by hand.
- Do not leave regenerated `.wasm` files only in `wasm-modules/out`; copy the intended artifacts into `graalwasm/.../src/main/resources`.
- If the exported ABI changes, update the Kotlin wrapper code and the affected plugin tests together.
