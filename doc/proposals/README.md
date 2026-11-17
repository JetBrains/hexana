# Finished WebAssembly Proposals — Hexana Parser Reference

Short binary-format references for **Finished** WebAssembly proposals
(merged into the spec — see
[finished-proposals.md](https://github.com/WebAssembly/proposals/blob/main/finished-proposals.md))
that have parser-level surface in Hexana.

Each doc captures the binary encoding additions a parser must handle and
points to the canonical proposal repo for full semantics. Pinned with
inline-bytes tests in
`wasmParser/src/commonTest/kotlin/org/jetbrains/hexana/wasm/FinishedProposalsInstructionTest.kt`.

## Index

| Proposal | Spec | Doc |
|----------|------|-----|
| Sign-extension operators | 2.0 | [sign-extension.md](sign-extension.md) |
| Non-trapping float-to-int conversions | 2.0 | [nontrapping-float-to-int.md](nontrapping-float-to-int.md) |
| Multi-value | 2.0 | [multi-value.md](multi-value.md) |
| Reference Types | 2.0 | [reference-types.md](reference-types.md) |
| Bulk memory operations | 2.0 | [bulk-memory.md](bulk-memory.md) |
| Fixed-width SIMD | 2.0 | [simd.md](simd.md) |
| Threads (atomics, shared memory) | 2.0 | [threads.md](threads.md) |
| Tail call | 3.0 | [tail-call.md](tail-call.md) |
| Typed Function References | 3.0 | [function-references.md](function-references.md) |
| Garbage Collection | 3.0 | [gc.md](gc.md) |
| Multiple memories | 3.0 | [multi-memory.md](multi-memory.md) |
| Relaxed SIMD | 3.0 | [relaxed-simd.md](relaxed-simd.md) |
| Exception Handling (modern) | 3.0 | [exception-handling.md](exception-handling.md) |
| Extended Constant Expressions | 3.0 | [extended-const.md](extended-const.md) |

## Excluded (no parser opcode surface)

- **MVP** — covered everywhere by definition.
- **Import/Export of Mutable Globals**, **JS BigInt↔i64**, **JS String Builtins** —
  JS API or section-level only; no new opcodes.
- **Custom Annotation Syntax in the Text Format** — text format only.
- **Branch Hinting** — custom section (`metadata.code.branch_hint`); not yet
  wired into the parser.
- **Memory64** — section-level (`0x04`/`0x05` limits already supported); the
  memarg-flag-bit extension is shared with multi-memory and tracked there.
