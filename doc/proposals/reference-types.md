# Reference Types

- **Spec version**: 2.0
- **Champion**: Andreas Rossberg
- **Proposal**: <https://github.com/WebAssembly/reference-types>
- **WG**: [2021-02-10](https://github.com/WebAssembly/meetings/blob/main/main/2021/WG-02-10.md)
- **Hexana parser**: ✅ supported
- **Tests**: `FinishedProposalsInstructionTest.kt` § *Reference Types*
- **Parser dispatch**: `consumeOpCode.kt` (`0xD0`–`0xD2`, `0x1C`, `0x25`, `0x26`),
  `consumeFcOpCode.kt` (table.{init,copy,grow,size,fill}, elem.drop)

## Summary

Adds first-class typed references (`funcref` and `externref`), `null` /
`is-null` checks, table operations on reference types, and a typed
`select` form. This is the foundation that GC and Typed Function
References later extend.

## Binary encoding additions

### Value types

| Byte   | Type                              |
|--------|-----------------------------------|
| `0x70` | `funcref`  (abstract heap type)   |
| `0x6F` | `externref` (abstract heap type)  |

(Encoded as single-byte SLEB128 negative numbers: `0x70 = -16`,
`0x6F = -17`.)

### Instructions

| Byte         | Mnemonic         | Immediates                  |
|--------------|------------------|-----------------------------|
| `0xD0`       | `ref.null`       | `ht: heaptype`              |
| `0xD1`       | `ref.is_null`    | —                            |
| `0xD2`       | `ref.func`       | `x: funcidx`                |
| `0x1C`       | `select t*`      | `t*: vec(valtype)`          |
| `0x25`       | `table.get`      | `x: tableidx`               |
| `0x26`       | `table.set`      | `x: tableidx`               |
| `0xFC 0x0C`  | `table.init`    | `y: elemidx, x: tableidx`   |
| `0xFC 0x0D`  | `elem.drop`     | `x: elemidx`                |
| `0xFC 0x0E`  | `table.copy`    | `x: tableidx, y: tableidx`  |
| `0xFC 0x0F`  | `table.grow`    | `x: tableidx`               |
| `0xFC 0x10`  | `table.size`    | `x: tableidx`               |
| `0xFC 0x11`  | `table.fill`    | `x: tableidx`               |

### Element section

The element section gains five new flag-prefixed shapes (`0x00`–`0x06`)
to express active/passive/declarative segments, table-index, and
element-expression vs. funcidx-vec bodies.

## Example bytes

```
D0 70                    ref.null funcref
D1                       ref.is_null
D2 07                    ref.func 7
1C 01 7F                 select t=[i32]
25 00 26 00              table.get 0; table.set 0
FC 0F 00                 table.grow 0
FC 10 00                 table.size 0
FC 11 00                 table.fill 0
```

## Hexana model

- Value types: `WasmFuncHeapType` (0x70), `WasmExternRefHeapType` (0x6F)
  in `WasmSectionTypes.kt`.
- Instructions: `OpCodes.Ref.Null/IsNull/Func`,
  `OpCodes.ParametricInstructions.SelectWithType`,
  `OpCodes.opcodes.table.Table.{Get,Set,Grow,Size,Fill,Init,Copy}`,
  `OpCodes.opcodes.table.Element.Drop`.
