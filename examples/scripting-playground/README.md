# Hexana Scripting Playground

A self-contained sample project for exploring the Hexana scripting API. It includes a
binary corpus, build scripts that reproduce every committed file, and ten annotated
scripts covering both the v1 API (runs on master) and the v2 slice API (requires
the `feature/scripting-v2-phase1` branch).

Total committed binary size: about 9.3 KB (7 files under 4 KB each; 1 MB budget is not
approached). The `many-functions.wasm` scale module (413 KB at N=15000) is not committed;
generate it locally with `build/gen-many-functions.sh`.

## Toolchain versions

| Tool | Version | Location on this machine |
|------|---------|--------------------------|
| wabt wat2wasm | 1.0.36 | /opt/homebrew/bin/wat2wasm |
| wasi-sdk | 27.0 (clang 20.1.8, wasm32-wasi) | /Users/user/wasi-sdk |
| wasm-tools | 1.245.1 | /Users/user/.cargo/bin/wasm-tools |

The build scripts accept override env vars: `WAT2WASM`, `WASI_SDK`, `WASM_TOOLS`.

## Corpus

| File | Size | Built by | Purpose |
|------|------|----------|---------|
| bin/hello.wasm | 276 bytes | build/build-hello.sh (wabt 1.0.36) | Hand-written WAT: 4 exports, 1 import (env.log), 1 data segment, name section |
| bin/app-debug.wasm | 3933 bytes | build/build-app.sh (wasi-sdk 27.0) | C reactor (3 files), 5 exports, embedded DWARF + name section |
| bin/app-release.wasm | 855 bytes | build/build-app.sh | Stripped version of app-debug: no DWARF, no name section |
| bin/app-release.debug.wasm | 3086 bytes | build/build-app.sh | DWARF sidecar only (llvm-objcopy --only-keep-debug) |
| bin/broken.wasm | 276 bytes | build/gen-broken.sh | hello.wasm with byte 0x14 -> 0xFF: fails wasm-tools validate |
| bin/component.wasm | 1112 bytes | build/build-component.sh (wasm-tools 1.245.1) | Component wrapping app-release.wasm |
| bin/many-functions.wasm | not committed | build/gen-many-functions.sh | 15000 functions; 413593 bytes at N=15000 |

## Regenerating

Run `build/all.sh` from any directory to rebuild all committed binaries:

```sh
bash examples/scripting-playground/build/all.sh
```

For the scale module at N=15000 (not committed; gitignored):

```sh
bash examples/scripting-playground/build/gen-many-functions.sh
# Output: bin/many-functions.wasm  413593 bytes  N=15000
# Measured: 0.8 seconds total (1 s WAT generation + under 1 s wat2wasm compile)
```

Reproducibility: all committed binaries are byte-for-byte reproducible with the toolchain
versions above. The app-* binaries embed `DW_AT_comp_dir = "."` in DWARF because the
build script `cd`s to the project root before invoking clang; running from a different cwd
without the script would change this field. See `bin/MANIFEST.md` for sha256 hashes and
details.

## Walkthrough part 1: v1 scripts (master branch)

These scripts use only the v1 API (`print`, `table`, `row`, `binary.*`, `open()`).
They run on the current master branch.

1. Open `bin/hello.wasm` in the IntelliJ IDEA Hexana editor.
2. Open the Script tab (Add Script). Start from the starter template or paste from
   `hexana-script/scripts/v1-overview.js`.
3. Run `v1-overview.js` against `hello.wasm`. Expected: a structure table (8 regions)
   and a tab list (9 tabs including Data, Exports, Imports, Functions, Custom, Problems,
   Top, Monos, Garbage).
4. Switch to `bin/app-debug.wasm`. Run `v1-strings.js`. Expected: a table of 63 strings
   found by the script's byte scan, led by the clang/wasi-sdk DWARF producer strings.
   Note: `binary.strings` is only filled by the native providers (ELF, Mach-O); the Wasm
   provider does not implement the strings capability in v1, so the script scans
   `binary.bytes` itself and says which path it took. On `hello.wasm` it finds 6 strings
   including the data segment greeting "Hello, Hexana!" at 0xbe.
5. Run `v1-open-sibling.js` with `app-debug.wasm` open. Expected: opens
   `app-release.wasm` from the same directory, prints a size comparison table
   (3933 bytes debug vs 855 bytes release, delta 3078 bytes).
