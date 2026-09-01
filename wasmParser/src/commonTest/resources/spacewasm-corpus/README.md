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
1. _prebuilt/ holds ready-made binaries from the repo: spacewasi WASI tests and coremark-minimal.wasm (benchmark)
2. decode-errors/ modules are INTENTIONALLY malformed (their regression suite for decoder errors), good for testing Hexana error handling
3. custom-page-sizes/ modules use non-64KiB page sizes (proposal SpaceWasm supports)
