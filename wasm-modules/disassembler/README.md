# disassembler — Hexana WASM disassembler backend

Pure-Rust disassembler compiled to `wasm32-unknown-unknown`. Consumed from
Kotlin Multiplatform via `disassembly/capstone-wasm`.

The crate name is `disassembler`; the produced artifact is
`disassembler.wasm`. The downstream KMP module is named `capstone-wasm`
because it provides a Capstone-shaped surface — the engine itself is
[yaxpeax](https://github.com/iximeow/yaxpeax) (pure Rust, no C toolchain).

## Architectures

| Status | Architecture | Engine                          |
|--------|--------------|---------------------------------|
| ✅      | x86_64       | `yaxpeax-x86` (long_mode)       |
| ✅      | x86 (i386)   | `yaxpeax-x86` (protected_mode)  |
| ✅      | ARM (A32)    | `yaxpeax-arm` (armv7)           |
| ✅      | ARM64        | `yaxpeax-arm` (armv8::a64)      |
| ✅      | RISC-V 32    | `riscv-decode`                  |
| ✅      | RISC-V 64    | `riscv-decode`                  |
| ✅      | Thumb / T32  | `yaxpeax-arm` (armv7_thumb)     |

## Build

Reproducible Docker flow from `wasm-modules/`:

```bash
docker build -t hexana-wasm-modules:build wasm-modules
docker run --rm -v "$PWD/wasm-modules:/src" -v "$PWD/wasm-modules/out:/out" \
  hexana-wasm-modules:build
```

Local nightly-Rust flow (matches `wasmprinter_wrapper/README.md`):

```bash
rustup toolchain install nightly --force
rustup +nightly target add wasm32-unknown-unknown
cargo build --target wasm32-unknown-unknown --release
```

Output: `target/wasm32-unknown-unknown/release/disassembler.wasm`.

## Export contract

All multi-byte integers are little-endian (WASM convention). Pointers are
`u32` linear-memory offsets. Strings are UTF-8 with explicit length (not
null-terminated).

### Memory management

```
memory                                          — exported linear memory
alloc(size: u32) -> u32                          — allocate a caller-owned buffer
dealloc(ptr: u32, size: u32)                     — free an alloc()'d buffer
free_result(ptr: u32)                            — free a decode_*-returned record
```

`alloc` returns the pointer to a writable region of exactly `size` bytes; the
caller is responsible for freeing it via `dealloc(ptr, size)` once decoding
finishes. The result records produced by `decode_*` are self-describing and
are freed via `free_result(ptr)` — do **not** use `dealloc` on them.

### Decode entry points

All decode entry points share the same signature:

```
decode_<arch>(
  bytes_ptr: u32,         — start of an alloc()'d byte window
  bytes_len: u32,         — length of that window in bytes
  offset:    u32,         — start decoding at bytes_ptr + offset
  base_va:   u64,         — virtual address that bytes_ptr maps to
) -> u32                  — pointer to a DecodeResult record, or 0 if offset >= bytes_len
```

Available entries:

| Export           | Arch                | Instruction width                        |
|------------------|---------------------|------------------------------------------|
| `decode_x86`     | i386 (protected)    | 1–15 bytes (variable)                    |
| `decode_x86_64`  | x86_64 (long mode)  | 1–15 bytes (variable)                    |
| `decode_arm`     | ARM A32             | 4 bytes                                  |
| `decode_arm64`   | AArch64             | 4 bytes                                  |
| `decode_thumb`   | Thumb / T32         | 2 bytes (narrow) or 4 bytes (wide T2)    |
| `decode_riscv32` | RV32                | 2 bytes (RVC) or 4 bytes (base)          |
| `decode_riscv64` | RV64                | 2 bytes (RVC) or 4 bytes (base)          |

Each call decodes **one** instruction. Iterate by advancing `offset` by the
returned record's `size` field. A return value of `0` means end of buffer.

### DecodeResult layout

48-byte header followed by trailing variable-length data, all in one
allocation:

| Offset | Type | Field                  | Notes                                                       |
|-------:|------|------------------------|-------------------------------------------------------------|
|      0 | u32  | total_size             | Pass to `free_result`; covers the entire record.            |
|      4 | u32  | kind                   | 0 = Valid, 1 = Unknown.                                     |
|      8 | u32  | size                   | Instruction size; for Unknown, the coalesced run length.    |
|     12 | u32  | is_block_terminator    | 0 / 1; ignored for Unknown.                                 |
|     16 | u64  | address                | Virtual address of this item (base_va + offset).            |
|     24 | u32  | branch_target_count    | Number of u64 entries in the branch_targets table.          |
|     28 | u32  | branch_targets_offset  | Bytes from record start to u64[branch_target_count]; 0 if empty. |
|     32 | u32  | mnemonic_len           | Length in bytes of the UTF-8 mnemonic; 0 for Unknown.       |
|     36 | u32  | mnemonic_offset        | Bytes from record start to UTF-8 mnemonic; 0 if empty.      |
|     40 | u32  | operands_len           | Length in bytes of the UTF-8 operands string.               |
|     44 | u32  | operands_offset        | Bytes from record start to UTF-8 operands; 0 if empty.      |

Trailing data, in order: `branch_targets` (u64[]), `mnemonic` (UTF-8),
`operands` (UTF-8). All within `[record_ptr, record_ptr + total_size)`.

The operand string is rendered in each engine's default Display impl —
Intel-ish for x86, GAS-ish for ARM/ARM64, debug-format for RISC-V (until a
dedicated yaxpeax-style decoder is wired). Explicit AT&T / Intel /
vendor-syntax routing arrives in a later commit.

## Stability

The exports and layout above are the contract consumed by
`disassembly/capstone-wasm`. Any change here is a breaking change to the
Kotlin side and must be made in the same commit.
