# SpaceWasm test corpus, compiled to .wasm

Source: https://github.com/nasa/spacewasm @ b109559 (2026-08-29)

Layout: one folder per .wast file from the repo (tests/core, tests/custom-page-sizes,
tests/regression). Each folder holds the .wasm modules extracted from that .wast plus
the wast2json/wasm-tools .json manifest (maps modules to spec assertions).

Tools:
1. wabt 1.0.34 (wast2json) for core and regression suites
2. wasm-tools 1.258.0 (json-from-wast) for custom-page-sizes/* and decode-errors,
   which wabt cannot parse (custom-page-sizes proposal / intentionally malformed binaries)

Notes:
1. _prebuilt/ holds ready-made binaries from the repo: the spacewasi WASI tests
2. decode-errors/ modules are INTENTIONALLY malformed (their regression suite for decoder errors), good for testing Hexana error handling
3. custom-page-sizes/ modules use non-64KiB page sizes (proposal SpaceWasm supports)

## Provenance and licences

Status legend: VERIFIED = checked against the upstream source named here;
UNVERIFIED = could not be established (the entry says what is needed). Upstream
files below were retrieved on 2026-11-12. Licence texts are in [`licenses/`](licenses/).

- Upstream: <https://github.com/nasa/spacewasm>, commit `b109559` (the README header gives
  2026-08-29; the full commit hash was not recorded: UNVERIFIED, the abbreviation resolves uniquely on
  GitHub), copyright "(c) 2026 California Institute of Technology (Caltech)", developed at NASA JPL.
  The project is `Apache-2.0` (its `LICENSE` is copied unchanged to
  [`licenses/spacewasm-Apache-2.0-LICENSE.txt`](licenses/spacewasm-Apache-2.0-LICENSE.txt); VERIFIED at `b109559`).
  Its `NOTICE` is copied to [`licenses/spacewasm-NOTICE.txt`](licenses/spacewasm-NOTICE.txt); it lists the
  third-party test material used below.
- The folder names map to files in the SpaceWasm repository (VERIFIED by comparing names at `b109559`):
  - 75 folders correspond to `tests/core/*.wast`. SpaceWasm states that these are the WebAssembly 1.0
    (MVP) spec tests curated in <https://github.com/WasmEdge/wasmedge-spectest> (README, "Testing" section).
    Its `NOTICE` credits WasmEdge (`MIT`, Copyright (c) 2025 WasmEdge Runtime,
    [`licenses/wasmedge-spectest-MIT-LICENSE.txt`](licenses/wasmedge-spectest-MIT-LICENSE.txt),
    from <https://github.com/WasmEdge/wasmedge-spectest/blob/master/LICENSE>) and the WebAssembly testsuite
    (`Apache-2.0`, [`licenses/WebAssembly-testsuite-Apache-2.0-LICENSE.txt`](licenses/WebAssembly-testsuite-Apache-2.0-LICENSE.txt),
    from <https://github.com/WebAssembly/testsuite/blob/main/LICENSE>). Which individual file comes through which
    of the two routes is UNVERIFIED (the `.wast` files differ byte-wise from the current testsuite `main`; needed: the
    SpaceWasm authors or the spec revision they imported).
  - `custom-page-sizes`, `custom-page-sizes-invalid`, `memory_max`, `memory_max_32` correspond to
    `tests/custom-page-sizes/*.wast` (custom page sizes proposal tests). Whether they are copied from the proposal
    repository (<https://github.com/WebAssembly/custom-page-sizes>, whose top-level licence file says
    `test/` is Apache-2.0) or written by SpaceWasm is UNVERIFIED; both routes are Apache-2.0 compatible.
  - 14 folders (`decode-errors`, `extern_*`, `host_*`, `host_func_invalid_*`, `pause-resume`, `start_stack_overflow`,
    `table_ref_not_unique`, `zero_page_memory_grow`) correspond to `tests/regression/*.wast`, authored by the SpaceWasm
    project (`decode-errors.wast` says its modules are "hand-encoded"), hence `Apache-2.0` (Caltech).
- `_prebuilt/`: byte-identical (SHA-256 compared) to files at `b109559`: `argv.wasm`, `argv0.wasm`, `env.wasm`, `fs.wasm`,
  `hello_universe.wasm`, `rc.wasm` come from `crates/spacewasi/tests/wasm/` with their `.c` sources and `dummyfile`
  (`Apache-2.0`, SpaceWasm); VERIFIED.
- Upstream also ships `crates/spacewasm_std/benches/coremark-minimal.wasm` (CoreMark 1.01 under the EEMBC Acceptable
  Use Agreement, from `wasm3/wasm-coremark`, which has no licence file). It is deliberately NOT included here.
- The `*.json` manifests and `.wasm` / `.wat` modules in the per-test folders are tool output (wabt 1.0.34
  `wast2json`, wasm-tools 1.258.0 `json-from-wast`, as stated at the top of this file) of the `.wast` sources above and
  are covered by the licence of those sources.
