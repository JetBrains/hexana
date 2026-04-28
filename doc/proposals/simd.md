# Fixed-width SIMD

- **Spec version**: 2.0
- **Champions**: Deepti Gandluri, Arun Purushan
- **Proposal**: <https://github.com/webassembly/simd>
- **WG**: [2021-07-14](https://github.com/WebAssembly/meetings/blob/main/main/2021/WG-07-14.md)
- **Hexana parser**: ✅ supported
- **Tests**: `FinishedProposalsInstructionTest.kt` § *Fixed-width SIMD*
- **Parser dispatch**: `consumeFcOpCode.kt` (0xFD prefix) → `consumeFDBasedOpCode`

## Summary

Adds the 128-bit `v128` SIMD value type and a large family of vector
instructions (load/store, splat, extract/replace lane, integer & FP arith,
saturating arith, swizzle/shuffle, lane-wise comparisons, …). All vector
instructions are encoded under the **`0xFD`** prefix.

## Binary encoding additions

### Value type

| Byte   | Type    |
|--------|---------|
| `0x7B` | `v128`  |

### Instructions

`0xFD` prefix + ULEB128 sub-opcode in the range `0x00`–~`0xFF` (Relaxed
SIMD extends this to `≥ 0x100`, see [relaxed-simd.md](relaxed-simd.md)).

Example dispatch ranges (non-exhaustive):

| Range              | Family                                  |
|--------------------|------------------------------------------|
| `0x00`–`0x0B`      | loads/stores (`v128.load`, `v128.store`, lane variants) |
| `0x0C`             | `v128.const i128`  (16 raw lane bytes)  |
| `0x0D`             | `i8x16.shuffle`  (16-byte lane mask)    |
| `0x0E`–`0x14`      | swizzle, splat, extract/replace_lane    |
| `0x23`–`0x82`      | i8x16/i16x8/i32x4/i64x2 integer ops     |
| `0xE0`–`0xFF`      | f32x4/f64x2 floating-point ops          |

`v128.const` is the only instruction whose immediate is **not** ULEB128 —
it's 16 raw bytes (one per lane).

## Example bytes

```
FD 0C 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00     v128.const all-zero
FD 51                                                     i8x16.add
FD 0D <16 lane indices>                                   i8x16.shuffle
```

## Hexana model

`OpCodes.opcodes.vector.V128.Const(v0…vf: Byte)`; per-lane families in
`vector.{i8x16,i16x8,i32x4,i64x2,f32x4,f64x2}`; dispatch in
`consumeFDBasedOpCode.kt`.
