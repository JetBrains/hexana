# Garbage Collection

- **Spec version**: 3.0
- **Champion**: Andreas Rossberg
- **Proposal**: <https://github.com/WebAssembly/gc>
- **WG**: [2024-07-10](https://github.com/WebAssembly/meetings/blob/main/main/2024/WG-07-10.md)
- **Hexana parser**: ✅ supported (with known caveats — see [Notes](#notes))
- **Tests**:
  - `MultibyteInstructionTest.kt` § *FB prefix — GC* (full coverage)
  - `FinishedProposalsInstructionTest.kt` § *Garbage Collection* (smoke test)
- **Parser dispatch**: `consumeGCOpCode.kt` (sub-opcodes `0x00`–`0x1E`),
  type section parser in `WasmSectionTypes.kt`

## Summary

Adds managed `struct` and `array` aggregates, the abstract heap-type
hierarchy (`any`/`eq`/`struct`/`array`/`i31`/`none`/`extern`/`func`/`exn`
+ `none*` bottom types), recursive and explicitly-final type
declarations, and a full set of new/get/set/test/cast instructions.
Foundation for Kotlin/Wasm, Java→Wasm, OCaml→Wasm and similar managed-
language backends.

## Binary encoding additions

### Type section

```
rectype  ::= 0x4E    vec(subtype)               (recursive group)
subtype  ::= 0x50    vec(typeidx) comptype       (subtype, non-final)
           | 0x4F    vec(typeidx) comptype       (subtype, final)
           |                       comptype       (bare = final, no super)
comptype ::= 0x5E    fieldtype                   (array)
           | 0x5F    vec(fieldtype)              (struct)
           | 0x60    resulttype resulttype       (func)   ← MVP
fieldtype ::= storagetype mut
storagetype ::= valtype | 0x77 (i16) | 0x78 (i8)
```

### Heap types (abstract codes)

Single-byte SLEB128 negative values:

| Byte   | Heap type                                |
|--------|-----------------------------------------|
| `0x69` | `exn`                                    |
| `0x6A` | `array`                                  |
| `0x6B` | `struct`                                 |
| `0x6C` | `i31`                                    |
| `0x6D` | `eq`                                     |
| `0x6E` | `any`                                    |
| `0x6F` | `extern`                                 |
| `0x70` | `func`                                   |
| `0x71` | `none`                                   |
| `0x72` | `noextern`                               |
| `0x73` | `nofunc`                                 |
| `0x74` | `noexn`                                  |

Concrete heap types are SLEB128-encoded **non-negative** type indices.

### Instructions (0xFB prefix, ULEB128 sub-opcode)

| `0xFB ?` | Mnemonic               | Immediates             |
|----------|------------------------|------------------------|
| `0x00`   | `struct.new`           | `t: typeidx`           |
| `0x01`   | `struct.new_default`   | `t: typeidx`           |
| `0x02`   | `struct.get`           | `t: typeidx, f: fieldidx` |
| `0x03`   | `struct.get_s`         | `t, f`                 |
| `0x04`   | `struct.get_u`         | `t, f`                 |
| `0x05`   | `struct.set`           | `t, f`                 |
| `0x06`   | `array.new`            | `t: typeidx`           |
| `0x07`   | `array.new_default`    | `t: typeidx`           |
| `0x08`   | `array.new_fixed`      | `t: typeidx, n: u32`   |
| `0x09`   | `array.new_data`       | `t: typeidx, d: dataidx` |
| `0x0A`   | `array.new_elem`       | `t: typeidx, e: elemidx` |
| `0x0B`   | `array.get`            | `t: typeidx`           |
| `0x0C`   | `array.get_s`          | `t: typeidx`           |
| `0x0D`   | `array.get_u`          | `t: typeidx`           |
| `0x0E`   | `array.set`            | `t: typeidx`           |
| `0x0F`   | `array.len`            | —                       |
| `0x10`   | `array.fill`           | `t: typeidx`           |
| `0x11`   | `array.copy`           | `t_dst, t_src`         |
| `0x12`   | `array.init_data`      | `t: typeidx, d: dataidx` |
| `0x13`   | `array.init_elem`      | `t: typeidx, e: elemidx` |
| `0x14`   | `ref.test`             | `ht: heaptype` (SLEB)  |
| `0x15`   | `ref.test null`        | `ht: heaptype`         |
| `0x16`   | `ref.cast`             | `ht: heaptype`         |
| `0x17`   | `ref.cast null`        | `ht: heaptype`         |
| `0x18`   | `br_on_cast`           | `flags: u8, l: labelidx, ht_src, ht_dst` |
| `0x19`   | `br_on_cast_fail`      | `flags: u8, l: labelidx, ht_src, ht_dst` |
| `0x1A`   | `any.convert_extern`   | —                       |
| `0x1B`   | `extern.convert_any`   | —                       |
| `0x1C`   | `ref.i31`              | —                       |
| `0x1D`   | `i31.get_s`            | —                       |
| `0x1E`   | `i31.get_u`            | —                       |

## Example bytes

```
FB 00 9D 43              struct.new (type 8605)
FB 05 9D 43 04           struct.set (type 8605) (field 4)
FB 1C                    ref.i31
FB 16 70                 ref.cast funcref           (heaptype = 0x70 = func)
```

## Notes

Known parser caveats (see also `consumeGCOpCode.kt` analysis in the
project notes):

- `heapType()` in `WasmSectionTypes.kt` falls back to **ULEB128** when
  the byte is not a recognized abstract code. The spec mandates SLEB128.
  Works in practice because all standard abstract codes (`0x69`–`0x74`)
  are matched explicitly and concrete indices ≤ 63 share the same byte
  sequence under both encodings.
- `HeapType(value: Int)` model used by GC instructions is parallel to
  the rich `WasmHeapType` hierarchy used by the type section. Renderers
  and analyses must handle both shapes.
