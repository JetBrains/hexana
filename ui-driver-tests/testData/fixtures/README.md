# Large-module test fixtures

## usdviewweb.wasm.gz

A 50 MB memory64 + threads WebAssembly build of Pixar/Autodesk OpenUSD's
`usdviewweb`, used by `HexanaLargeWasmResponsivenessTest` as a real-world
large-module fixture (~74k call-graph nodes, huge element table, Emscripten
C++ conventions). The test decompresses it to `build/fixtures/` on first use;
override with the `HEXANA_LARGE_WASM` environment variable to test another
module without touching this file.

Provenance (reproducible):

- Source: https://github.com/autodesk-forks/USD, branch `adsk/feature/wasm`,
  commit `12a01e1` (Merge pull request #2318).
- Build: `python build_scripts/build_usd.py --build-target wasm64 <dir>` with
  Emscripten 4.0.16, plus `-s INITIAL_MEMORY=64MB` added to the `PXR_WASM64`
  branch of `pxr/usdImaging/bin/usdviewweb/CMakeLists.txt` (the stock branch
  under-allocates and fails to link).
- sha256 (decompressed):
  `0007c3000987f9e88202a624f73ab94863f14f963bec4aea956dd00a0c5fda00`

OpenUSD and its statically linked dependencies (MaterialX, OpenSubdiv, oneTBB,
Dawn/Tint, glslang) are distributed under Apache-2.0-style licenses; see
LICENSE.txt and NOTICE.txt in the source repository. The artifact is included
here solely as a test input.