# Large-module test fixtures

## usdviewweb.wasm.gz

A 50 MB memory64 + threads WebAssembly build of Pixar/Autodesk OpenUSD's
`usdviewweb`, used by `HexanaLargeWasmResponsivenessTest` as a real-world
large-module fixture (42k defined functions, huge element table, Emscripten
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

## Licence

Status legend: VERIFIED = checked against the upstream source named here;
UNVERIFIED = could not be established (the entry says what is needed).

- Licence of the artifact: Tomorrow Open Source Technology License 1.0 (TOST 1.0), the licence of
  OpenUSD. It is the Apache License 2.0 with a different Section 6 ("Trademarks"); it has no SPDX
  identifier (not on the SPDX list at `licenseListVersion` `8791eda`, checked 2026-11-12). It is not "Apache-2.0".
  Upstream text: <https://github.com/PixarAnimationStudios/OpenUSD/blob/dev/LICENSE.txt>
  (top "OpenUSD" section; identical to the same section in the fork at the commit above, compared line by line).
  Full text, copied unchanged from `autodesk-forks/USD` at `12a01e18da9218492b28712ff004b7cd23074dee`
  (`LICENSE.txt`, lines 1-181, retrieved 2026-11-12): [`usdviewweb.TOST-1.0.txt`](usdviewweb.TOST-1.0.txt).
  Section 4(d) of the licence requires reproducing the upstream `NOTICE`:
  [`usdviewweb.NOTICE.txt`](usdviewweb.NOTICE.txt) (same commit). VERIFIED.
- The module also statically links third-party code (MaterialX, OpenSubdiv, oneTBB, Dawn/Tint, glslang and
  others; the module contains `src/dawn/src/tint/...` paths). Their licence texts are in the rest of the same upstream file,
  copied unchanged as [`usdviewweb.THIRD-PARTY-LICENSES.txt`](usdviewweb.THIRD-PARTY-LICENSES.txt).
  Which of those components are really linked into this particular build was not checked: UNVERIFIED.
- Version recorded inside the artifact: OpenUSD namespace `pxrInternal_v0_26_5` (OpenUSD 0.26.5). The build paths in the
  module (`.../USD/build-wasm64/...` under a local user directory) show it was built locally, not downloaded.

The artifact is included here solely as a test input.
