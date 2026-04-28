# Multiple memories

- **Spec version**: 3.0
- **Champion**: Andreas Rossberg
- **Proposal**: <https://github.com/WebAssembly/multi-memory>
- **WG**: [2024-07-10](https://github.com/WebAssembly/meetings/blob/main/main/2024/WG-07-10.md)
- **Hexana parser**: ⚠️ partial — see [Limitations](#limitations)
- **Tests**: `FinishedProposalsInstructionTest.kt` § *Multiple memories* (pins limitation)

## Summary

Lifts the MVP single-memory restriction. Modules can declare multiple
memories, and load/store/bulk-memory instructions now select **which**
memory to act on.

## Binary encoding additions

### Memory section

The vector length in section `5` (Memory) was previously implicitly `≤ 1`;
that constraint is dropped. Encoding is unchanged.

### Bulk memory ops

Already carry an explicit `memidx` immediate (see
[bulk-memory.md](bulk-memory.md)) — pre-multi-memory it had to be `0`,
post-multi-memory it can be any valid memory index. **No encoding
change.**

### Load/store memarg

The `memarg` of MVP load/store instructions (`0x28`–`0x3E`) is `(align,
offset)` ULEB pair. Multi-memory introduces a flag in the **align byte**:

```
align_byte:
  bit 6 (0x40) — if set, a memidx ULEB128 follows after `align` and before `offset`.
  bits 0–5     — actual log2(align), as before.
  bit 7        — high bit of LEB128 continuation (must be 0 for align ≤ 63).
```

Encoded shape with memidx:

```
opcode  align_byte  [memidx_uleb]  offset_uleb
        ^^^^^^^^^^ bit 6 set ⇒ memidx present
```

## Example bytes

```
28 02 00                 i32.load align=2 offset=0 memidx=0   (legacy/MVP)
28 42 00 01              i32.load align=2 offset=0 memidx=1   (multi-memory; bit 6 of 0x42 set)
                            ^^ 0x42 = 0x40 (memidx flag) | 0x02 (log2 align)
```

## Limitations

`consumeMemArg()` in `wasmParser/.../consumers.kt` reads only `(align,
offset)` and **does not decode the memidx flag**. A `i32.load` with the
flag set will:

1. Parse `align = 0x42` (which violates the spec rule that align ≤ 63 — 
   no validator complaint at parser level).
2. Parse `offset = 0x00`.
3. Treat the trailing `memidx` byte as the next opcode.

The test `multi-memory — i32_load with memidx is currently not decoded`
in `FinishedProposalsInstructionTest.kt` pins this as a known limitation.
Bulk-memory ops (`memory.init`, `memory.copy`, `memory.fill`) are
unaffected — they use explicit memidx immediates and parse correctly.

To remove the limitation:
- In `consumeMemArg()`, after reading the align ULEB, check bit 6 of the
  decoded value. If set, read another ULEB128 for memidx, return a richer
  `MemArg(align, offset, memidx)`.
- Update `MemArg` data class and downstream consumers (renderers, analyses).

## Hexana model

Bulk-memory ops already carry memidx: `OpCodes.Memory.Init.memIdx`,
`OpCodes.Memory.Copy.memIdxX/Y`, `OpCodes.Memory.Fill.memIdx`.
Load/store memarg-with-memidx is **not yet modeled**.
