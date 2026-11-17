# Third-party fixtures in this directory

Index of the loose third-party fixtures that sit directly in
`wasmParser/src/commonTest/resources/` plus pointers to the sub-directories that
have their own notice files. Every file here is a test input only. Licence texts
are stored once in [`licenses/`](licenses/).

Status legend: VERIFIED = checked against the upstream source named in the
entry; UNVERIFIED = could not be established from repository evidence or from
the network (the entry says what is needed). Retrieval date of every upstream
file referenced below: 2026-11-12.

Sub-directories with their own notices:

- [`webImage/`](webImage/README.md): `javac.js.wasm`, `wasm-spring-shell.js.wasm`.
- [`spacewasm-corpus/`](spacewasm-corpus/README.md): NASA SpaceWasm test corpus.
- [`threads/`](threads/README.md): `shared-memory-import.wasm`, written for this repository.

## faustide.wasm

- What: the Faust compiler built as WebAssembly (`libfaust-wasm.wasm`, Faust 2.81.10;
  the module contains the string `Copyright (C) 2002-2025, GRAME` and
  `usage : faust [options] file1 [file2 ...]`).
- Origin: added to the repository with the message "wasm from https://faustide.grame.fr".
- Upstream: <https://github.com/grame-cncm/faustwasm> (`libfaust-wasm/libfaust-wasm.wasm`),
  commit `87c00631ba3a8e7d0ca58a795cf356d3b619133d` ("Updated with libfaust 2.81.10.
  Set version to 0.11.3.", 2025-10-03); compiler source:
  <https://github.com/grame-cncm/faust> tag `2.81.10`
  (tag object `1f62d97c7b925153bf6cc40c83a9c2023a7527af`).
- Identity check: the file is byte-identical to the upstream blob at that commit
  (SHA-256 `b9599e8bf510478d9b2e8805e6ad6bf0273d62d8a2ae278321c1aff064ac2b7f`, 3121412 bytes). VERIFIED.
- Licence: `LGPL-2.1-or-later`. VERIFIED from the upstream `COPYING.txt` files
  ("GNU Lesser General Public License ... either version 2.1 ... or (at your option)
  any later version"): [`licenses/Faust-2.81.10-COPYING.txt`](licenses/Faust-2.81.10-COPYING.txt)
  (Faust tag `2.81.10`), [`licenses/faustwasm-0.11.3-COPYING.txt`](licenses/faustwasm-0.11.3-COPYING.txt)
  (faustwasm commit above). Full LGPL-2.1 text from
  <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt>:
  [`licenses/LGPL-2.1.txt`](licenses/LGPL-2.1.txt).
- Not in the module: the Faust standard libraries (`libfaust-wasm.data`, separate upstream file) and
  an LLVM backend (the compiler reports "LLVM backend is not built").

## mpp-demo.wasm and 23735.mpp-demo.wasm

- What: Kotlin/Wasm (WasmGC) build of the Compose Multiplatform demo application
  `androidx.compose.mpp.demo` (module references `mpp-demo.wasm.map`).
  `23735.mpp-demo.wasm` (697 bytes) is a reduced module that keeps only a few
  imports of the full build (see `KotlinWasmDetectorTest`); it was added
  to the repository separately.
- Upstream: `compose/mpp/demo` in <https://github.com/JetBrains/compose-multiplatform-core>
  (branch `jb-main`, licence file `LICENSE.txt`, retrieved from `jb-main`).
  The code embedded in the module is that demo plus Compose Multiplatform, the Kotlin
  standard library, kotlinx.coroutines / serialization / datetime / collections-immutable
  and Skiko/Skia (the module contains `org.jetbrains.skiko` and `org.jetbrains.skia` symbols).
- Added to the repository with the message "Introduce mpp-demo.wasm" (2025-11-26).
- Version / commit of the build: UNVERIFIED (no version string inside the module and no
  record in the repository). Needed: the person who built it naming the compose-multiplatform-core commit or release.
- Licence: `Apache-2.0` for the Compose Multiplatform code (repository `LICENSE.txt` is the
  Apache License 2.0; Apache text: [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)). Kotlin stdlib and
  kotlinx.* were not individually checked: UNVERIFIED.
  Skia and the native libraries inside Skiko: see the Skiko entry.

## perspective.wasm

- What: the Perspective server engine (`perspective-server`, C++ compiled to Wasm; the
  module contains build paths of a local Perspective checkout (`.../perspective/rust/perspective-server/build/release/`)
  and statically linked Apache Arrow, protobuf, re2, Boost, ExprTk, lz4, zstd and Abseil sources).
- Origin: added to the repository with the message "wasm from https://perspective-dev.github.io".
- Upstream: <https://github.com/perspective-dev/perspective> (formerly `finos/perspective`).
- Version: UNVERIFIED. The module is a 10.8 MB debug-style build (not byte-identical to
  `@perspective-dev/server` 4.0.0 or 4.0.1 from npm, whose `dist/wasm/perspective-server.wasm` is
  about 2.2 MB); the demo site was the source and the version served on 2025-11-26 is unknown.
  Needed: the version served at <https://perspective-dev.github.io> at that date.
- Licence: `Apache-2.0` for Perspective (upstream `LICENSE.md`, Apache License 2.0,
  <https://github.com/perspective-dev/perspective/blob/master/LICENSE.md>; text in
  [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)). VERIFIED for Perspective's own code (`master`).
  The licences of the statically linked libraries listed above were NOT checked: UNVERIFIED.
  Needed: Perspective's third-party notices for the same version.

## Skiko (`skiko.wasm`, `11449.skiko.wasm`, `1381.skiko.wasm`, `maps/skiko.wasm`, `maps/skiko.wasm.map`, `skiko.wat`)

- What: `skiko.wasm` from JetBrains Skiko, the Kotlin bindings for Skia, in the Wasm runtime build
  used by Compose Multiplatform for web (`org.jetbrains.skia.*` exports, Skia, libpng 1.6.43,
  libjpeg-turbo, expat, FreeType, HarfBuzz, ICU (`icu_skiko`), wuffs).
- Upstream: <https://github.com/JetBrains/skiko>; Maven artifact `org.jetbrains.skiko:skiko-js-wasm-runtime`.
- Version: UNVERIFIED for all five files. None of them is byte-identical to any of the
  `skiko-js-wasm-runtime` jars published on Maven Central (versions 0.7.93 to 0.150.2 were compared by
  SHA-256). Evidence for the range: `skiko.wasm`, `11449.skiko.wasm`, `1381.skiko.wasm` embed
  libjpeg-turbo 2.1.4 and libpng 1.6.43, which matches the 0.7.93 to 0.8.19 line (`skiko.wasm` is 8305348 bytes,
  the 0.8.11 to 0.8.19 jars hold 8.29 MB), i.e. between 0.8.19 and 0.8.25 or a development build.
  `maps/skiko.wasm` embeds libjpeg-turbo 3.1.0, which appears from 0.9.24 onwards. Needed: the version or build
  number each file was taken from (`11449` and `1381` look like build/issue numbers).
- `skiko.wasm`: first committed on 2025-09-16. `maps/skiko.wasm.map` is its
  source map. `skiko.wat` is a text rendering of a Skiko module (derived file, treated as a
  derivative of `skiko.wasm`; the generating tool is not recorded).
- Licence: Skiko is `Apache-2.0` (repository `LICENSE`, VERIFIED on `master`), with a `NOTICE` for code adapted from
  the Android Open Source Project: [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) (copy of the Skiko `LICENSE`),
  [`licenses/skiko-NOTICE.txt`](licenses/skiko-NOTICE.txt). Skia is `BSD-3-Clause`
  ([`licenses/skia-BSD-3-Clause-LICENSE.txt`](licenses/skia-BSD-3-Clause-LICENSE.txt), from
  <https://github.com/google/skia/blob/main/LICENSE>, `main` branch, not the exact Skia milestone: UNVERIFIED for the
  milestone). The other native libraries named above (libpng, libjpeg-turbo, expat, FreeType, HarfBuzz, ICU, wuffs) have
  their own licences that were not collected for the exact versions: UNVERIFIED.

## wasm-component-experiment-2/

- What: `plugin_cat.wasm`, `plugin_echo.wasm`, `plugin_greet.wasm`, `plugin_ls.wasm`, `plugin_tee.wasm`,
  `plugin_weather.wasm`, `repl_logic_guest.wasm`, `repl-api.wasm`: WebAssembly component-model plugin and REPL-logic
  modules of a REPL with a sandboxed multi-language plugin system. The names and the embedded build path
  `webassembly-component-model-experiments-master` match the Rust crates of the upstream project
  (`crates/plugin-*`, `crates/repl-logic-guest`).
- Upstream: <https://github.com/topheman/webassembly-component-model-experiments> (author Christophe Rosset).
- Build: Rust with `wit-bindgen-rt 0.44.0` (strings inside the modules), built locally from a download of the
  `master` zip (the build path ends in `webassembly-component-model-experiments-master`), not downloaded from the project's
  site (the published `plugin_greet.wasm` / `repl_logic_guest.wasm` differ from these files byte-wise).
  Added to the repository with the message "check against sortIndex read (#77)" (2026-03-30).
- Commit of the upstream tree: UNVERIFIED (zip download, no commit hash recorded). Needed: the person who built them
  naming the date or commit.
- Licence: `MIT`, Copyright (C) 2025 Christophe Rosset. VERIFIED on `master` (GitHub licence file
  [`licenses/topheman-webassembly-component-model-experiments-MIT-LICENSE.txt`](licenses/topheman-webassembly-component-model-experiments-MIT-LICENSE.txt)).
  Whether the licence was MIT at the downloaded revision is not checked. The modules also embed the Rust standard
  library and `wit-bindgen-rt`; their licences were not checked: UNVERIFIED.

## Other loose files not reviewed here

`flow.wasm`, `globals.wasm`, `loop.wasm`, `string.wasm` (tiny modules) and the directories `c/`, `component/`, `go/`,
`kotlin/`, `rust/`, `snippets/`, `spec/`, `wat/` were outside the scope of this review and have no recorded provenance.
