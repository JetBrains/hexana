# Sign-extension operators

- **Spec version**: 2.0
- **Champion**: Ben Smith
- **Proposal**: <https://github.com/WebAssembly/sign-extension-ops>
- **WG**: [2020-03-11](https://github.com/WebAssembly/meetings/blob/main/main/2020/WG-03-11.md)
- **Hexana parser**: ✅ fully supported
- **Tests**: `FinishedProposalsInstructionTest.kt` § *Sign-extension operators*
- **Parser dispatch**: `consumeOpCode.kt` (single-byte opcodes 0xC0–0xC4)

## Summary

Adds five single-byte numeric instructions that sign-extend a smaller-width
value held in an `i32` or `i64` register, mirroring `MOVSX`-class
instructions on hardware.

## Binary encoding additions

| Byte   | Mnemonic             | Stack signature       |
|--------|----------------------|------------------------|
| `0xC0` | `i32.extend8_s`      | `[i32] → [i32]`        |
| `0xC1` | `i32.extend16_s`     | `[i32] → [i32]`        |
| `0xC2` | `i64.extend8_s`      | `[i64] → [i64]`        |
| `0xC3` | `i64.extend16_s`     | `[i64] → [i64]`        |
| `0xC4` | `i64.extend32_s`     | `[i64] → [i64]`        |

No immediates. Single-byte opcode space.

## Example bytes

```
C0                       i32.extend8_s
C1                       i32.extend16_s
C2 C3 C4                 i64.extend{8,16,32}_s
```

## Hexana model

`OpCodes.numeric.I32.Extend8S`, `Extend16S`;
`OpCodes.numeric.I64.Extend8S`, `Extend16S`, `Extend32S` — singleton objects.
