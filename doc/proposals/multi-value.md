# Multi-value

- **Spec version**: 2.0
- **Champion**: Andreas Rossberg
- **Proposal**: <https://github.com/WebAssembly/multi-value>
- **WG**: [2020-03-11](https://github.com/WebAssembly/meetings/blob/main/main/2020/WG-03-11.md)
- **Hexana parser**: ✅ supported (block type S33 path)
- **Tests**: `FinishedProposalsInstructionTest.kt` § *Multi-value*
- **Parser dispatch**: `WasmSectionTypes.kt` → `blockType()` (S33 fallback)

## Summary

Allows blocks (`block`/`loop`/`if`) and functions to consume and produce
**multiple** values. The MVP only supported one result, encoded as a single
value-type byte (or `0x40` for empty). This proposal generalizes the block
type to also accept a positive type index referring to an entry in the
type section — that entry's full func-type signature (params + results)
becomes the block's signature.

## Binary encoding additions

The `blocktype` immediate of `block`/`loop`/`if` (`0x02`/`0x03`/`0x04`)
becomes:

```
blocktype ::= 0x40                 (empty)
            | t:valtype            (single result, MVP encoding)
            | x:s33                (positive: typeidx; negative: reserved)
```

`s33` is a 33-bit **signed** LEB128. Positive values are type indices.
Negative values (which would have the high bit of the first byte set, like
the value-type bytes `0x7C`–`0x7F`, `0x6F`, etc.) remain reserved for
existing single-valtype encodings.

Func types in the type section are unchanged structurally — multi-result
support always existed at that level — but compilers now actually emit
non-trivial result vectors.

## Example bytes

```
02 05 0B                 block (typeidx 5) end
                         ^^ positive S33: type index 5
```

## Hexana model

`StandardTypesParser.WasmBlockType.S33(value: UInt)` —
the `value` field carries the decoded type index. The single-valtype path
still produces `WasmRefType`/`WasmNumType`/`WasmVecType` instances, and
the empty path produces `WasmBlockType.Empty`.
