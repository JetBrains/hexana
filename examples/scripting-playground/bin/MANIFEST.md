# Binary Manifest

Ground-truth facts for the committed binaries. All measurements taken from
actual build output on 2026-09-18 using the toolchain versions below.

## Toolchain

| Tool | Version | Path |
|------|---------|------|
| wabt wat2wasm | 1.0.36 | /opt/homebrew/bin/wat2wasm |
| wasi-sdk clang | 20.1.8 (wasm32-wasi) | /Users/user/wasi-sdk/bin/clang |
| wasi-sdk llvm-objcopy | from wasi-sdk 27.0 | /Users/user/wasi-sdk/bin/llvm-objcopy |
| wasm-tools | 1.245.1 | /Users/user/.cargo/bin/wasm-tools |

## Committed binaries

### hello.wasm

| Field | Value |
|-------|-------|
| Size | 276 bytes |
| SHA-256 | 40d6319d8cb5bfeaab4294de861a5817f4224c00d3405a264cfacb434b92f50a |
| Source | src/hello.wat |
| Build script | build/build-hello.sh |
| Build command | wat2wasm src/hello.wat -o bin/hello.wasm --debug-names |
| Tool | wabt wat2wasm 1.0.36 |

Ground-truth facts (from wasm-tools objdump and print):

- Exports: `memory` (memory), `greet` (func 1), `add` (func 2), `fib` (func 3), `strlen` (func 4)
- Imports: 1 func (`env.log`, funcidx 0)
- Defined functions: 4 (code section count)
- Total functions: 5 (1 import + 4 defined)
- Data segments: 1 (at offset 0, content `"Hello, Hexana!"`, 14 bytes + 6-byte header = 20 bytes section)
- Custom sections: `name` (65 bytes, names all 4 defined functions + import)
- Name section: present
- DWARF custom sections: none
- Validation: passes wasm-tools validate
- Reproducibility: deterministic (wabt is deterministic for WAT input)

### app-debug.wasm

| Field | Value |
|-------|-------|
| Size | 3933 bytes |
| SHA-256 | 8f3e1fe46156c64cd3595fe221b1118905bcc1c6038fdf0cbc70bae5b9f1afb9 |
| Source | src/app/main.c, src/app/fib.c, src/app/strings.c |
| Build script | build/build-app.sh |
| Build command | clang -target wasm32-wasi -mexec-model=reactor -O1 -g -ffile-prefix-map=ROOT=. -Wl,--export=greeting_len,--export=greeting_byte,--export=compute_fib,--no-entry src/app/main.c src/app/fib.c src/app/strings.c -o bin/app-debug.wasm |
| Tool | wasi-sdk 27.0 clang 20.1.8 |

Ground-truth facts:

- Exports: `memory` (memory), `_initialize` (func 1), `greeting_len` (func 2), `compute_fib` (func 3), `greeting_byte` (func 4)
- Imports: 0 (wasi-libc is statically linked; reactor model; no wasi imports called by these functions)
- Defined functions: 8 (code section count, includes wasi-libc reactor init and musl strlen)
- Total functions: 8
- Data segments: 1 (strings: "Hello from Hexana scripting playground" + "app-debug: wasm32-wasi reactor built with wasi-sdk 27.0")
- Custom sections: `.debug_abbrev`, `.debug_info`, `.debug_str`, `.debug_line`, `.debug_loc`, `.debug_ranges`, `producers`, `target_features`
- Name section: none (stripped by LLVM linker at O1; names in DWARF .debug_str only)
- DWARF custom sections: present (.debug_abbrev, .debug_info, .debug_str, .debug_line, .debug_loc, .debug_ranges)
- Validation: passes wasm-tools validate
- Reproducibility: deterministic when built with `cd examples/scripting-playground && clang ...` (so DW_AT_comp_dir resolves to `.`). Building from a different cwd changes DW_AT_comp_dir and breaks reproducibility. The build script enforces `cd $ROOT` before invoking clang.
- Machine-specific: the DWARF line table and .debug_str still embed the wasi-sdk sysroot install path (here `/Users/user/wasi-sdk/share/wasi-sysroot/...`), which -ffile-prefix-map=ROOT=. does not cover. Rebuilds are byte-identical on the same machine; a different wasi-sdk location produces different bytes. Extend the prefix map with the sysroot path to reproduce elsewhere.

### app-release.wasm

| Field | Value |
|-------|-------|
| Size | 855 bytes |
| SHA-256 | 1c4f6f03d834b76a7901406b5c235af87fc95dd837e4b4e11af3f7ca7793fd32 |
| Source | derived from app-debug.wasm |
| Build script | build/build-app.sh |
| Build command | llvm-objcopy --strip-debug bin/app-debug.wasm bin/app-release.wasm |
| Tool | wasi-sdk 27.0 llvm-objcopy |

Ground-truth facts:

