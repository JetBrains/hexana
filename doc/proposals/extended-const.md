# Extended Constant Expressions

- **Spec version**: 3.0
- **Champion**: Sam Clegg
- **Proposal**: <https://github.com/WebAssembly/extended-const>
- **WG**: [2024-07-10](https://github.com/WebAssembly/meetings/blob/main/main/2024/WG-07-10.md)
- **Hexana parser**: ✅ supported (no parser change required)
- **Tests**: `FinishedProposalsInstructionTest.kt` § *Extended Constant Expressions*

## Summary

Broadens the set of instructions that may appear in **constant
expressions** — global initializers, element segment offsets, and data
segment offsets — to include integer arithmetic. No new opcodes; the
change lives in the validator's "is this expr const?" check.

Pre-extended-const, a constant expression was limited to:

```
i32.const | i64.const | f32.const | f64.const
| ref.null | ref.func
| global.get   (immutable imported global only)
```

Post-extended-const, also allowed:

```
i32.add | i32.sub | i32.mul
i64.add | i64.sub | i64.mul
```

## Why the parser doesn't change

`parseOpCodes()` does not enforce constness — it accepts any sequence of
opcodes terminated by `end` (`0x0B`) and lets validation be a layer on
top. So a constant expression like `(i32.const 1) (i32.const 2) (i32.add)
end` was already byte-valid; only its validity *as a const expr*
changed.

## Example bytes

A typical extended-const global initializer:

```
41 01                    i32.const 1
41 02                    i32.const 2
6A                       i32.add
0B                       end
```

Parses as:

```
[I32.Const(1), I32.Const(2), I32.Add]
```

(The `end` byte is consumed but not represented in the op list.)

## Hexana model

Reuses existing op classes:
`OpCodes.numeric.I32.{Const, Add, Sub, Mul}`,
`OpCodes.numeric.I64.{Const, Add, Sub, Mul}`,
`OpCodes.Ref.{Null, Func}`,
`OpCodes.Global.Get`.
