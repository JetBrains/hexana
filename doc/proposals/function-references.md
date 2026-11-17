# Typed Function References

- **Spec version**: 3.0
- **Champion**: Andreas Rossberg
- **Proposal**: <https://github.com/WebAssembly/function-references>
- **WG**: [2024-07-10](https://github.com/WebAssembly/meetings/blob/main/main/2024/WG-07-10.md)
- **Hexana parser**: ✅ supported
- **Tests**: `FinishedProposalsInstructionTest.kt` § *Typed Function References*
- **Parser dispatch**: `consumeOpCode.kt` (`0x14`, `0x15`, `0xD4`–`0xD6`),
  `WasmSectionTypes.kt` (non-nullable refs `0x64`)

## Summary

Generalizes `funcref` so a reference can carry the **specific signature**
of the function it points to (`ref $sig`), and adds non-nullable variants
that statically rule out null. Enables typed indirect calls
(`call_ref $sig`) without a runtime type check, and gives toolchains
zero-cost first-class function values.

## Binary encoding additions

### Reference types

| Byte   | Form                | Spec                                |
|--------|---------------------|-------------------------------------|
| `0x63` | `(ref null ht)`     | nullable typed reference            |
| `0x64` | `(ref ht)`          | **non-nullable** typed reference    |

`ht` is a heap type — either a single-byte abstract code (`func`,
`extern`, …) or an SLEB128-encoded type index.

### Instructions

| Byte   | Mnemonic            | Immediates       |
|--------|---------------------|------------------|
| `0x14` | `call_ref`          | `x: typeidx`     |
| `0x15` | `return_call_ref`   | `x: typeidx`     |
| `0xD4` | `ref.as_non_null`   | —                 |
| `0xD5` | `br_on_null`        | `l: labelidx`    |
| `0xD6` | `br_on_non_null`    | `l: labelidx`    |

(`return_call_ref` is shared with the [Tail Call](tail-call.md) proposal.)

## Example bytes

```
14 02                    call_ref (type 2)
D4                       ref.as_non_null
D5 00                    br_on_null      label 0
D6 01                    br_on_non_null  label 1
```

## Hexana model

- Types: `WasmNullableReferenceType` (`0x63`),
  `WasmNonNullableReferenceType` (`0x64`).
- Instructions: `OpCodes.Flow.CallRef`, `OpCodes.Flow.ReturnCallRef`,
  `OpCodes.Ref.AsNonNull`, `OpCodes.Flow.BrOnNull`, `OpCodes.Flow.BrOnNonNull`.