- Exports: same as app-debug.wasm (memory, _initialize, greeting_len, compute_fib, greeting_byte)
- Imports: 0
- Defined functions: 8
- Total functions: 8
- Data segments: 1
- Custom sections: `producers`, `target_features` (DWARF and name sections stripped)
- Name section: none
- DWARF custom sections: none (stripped)
- Validation: passes wasm-tools validate
- Reproducibility: deterministic (derived from deterministic app-debug.wasm)

### app-release.debug.wasm

| Field | Value |
|-------|-------|
| Size | 3086 bytes |
| SHA-256 | ea5db77cfc730294d6df074c34e89fc4bf7503f1e696add2b3421954e75ddcf0 |
| Source | derived from app-debug.wasm |
| Build script | build/build-app.sh |
| Build command | llvm-objcopy --only-keep-debug bin/app-debug.wasm bin/app-release.debug.wasm |
| Tool | wasi-sdk 27.0 llvm-objcopy |

Ground-truth facts:

- Contains only DWARF custom sections (no types/functions/code/exports sections)
- Custom sections: `.debug_abbrev`, `.debug_info`, `.debug_str`, `.debug_line`, `.debug_loc`, `.debug_ranges`
- Validation: wasm-tools validate rejects this file (no function or type sections; debug-only wasm is not a valid core module). Use `wasm-tools print` for parse sanity check -- it succeeds.
- Intended use: sidecar DWARF file placed next to app-release.wasm for Hexana sidecar discovery

### broken.wasm

| Field | Value |
|-------|-------|
| Size | 276 bytes |
| SHA-256 | 239a435c808a61f5263bcd19b63941bdd613722d4a9ab21369ee4aa8e25e8527 |
| Source | derived from hello.wasm by byte mutation |
| Build script | build/gen-broken.sh |
| Build command | cp hello.wasm broken.wasm; printf '\xff' | dd of=broken.wasm bs=1 seek=20 count=1 conv=notrunc |

Ground-truth facts:

- Byte at offset 20 (0x14) overwritten with 0xFF (was 0x7F, a valid i32 valtype; 0xFF is invalid)
- Original byte is inside the Type section's second func type definition, corrupting the param valtype
- Validation: fails wasm-tools validate with "function params size is out of bounds (at offset 0x14)"
- Intended use: error-recovery demo; Hexana must not crash on this input

### component.wasm

| Field | Value |
|-------|-------|
| Size | 1112 bytes |
| SHA-256 | 9ee864363e4a4d9a43120ccd52e1cf61b970606bc2f529cb752b50ae9703b1af |
| Source | derived from app-release.wasm |
| Build script | build/build-component.sh |
| Build command | wasm-tools component new bin/app-release.wasm -o bin/component.wasm |
| Tool | wasm-tools 1.245.1 |

Ground-truth facts:

- WebAssembly Component wrapping app-release.wasm (no WASI imports needed; no adapter required)
- Contains 2 core modules (module 0: app-release, module 1: generated shim), 2 core instances, component aliases
- Custom sections: `component-name`, `producers`
- Validation: passes wasm-tools validate
- Reproducibility: deterministic (derived from deterministic app-release.wasm)

## Not-committed binary: many-functions.wasm

Generated on demand by `build/gen-many-functions.sh`. Excluded by `bin/.gitignore`.

Recorded run at N=15000 (2026-09-18, wabt 1.0.36):

| Metric | Value |
|--------|-------|
| Generation time (WAT emit) | 1 second |
| Compile time (wat2wasm) | under 1 second |
| Total elapsed | 0.8 seconds (real) |
| Output size | 413,593 bytes |

Ground-truth facts (N=15000):

- Functions: 15000 defined (no imports)
- Named functions (even index): 7500 (funcidx 0, 2, 4, ... 14998 with $named_<i> id)
- Unnamed functions (odd index): 7500 (funcidx 1, 3, 5, ... 14999, no $id)
- Exports: 1 (`first`, func 0)
- Data segments: 1 (label string "many-functions scale demo")
- Body sizes: vary by index (1 + (i mod 8) additions of i32.const), giving bodies from 5 to 25 bytes
- Name section: present (wat2wasm emits name section for all $id-named functions)
- Validation: passes wasm-tools validate

## Nondeterminism notes

- `hello.wasm` and `component.wasm` are fully reproducible byte-for-byte.
- `app-debug.wasm`, `app-release.wasm`, `app-release.debug.wasm`: deterministic when the build script is invoked from any cwd, because the script `cd`s to the project root before invoking clang, ensuring `DW_AT_comp_dir` is always `.`. The wasi-sdk internal paths (`wasisdk://v27.0/...`) are embedded by wasi-libc itself and are version-stable.
- `broken.wasm`: derived from `hello.wasm`, so deterministic.
- `many-functions.wasm`: deterministic for a fixed N and wabt version.
