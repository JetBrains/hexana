# Threads

- **Spec version**: 2.0 (Finished — merged into core)
- **Champions**: Andreas Rossberg, Conrad Watt
- **Proposal**: <https://github.com/WebAssembly/threads>
- **Spec text**: <https://webassembly.github.io/threads/core/binary/instructions.html>
- **WG**: standardised in core spec; threads-spec branch retired
- **Hexana parser**: ✅ supported
- **Tests**: `AtomicInstructionTest.kt` — 70 inlined tests, one per instruction + memarg/marker/classifier invariants
- **Parser dispatch**: `consumeFcOpCode.kt` (0xFE prefix) → `consumeFEBasedOpCode`
- **Marker interface**: `AtomicOpCode` (siblings: `VectorOpCode`)

## Summary

Adds shared linear memory and atomic memory accesses for thread-safe
JavaScript ↔ WebAssembly interop. All atomic instructions are encoded
under the **`0xFE`** prefix.

Two pieces of surface land outside the opcode table:

1. **Shared memory** — the memory-section limits flag gets a new bit,
   `0x02 (shared)`, combinable with `0x01 (max)`. So a `(memory 1 1 shared)`
   declaration encodes its limits as `flags=0x03 min=1 max=1`. Already
   accepted by the parser via the existing limits decoder; no opcode-side
   work was needed.
