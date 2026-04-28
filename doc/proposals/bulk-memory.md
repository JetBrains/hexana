# Bulk memory operations

- **Spec version**: 2.0
- **Champion**: Ben Smith
- **Proposal**: <https://github.com/WebAssembly/bulk-memory-operations>
- **WG**: [2021-02-10](https://github.com/WebAssembly/meetings/blob/main/main/2021/WG-02-10.md)
- **Hexana parser**: ✅ supported
- **Tests**: `FinishedProposalsInstructionTest.kt` § *Bulk memory operations*
- **Parser dispatch**: `consumeFcOpCode.kt` cases `0x08`–`0x11`

## Summary

Adds `memcpy`/`memset`-style bulk operations on memories and tables, plus
a passive variant of data and element segments that defers initialization
to explicit `*.init` calls. Essential for any toolchain emitting
non-trivial runtime initialization.

## Binary encoding additions

### Instructions (0xFC prefix, single-byte ULEB sub-opcode)

| `0xFC ?` | Mnemonic       | Immediates                          |
|----------|----------------|-------------------------------------|
| `0x08`   | `memory.init`  | `x: dataidx, y: memidx`             |
| `0x09`   | `data.drop`    | `x: dataidx`                        |
| `0x0A`   | `memory.copy`  | `y: memidx (dst), x: memidx (src)`  |
| `0x0B`   | `memory.fill`  | `x: memidx`                         |
| `0x0C`   | `table.init`   | `y: elemidx, x: tableidx`           |
| `0x0D`   | `elem.drop`    | `x: elemidx`                        |
| `0x0E`   | `table.copy`   | `x: tableidx (dst), y: tableidx (src)` |

> Pre-multi-memory the `memidx` byte was always `0x00`; multi-memory keeps
> the same encoding but allows non-zero values (see [multi-memory.md](multi-memory.md)).

### Sections

- **DataCount** (id `12`) — declares the number of data segments before the
  code section so single-pass validators can resolve `data.drop` /
  `memory.init` references.
- **Data** segment kinds extended:
  - `0x00`: active, memory 0, init expr, bytes (MVP)
  - `0x01`: **passive**, bytes only (no offset)
  - `0x02`: active, explicit memidx, init expr, bytes
- **Element** segment shapes similarly extended (covered in
  [reference-types.md](reference-types.md)).

## Example bytes

```
FC 08 03 00              memory.init dataidx=3 memidx=0
FC 09 02                 data.drop 2
FC 0A 00 00              memory.copy 0 0
FC 0B 00                 memory.fill 0
FC 0C 04 00              table.init elemidx=4 tableidx=0
FC 0D 01                 elem.drop 1
FC 0E 00 00              table.copy 0 0
```

## Hexana model

`OpCodes.Memory.Init`, `OpCodes.Memory.Data.Drop`, `OpCodes.Memory.Copy`,
`OpCodes.Memory.Fill`; `Table.Init`, `Table.Copy`; `Element.Drop`.
DataCount section: `WasmSectionDataCount`.
