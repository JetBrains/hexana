# Exception Handling (modern)

- **Spec version**: 3.0
- **Champions**: Heejin Ahn, Ben Titzer
- **Proposal**: <https://github.com/WebAssembly/exception-handling>
- **WG**: [2025-07-23](https://github.com/WebAssembly/meetings/blob/main/main/2025/WG-07-23.md)
- **Hexana parser**: ✅ supported (modern + legacy paths)
- **Tests**:
  - `FinishedProposalsInstructionTest.kt` § *Exception Handling*
  - `LegacyExceptionInstructionTest.kt` (legacy `try`/`catch`/`delegate`)
- **Parser dispatch**: `consumeOpCode.kt` (`0x1F`, `0x08`, `0x0A`),
  `OpCodes.kt` `Flow.CatchType` sealed family

## Summary

Final shape of WebAssembly exception handling. Replaces the legacy
`try`/`catch`/`delegate` block (which the parser still accepts) with a
flat **`try_table`** instruction that lists all catch handlers up-front,
along with first-class `exnref`. The legacy form is described in
[doc/exception-handling/legacy/Exceptions.md](../exception-handling/legacy/Exceptions.md).

## Binary encoding additions

### Heap type

| Byte   | Heap type   |
|--------|-------------|
| `0x69` | `exn`       |

### Instructions

| Byte   | Mnemonic     | Immediates                                       |
|--------|--------------|--------------------------------------------------|
| `0x1F` | `try_table`  | `bt: blocktype, vec(catchclause), instr*, 0x0B`  |
| `0x08` | `throw`      | `x: tagidx`                                      |
| `0x0A` | `throw_ref`  | —                                                 |

### Catch clause encoding

Inside `try_table`'s vector, each catch is one of four shapes:

| Tag byte | Form           | Immediates                |
|----------|----------------|---------------------------|
| `0x00`   | `catch`        | `x: tagidx, l: labelidx`  |
| `0x01`   | `catch_ref`    | `x: tagidx, l: labelidx`  |
| `0x02`   | `catch_all`    | `l: labelidx`             |
| `0x03`   | `catch_all_ref`| `l: labelidx`             |

Block body terminates with the standard `0x0B` end byte.

## Example bytes

```
1F 40 00 0B              try_table bt=Empty, no catches, end
1F 40 01 02 05 0B        try_table bt=Empty, [catch_all → label 5], end
                            ^^ vec len 1
                                ^^ catch kind 0x02 = catch_all
                                   ^^ labelidx 5
08 02                    throw tag 2
0A                       throw_ref
```

## Hexana model

- Modern: `OpCodes.Flow.TryTable(blockType, catches, code, offset)`,
  `OpCodes.Flow.Trow(tagIdx)`, `OpCodes.Flow.ThrowRef`.
- `OpCodes.Flow.CatchType` sealed: `Catch`, `CatchRef`, `CatchAll`,
  `CatchAllRef`.
- Legacy: `OpCodes.legacy.eh.LegacyEH.{Try,Catch,Rethrow,Delegate,CatchAll}`
  — covered in
  [doc/exception-handling/legacy/Exceptions.md](../exception-handling/legacy/Exceptions.md).
