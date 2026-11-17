# Relaxed SIMD

- **Spec version**: 3.0
- **Champions**: Marat Dukhan, Zhi An Ng
- **Proposal**: <https://github.com/WebAssembly/relaxed-simd>
- **WG**: [2024-07-10](https://github.com/WebAssembly/meetings/blob/main/main/2024/WG-07-10.md)
- **Hexana parser**: ✅ supported
- **Tests**: `FinishedProposalsInstructionTest.kt` § *Relaxed SIMD*
- **Parser dispatch**: `consumeFcOpCode.kt` (0xFD prefix) →
  `consumeFDBasedOpCode` for sub-opcodes `≥ 0x100`

## Summary

Adds SIMD instructions whose **exact** behavior on certain edge inputs
(out-of-range FP-to-int, NaN handling, FMA rounding, swizzle index
out-of-range) is **implementation-defined**. Lets engines map these
directly to their host-CPU instructions without a portability shim,
trading a small amount of cross-engine determinism for a sizable speedup
on AVX-512 / SVE / NEON / SSE4 paths.

## Binary encoding additions

`0xFD` prefix (shared with [Fixed-width SIMD](simd.md)). Sub-opcodes are
in the **multi-byte ULEB128** range `0x100`–`0x115`:

| Sub-opcode | Mnemonic                        |
|------------|----------------------------------|
| `0x100`    | `i8x16.relaxed_swizzle`         |
| `0x101`    | `i32x4.relaxed_trunc_f32x4_s`   |
| `0x102`    | `i32x4.relaxed_trunc_f32x4_u`   |
| `0x103`    | `i32x4.relaxed_trunc_f64x2_s_zero` |
| `0x104`    | `i32x4.relaxed_trunc_f64x2_u_zero` |
| `0x105`    | `f32x4.relaxed_madd`            |
| `0x106`    | `f32x4.relaxed_nmadd`           |
| `0x107`    | `f64x2.relaxed_madd`            |
| `0x108`    | `f64x2.relaxed_nmadd`           |
| `0x109`    | `i8x16.relaxed_laneselect`      |
| `0x10A`    | `i16x8.relaxed_laneselect`      |
| `0x10B`    | `i32x4.relaxed_laneselect`      |
| `0x10C`    | `i64x2.relaxed_laneselect`      |
| `0x10D`    | `f32x4.relaxed_min`             |
| `0x10E`    | `f32x4.relaxed_max`             |
| `0x10F`    | `f64x2.relaxed_min`             |
| `0x110`    | `f64x2.relaxed_max`             |
| `0x111`    | `i16x8.relaxed_q15mulr_s`       |
| `0x112`    | `i16x8.relaxed_dot_i8x16_i7x16_s` |
| `0x113`    | `i32x4.relaxed_dot_i8x16_i7x16_add_s` |

`0x100` ULEB128-encoded is **two bytes**: `0x80 0x02`
(continuation bit on the first byte, then `0x02 << 7`).

## Example bytes

```
FD 80 02                 i8x16.relaxed_swizzle
FD 85 02                 f32x4.relaxed_madd
FD 8D 02                 f32x4.relaxed_min
```

## Hexana model

Singletons in `OpCodes.opcodes.vector.{i8x16,i16x8,i32x4,i64x2,f32x4,f64x2}`
under names like `RelaxedSwizzle`, `RelaxedMadd`, `RelaxedMin`,
`RelaxedLaneSelect`, etc.