6. Run `v1-slow.js` against any binary. Expected: prints the startup line, then hits the
   timeout after 3 seconds (CLI) or 60 seconds (IDE). Diagnostic code: `TIMEOUT`.
7. Run `v1-denied.js` against any binary. Expected: exit code 1, diagnostic code
   `SANDBOX_DENIED`, message "TypeError: Access to host class java.lang.System is not
   allowed or does not exist."

## Walkthrough part 2: v2 scripts (requires branch feature/scripting-v2-phase1)

These scripts use `hexana.defineSlice()`, `binary.functions()`, `binary.imports()`, and
`console.log/debug`. They degrade with a printed message on master.

1. Open `bin/app-debug.wasm`.
2. Add `large-unnamed-functions.js` as a script. Expected: a slice tab named
   "user:Unnamed Functions" with columns funcidx/name/size/offset. Since app-debug.wasm
   has no name section, all defined functions appear as "(unnamed)".
3. Edit the `minSize` param in the toolbar. The stale badge appears. Click Recompute.
   Undo the change; the badge clears.
4. Add `failing-slice.js`. Expected: first page loads. Scroll or trigger load-more.
   A problem row appears with "second page fails deliberately".
5. Add `imports-by-module.js` against `bin/hello.wasm` (which has an `env.log` import).
   Expected: one table row (module `env`, count 1, kind `func`). The `console.debug`
   progress lines appear in the Log sub-tab only, not in the table.

## Walkthrough part 3: scale (optional, requires gen-many-functions.sh)

1. Generate the scale module:
   ```sh
   bash examples/scripting-playground/build/gen-many-functions.sh
   ```
2. Open `bin/many-functions.wasm` in the IDE.
3. Run `large-unnamed-functions.js` (v2 branch). Expected: a paginated table with 7500
   unnamed functions (odd-indexed). Load-more loads subsequent pages. Note scrolling
   latency at 15000 functions total.

## Broken binary

Open `bin/broken.wasm` in the IDE. Hexana must parse it without crashing. The Problems
tab should show an error row. The byte at offset 0x14 (decimal 20) is 0xFF, which is an
invalid valtype in the Type section. `wasm-tools validate` rejects it with "function
params size is out of bounds (at offset 0x14)".

## Sidecar pair

Open `bin/app-release.wasm` with `bin/app-release.debug.wasm` in the same directory.
Hexana's sidecar discovery finds the `.debug.wasm` file next to the release binary and
attaches the DWARF data. The DWARF tab appears with function source-location information
even though the release binary itself has no debug sections.

Note: `wasm-tools validate` rejects `app-release.debug.wasm` because it has no function
or type sections (it is debug-only). This is expected; `wasm-tools print` parses it
correctly.

## QA checklist

- [ ] `build/all.sh` completes without errors
- [ ] `wasm-tools validate` passes for hello.wasm, app-debug.wasm, app-release.wasm, component.wasm
- [ ] `wasm-tools validate` fails for broken.wasm
- [ ] `wasm-tools validate` fails for app-release.debug.wasm (expected: no function sections)
- [ ] `wasm-tools print` succeeds for app-release.debug.wasm
- [ ] v1-overview.js runs on hello.wasm via CLI: exit 0, 8 structure regions, 9 tabs
- [ ] v1-overview.js runs on app-debug.wasm via CLI: exit 0, 15 regions, 7 tabs
- [ ] v1-strings.js runs on app-debug.wasm via CLI: exit 0, 63 strings via byte scan
- [ ] v1-open-sibling.js on app-debug.wasm via CLI: exit 0, prints size comparison
- [ ] v1-slow.js via CLI: exit 1, diagnostic code TIMEOUT within 3 seconds
- [ ] v1-denied.js via CLI: exit 1, diagnostic code SANDBOX_DENIED
- [ ] gen-many-functions.sh at N=15000: completes in under 5 seconds, output 413593 bytes
- [ ] git status shows only new files under examples/scripting-playground/, no modified tracked files
- [ ] bin/many-functions.wasm does NOT appear in git status (gitignored)
- [ ] v2 scripts on master each print "This script needs the Scripting v2 Phase 1 branch."
- [ ] async-trap.js on master: prints "sync output visible", exits cleanly (no diagnostic)
- [ ] Open broken.wasm in IDE: no crash, Problems tab shows error
- [ ] Open app-release.wasm with sidecar next to it: DWARF info visible in IDE

Estimated time: under 15 minutes for the v1 and CLI portions. v2 walkthrough requires
the `feature/scripting-v2-phase1` branch.