2. **`atomic.fence`** — sole instruction in the prefix that carries a
   reserved `0x00` byte instead of a [`MemArg`](#memarg). Future
   memory-order encodings may use this slot.

## Binary encoding — `0xFE` prefix

`0xFE <subop:LEB128> <memarg>` for every memory-access instruction.
[`MemArg`](#memarg) is the standard `<align:LEB128> <offset:LEB128>`
pair shared with non-atomic loads/stores.

`atomic.fence` is the lone exception: `0xFE 0x03 0x00`.

### Wait / notify

| Subop  | Mnemonic              | Operands                                 | Result | Natural align |
|--------|-----------------------|------------------------------------------|--------|---------------|
| `0x00` | `memory.atomic.notify`  | `i32 addr, i32 count`                  | `i32`  | 4 (align=2)   |
| `0x01` | `memory.atomic.wait32`  | `i32 addr, i32 expected, i64 timeout`  | `i32`  | 4 (align=2)   |
| `0x02` | `memory.atomic.wait64`  | `i32 addr, i64 expected, i64 timeout`  | `i32`  | 8 (align=3)   |

### Fence

| Subop  | Mnemonic       | Encoding tail | Operands | Result |
|--------|----------------|---------------|----------|--------|
| `0x03` | `atomic.fence` | `0x00`        | —        | —      |

### Atomic loads (`0x10`–`0x16`)

| Subop  | Mnemonic                | Result | Natural align |
|--------|-------------------------|--------|---------------|
| `0x10` | `i32.atomic.load`         | `i32`  | 4 (align=2)   |
| `0x11` | `i64.atomic.load`         | `i64`  | 8 (align=3)   |
| `0x12` | `i32.atomic.load8_u`      | `i32`  | 1 (align=0)   |
| `0x13` | `i32.atomic.load16_u`     | `i32`  | 2 (align=1)   |
| `0x14` | `i64.atomic.load8_u`      | `i64`  | 1 (align=0)   |
| `0x15` | `i64.atomic.load16_u`     | `i64`  | 2 (align=1)   |
| `0x16` | `i64.atomic.load32_u`     | `i64`  | 4 (align=2)   |

### Atomic stores (`0x17`–`0x1D`)

| Subop  | Mnemonic            | Operands              | Natural align |
|--------|---------------------|-----------------------|---------------|
| `0x17` | `i32.atomic.store`    | `i32 addr, i32 val` | 4 (align=2)   |
| `0x18` | `i64.atomic.store`    | `i32 addr, i64 val` | 8 (align=3)   |
| `0x19` | `i32.atomic.store8`   | `i32 addr, i32 val` | 1 (align=0)   |
| `0x1A` | `i32.atomic.store16`  | `i32 addr, i32 val` | 2 (align=1)   |
| `0x1B` | `i64.atomic.store8`   | `i32 addr, i64 val` | 1 (align=0)   |
| `0x1C` | `i64.atomic.store16`  | `i32 addr, i64 val` | 2 (align=1)   |
| `0x1D` | `i64.atomic.store32`  | `i32 addr, i64 val` | 4 (align=2)   |

### Read-modify-write (RMW) — 7 ops × 7 size/sign rows = 49 instructions

Operand shape is `i32 addr, T val` for `add`/`sub`/`and`/`or`/`xor`/`xchg`,
and `i32 addr, T expected, T replacement` for `cmpxchg`. Result is `T`.

The opcode block is **column-major**: each operation occupies 7 consecutive
subops, ordered by size/sign as below.

#### Size/sign row order (within each operation block)

| Row | Source type | Width  | Mnemonic shape      | Natural align |
|-----|-------------|--------|---------------------|---------------|
| 0   | `i32`       | 32-bit | `i32.atomic.rmw.OP`     | align=2       |
| 1   | `i64`       | 64-bit | `i64.atomic.rmw.OP`     | align=3       |
| 2   | `i32`       | 8-bit  | `i32.atomic.rmw8.OP_u`  | align=0       |
| 3   | `i32`       | 16-bit | `i32.atomic.rmw16.OP_u` | align=1       |
| 4   | `i64`       | 8-bit  | `i64.atomic.rmw8.OP_u`  | align=0       |
| 5   | `i64`       | 16-bit | `i64.atomic.rmw16.OP_u` | align=1       |
| 6   | `i64`       | 32-bit | `i64.atomic.rmw32.OP_u` | align=2       |

#### Operation columns

| OP        | Subop range  |
|-----------|--------------|
| `add`     | `0x1E`–`0x24` |
| `sub`     | `0x25`–`0x2B` |
| `and`     | `0x2C`–`0x32` |
| `or`      | `0x33`–`0x39` |
| `xor`     | `0x3A`–`0x40` |
| `xchg`    | `0x41`–`0x47` |
| `cmpxchg` | `0x48`–`0x4E` |

So `i64.atomic.rmw32.xor_u` = `0x3A` (xor base) + `6` (row 6) = `0x40` —
the formula `subop = base + row` works for the entire RMW table.

## <a id="memarg"></a>MemArg

Same structure as non-atomic memory ops:

```
memarg := align:LEB128  offset:LEB128
```

`align` is encoded as **log₂(byte-alignment)** — so `align=2` means 4-byte
alignment. The Threads spec mandates **natural alignment** for every
atomic op (load/store/RMW alignment must match the access width); the
table columns above record the byte width and the encoded log₂ value.

> **Validation note**: `wat2wasm` (and the embedded `wat2wasm_full.wasm`
> Hexana uses) accepts under-aligned atomic ops at compile time even
> though the spec rejects them. Hexana's parser does likewise — alignment
> validation is a runtime/engine concern. Tests pin natural alignment so
> any future drift surfaces as an `assertEquals` mismatch.

## Example bytes

```
FE 1E 02 00                                  i32.atomic.rmw.add (align=2, offset=0)
FE 11 03 80 01                               i64.atomic.load (align=3, offset=128)
FE 03 00                                     atomic.fence
FE 01 02 00                                  memory.atomic.wait32 (align=2, offset=0)
FE 48 02 00                                  i32.atomic.rmw.cmpxchg (align=2, offset=0)
```

## Hexana model

- **Opcode classes**: `wasm.opcodes.atomic.Atomic.*` — flat 67-class
  surface, one per spec mnemonic. Naming convention:
  `<width>.atomic.rmw<size>.<op>_u` → `Atomic.{I32,I64}Rmw{,8,16,32}{Op}{U}`
  (e.g. `i64.atomic.rmw32.cmpxchg_u` → `Atomic.I64Rmw32CmpxchgU`).
  Every class implements the `AtomicOpCode` marker.
- **Dispatch**: `consumeFcOpCode.kt::consumeMultibyteOpCode` routes the
  `0xFEu` prefix into `consumeFEOpCode.kt::consumeFEBasedOpCode`, which
  is a single `when`-table over the subop and rebuilds a `MemArg` via
  the shared `consumeMemArg()` helper. `Atomic.Fence` consumes its
  reserved byte unconditionally (non-strict) so malformed inputs don't
  desync the parser.
- **Categorisation**: `analysis.OpcodeClassifier` maps `is AtomicOpCode`
  → `OpcodeCategory.Memory` so size analysis buckets atomics alongside
  conventional load/store ops without enumerating each subtype.
- **Shared memory**: handled by the existing memory-limits decoder
  (`flags & 0x02`); no opcode-side change.

## Cross-references

- [bulk-memory.md](bulk-memory.md) — `0xFC` prefix, neighbouring memory ops.
- [simd.md](simd.md) — `0xFD` prefix, neighbouring vector ops.
- [gc.md](gc.md) — `0xFB` prefix, GC instructions.
