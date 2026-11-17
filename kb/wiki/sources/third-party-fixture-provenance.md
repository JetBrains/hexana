# Third-party test fixture provenance (HEXANA-33, part B area D)

Where each third-party fixture came from and what licence it carries. The per-directory notice
files are the source of truth; this page records the findings that were not obvious from the
repository and how they were established.

Index files: `wasmParser/src/commonTest/resources/THIRD-PARTY-FIXTURES.md`,
`wasmParser/src/commonTest/resources/webImage/README.md`,
`wasmParser/src/commonTest/resources/spacewasm-corpus/README.md`,
`ui-driver-tests/testData/fixtures/README.md` (USD) and
`ui-driver-tests/testData/fixtures/zephyr-corpus/README.md`.

## How provenance was established

- Identity by hash first: download the candidate upstream artifact and compare SHA-256. This worked for
  `faustide.wasm` (= `libfaust-wasm.wasm` of `grame-cncm/faustwasm` commit `87c00631…`, Faust 2.81.10) and the
  SpaceWasm `_prebuilt/` binaries; it failed for Skiko, Perspective and the topheman plugins (local or
  development builds), so their versions stay UNVERIFIED.
- Strings inside binaries carry build paths and versions: `strings -a` (use `cat file | strings -a` for GC Wasm
  modules, `strings file` aborts on some). Examples: `25.0.1+8-LTS-jvmci-b01` in the Web Image modules,
  `pxrInternal_v0_26_5` in `usdviewweb`, `/home/runner/work/little-on-air/...` in the `loa-*` ELFs.
- GitHub's anonymous API is rate-limited (60/hour); `raw.githubusercontent.com`, `codeload.github.com` and
  Maven Central are not. The Skiko Maven Central jars can be scanned by hash with `skiko-js-wasm-runtime`.

## Findings that need attention

- `external/zmk/xiao-nrf52840-loa-*.elf` are not ZMK builds: they are the MIT-licensed Little On Air Zephyr app
  (`sayhiben/little-on-air`) on Zephyr v4.3.0. A third ELF under the same directory, licensed under Nordic-5-Clause, was removed.
- `spacewasm-corpus/_prebuilt/coremark-minimal.wasm` (CoreMark under the EEMBC Acceptable Use Agreement, built by
  `wasm3/wasm-coremark`, which has no licence file) was removed because its redistribution terms could not be cleared.
- `webImage/*.js.wasm` were built with an Oracle-branded JDK (`Java(TM) SE Runtime Environment`,
  `-LTS-jvmci-`); the GPL-2.0 + Classpath-exception decision covers the OpenJDK class library and the Web Image runtime
  sources, but Oracle GraalVM distribution terms for the toolchain are unverified.
- `usdviewweb.wasm.gz` is under the Tomorrow Open Source Technology License 1.0 (Apache-2.0 with a different
  trademark section), which has no SPDX identifier.
- `doc/exception-handling/legacy/Exceptions.md` was a byte-identical copy of the upstream
  `WebAssembly/exception-handling` `proposals/exception-handling/legacy/Exceptions.md` (last upstream change
  `e7c7c313d26f6b0fe8f1bda33cd6ab5e9edd838b`); it is now only a link.
