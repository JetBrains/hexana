# Tail call

- **Spec version**: 3.0
- **Champion**: Andreas Rossberg
- **Proposal**: <https://github.com/WebAssembly/tail-call>
- **WG**: [2024-07-10](https://github.com/WebAssembly/meetings/blob/main/main/2024/WG-07-10.md)
- **Hexana parser**: ✅ supported
- **Tests**: `FinishedProposalsInstructionTest.kt` § *Tail call*
- **Parser dispatch**: `consumeOpCode.kt` cases `0x12`, `0x13`, `0x15`

## Summary

Three "return-call" instructions that implement proper tail calls — the
caller's frame is replaced by the callee's, so an unbounded chain of tail
calls runs in constant stack. Critical for functional languages and
trampoline-free interpreter loops compiled to Wasm.

## Binary encoding additions

| Byte   | Mnemonic                  | Immediates                  |
|--------|---------------------------|-----------------------------|
| `0x12` | `return_call`             | `x: funcidx`                |
| `0x13` | `return_call_indirect`    | `y: typeidx, x: tableidx`   |
| `0x15` | `return_call_ref`         | `x: typeidx`                |

`return_call_ref` (`0x15`) overlaps in scope with the
[Typed Function References](function-references.md) proposal — both shipped
together in spec 3.0.

## Example bytes

```
12 03                    return_call 3
13 02 00                 return_call_indirect (type 2) (table 0)
15 04                    return_call_ref (type 4)
```

## Hexana model

`OpCodes.Flow.ReturnCall`, `Flow.ReturnCallIndirect`, `Flow.ReturnCallRef`.
