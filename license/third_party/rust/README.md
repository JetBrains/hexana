# Rust crate licence texts

Upstream licence files of every Rust crate that is linked into an embedded wasm module built from
`wasm-modules/`, kept as input for the third-party notices generator.

- `<crate>-<version>/` holds the files matching `LICENSE*`, `COPYING*`, `NOTICE*`, and `UNLICENSE*` exactly as
  shipped in that crate's published source (copied byte-for-byte, never edited).
- `crates.json` lists every crate (sorted by name, then version) with its source, the SPDX expression from its
  `Cargo.toml` `license` field (verbatim), its repository, the wasm modules that use it, and the licence files that
  were found. A crate that ships no licence file has `"files": []`; no text is invented for it.

## Scope

The crate set is the normal (non-build, non-dev, non-proc-macro) dependency closure per module:

| Module | Target |
| --- | --- |
| `wasmprinter_wrapper` | `wasm32-unknown-unknown` |
| `wat2wasm` | `wasm32-unknown-unknown` |
| `disassembler` | `wasm32-unknown-unknown` |
| `webapp_server` | `wasm32-wasip2` |

`webapp_server` is a developer-only experiment (`:webapp:buildComponent`) and is not part of the shipped webapp; it is
still listed as a separate module entry.

## How it was generated

Generated on 2026-11-12 from the committed `wasm-modules/*/Cargo.lock` files, using the nightly toolchain of the
`hexana-wasm-modules:build` image (`wasm-modules/Dockerfile`, based on `rust:1-bookworm`; cargo 1.102.0-nightly).

For each module `<m>` with target `<t>` (see the table above), run from the repository root:

```bash
docker run --rm --entrypoint bash --user "$(id -u):$(id -g)" -e HOME=/tmp -e CARGO_HOME=/tmp/cargo \
  -v "$PWD/wasm-modules":/work:ro -v /tmp/hx-cargo:/tmp/cargo -v /tmp/hx-vendor:/vendor -v /tmp/hx-tree:/tree \
  -w /work hexana-wasm-modules:build -c '
    cd <m>
    cargo tree --target <t> -e normal --prefix none --locked -f "{p}|{l}|{r}" | sort -u > /tree/<m>.txt
    cargo vendor --locked --versioned-dirs /vendor/<m>
  '
```

`/tmp/hx-cargo`, `/tmp/hx-vendor`, and `/tmp/hx-tree` are scratch directories outside the repository. A small script
then read the crate set from `/tree/<m>.txt`, copied the matching licence files from `/vendor/<m>/<crate>-<version>/`
(asserting that crates shared by several modules have identical files), and wrote the directories and `crates.json`.
The `wast`, `wat`, and `wasm-encoder` crates come from the `bytecodealliance/wasm-tools` git checkout pinned in
`wasm-modules/wat2wasm/Cargo.toml` (revision `c463da0e0a6310f5c33173164d8b745164bc333a`).

## Maintenance

Regenerate this directory whenever a `wasm-modules/*/Cargo.lock` changes, in the same change as the lock update.

## Texts added by hand

Seven crates ship no licence file in their published source. Their `crates.json` entries point at texts taken from
elsewhere, and the entry's `note` says where:

- `wasmparser`, `wasmprinter`, `wast`, `wat`, `wasm-encoder`: the repository-root `LICENSE-APACHE`,
  `LICENSE-Apache-2.0_WITH_LLVM-exception` and `LICENSE-MIT` of <https://github.com/bytecodealliance/wasm-tools>
  (identical at tag `v1.241.2` and at revision `c463da0e0a6310f5c33173164d8b745164bc333a`), kept in `wasm-tools/`.
- `riscv-decode` and `yaxpeax-arm`: neither the crate nor its upstream repository ships a licence file, so the entries
  use the canonical texts in `../licenses/` (`Apache-2.0.txt`, and the SPDX `0BSD.txt` template) and are marked
  `UNVERIFIED`.
