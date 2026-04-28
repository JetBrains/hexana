# Non-trapping float-to-int conversions

- **Spec version**: 2.0
- **Champion**: Dan Gohman
- **Proposal**: <https://github.com/WebAssembly/nontrapping-float-to-int-conversions>
- **WG**: [2020-03-11](https://github.com/WebAssembly/meetings/blob/main/main/2020/WG-03-11.md)
- **Hexana parser**: ✅ fully supported
- **Tests**: `FinishedProposalsInstructionTest.kt` § *Non-trapping float-to-int conversions*
- **Parser dispatch**: `consumeFcOpCode.kt` → `consumeFCBasedOpCode` cases `0x00`–`0x07`

## Summary

The MVP `i{32,64}.trunc_f{32,64}_{s,u}` instructions trap on out-of-range
or NaN inputs. This proposal adds saturating variants (`trunc_sat`) that
clamp out-of-range inputs to the destination type's min/max and yield 0
for NaN.

## Binary encoding additions

`0xFC` prefix, single-byte ULEB128 sub-opcode:

| `0xFC ?` | Mnemonic                |
|----------|-------------------------|
| `0x00`   | `i32.trunc_sat_f32_s`   |
| `0x01`   | `i32.trunc_sat_f32_u`   |
| `0x02`   | `i32.trunc_sat_f64_s`   |
| `0x03`   | `i32.trunc_sat_f64_u`   |
| `0x04`   | `i64.trunc_sat_f32_s`   |
| `0x05`   | `i64.trunc_sat_f32_u`   |
| `0x06`   | `i64.trunc_sat_f64_s`   |
| `0x07`   | `i64.trunc_sat_f64_u`   |

No further immediates. The `0xFC` prefix is shared with bulk memory ops
(sub-opcodes `0x08`–`0x11`).

## Example bytes

```
FC 00      i32.trunc_sat_f32_s
FC 07      i64.trunc_sat_f64_u
```

## Hexana model

`OpCodes.numeric.I32.TruncSatF32S` … `TruncSatF64U`;
`OpCodes.numeric.I64.TruncSatF32S` … `TruncSatF64U` — singleton objects.
