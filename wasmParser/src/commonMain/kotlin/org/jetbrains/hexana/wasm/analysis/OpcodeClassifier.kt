package org.jetbrains.hexana.wasm.analysis

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.VectorOpCode
import org.jetbrains.hexana.wasm.sections.UnparsedOpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f64
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64

/**
 * Classifies a WASM [OpCode] into a high-level [OpcodeCategory] for size analysis.
 *
 * Classification rules (evaluated in order):
 * - **Call** — call/return-call variants: [OpCodes.Flow.Call], [OpCodes.Flow.CallIndirect],
 *   [OpCodes.Flow.ReturnCall], [OpCodes.Flow.ReturnCallIndirect],
 *   [OpCodes.Flow.CallRef], [OpCodes.Flow.ReturnCallRef]
 * - **Control** — all other [OpCodes.Flow] opcodes (block, branch, exception, etc.)
 * - **Memory** — load/store variants in I32/I64/F32/F64, plus [OpCodes.Memory] opcodes
 * - **GC** — all [OpCodes.GC] opcodes
 * - **Arithmetic** — remaining I32/I64/F32/F64 opcodes (ALU, conversions, constants)
 * - **Other** — everything else (Local, Global, Table, Ref, reserved, etc.)
 */
fun classifyOpcode(opcode: OpCode): OpcodeCategory = when (opcode) {
    // --- Call ---
    is OpCodes.Flow.Call -> OpcodeCategory.Call
    is OpCodes.Flow.CallIndirect -> OpcodeCategory.Call
    is OpCodes.Flow.ReturnCallIndirect -> OpcodeCategory.Call
    is OpCodes.Flow.CallRef -> OpcodeCategory.Call
    is OpCodes.Flow.ReturnCallRef -> OpcodeCategory.Call
    is OpCodes.Flow.ReturnCall -> OpcodeCategory.Call

    // --- Control (remaining Flow opcodes) ---
    is OpCodes.Flow.Block -> OpcodeCategory.Control   // also covers Loop, If, Else (subclasses)
    is OpCodes.Flow.Br -> OpcodeCategory.Control
    is OpCodes.Flow.BrIf -> OpcodeCategory.Control
    is OpCodes.Flow.BrTable -> OpcodeCategory.Control
    is OpCodes.Flow.BrOnNull -> OpcodeCategory.Control
    is OpCodes.Flow.BrOnNonNull -> OpcodeCategory.Control
    is OpCodes.Flow.Trow -> OpcodeCategory.Control
    is OpCodes.Flow.TryTable -> OpcodeCategory.Control
    OpCodes.Flow.Terminal -> OpcodeCategory.Control
    OpCodes.Flow.Return -> OpcodeCategory.Control
    OpCodes.ParametricInstructions.Drop -> OpcodeCategory.Control
    OpCodes.ParametricInstructions.Select -> OpcodeCategory.Control
    is OpCodes.ParametricInstructions.SelectWithType -> OpcodeCategory.Control
    OpCodes.ParametricInstructions.Unreachable -> OpcodeCategory.Control
    OpCodes.ParametricInstructions.Nop -> OpcodeCategory.Control
    OpCodes.Flow.ThrowRef -> OpcodeCategory.Control

    // --- Memory: Load/Store from I32, I64, F32, F64 ---
    is i32.Load -> OpcodeCategory.Memory
    is i32.Load8_S -> OpcodeCategory.Memory
    is i32.Load8_U -> OpcodeCategory.Memory
    is i32.Load16_S -> OpcodeCategory.Memory
    is i32.Load16_U -> OpcodeCategory.Memory
    is i32.Store -> OpcodeCategory.Memory
    is i32.Store8 -> OpcodeCategory.Memory
    is i32.Store16 -> OpcodeCategory.Memory

    is i64.Load -> OpcodeCategory.Memory
    is i64.Load8_S -> OpcodeCategory.Memory
    is i64.Load8_U -> OpcodeCategory.Memory
    is i64.Load16_S -> OpcodeCategory.Memory
    is i64.Load16_U -> OpcodeCategory.Memory
    is i64.Load32_S -> OpcodeCategory.Memory
    is i64.Load32_U -> OpcodeCategory.Memory
    is i64.Store -> OpcodeCategory.Memory
    is i64.Store8 -> OpcodeCategory.Memory
    is i64.Store16 -> OpcodeCategory.Memory
    is i64.Store32 -> OpcodeCategory.Memory

    is f32.Load -> OpcodeCategory.Memory
    is f32.Store -> OpcodeCategory.Memory

    is f64.Load -> OpcodeCategory.Memory
    is f64.Store -> OpcodeCategory.Memory

    is OpCodes.Memory.Size -> OpcodeCategory.Memory
    is OpCodes.Memory.Grow -> OpcodeCategory.Memory

    is OpCodes.Memory.Init -> OpcodeCategory.Memory
    is OpCodes.Memory.Copy -> OpcodeCategory.Memory
    is OpCodes.Memory.Fill -> OpcodeCategory.Memory
    is OpCodes.Memory.Data.Drop -> OpcodeCategory.Memory

    // --- GC ---
    is OpCodes.GC.StructNew -> OpcodeCategory.GC
    is OpCodes.GC.StructNewDefault -> OpcodeCategory.GC
    is OpCodes.GC.StructGet -> OpcodeCategory.GC
    is OpCodes.GC.StructGetSignExtended -> OpcodeCategory.GC
    is OpCodes.GC.StructGetZeroExtended -> OpcodeCategory.GC
    is OpCodes.GC.StructSet -> OpcodeCategory.GC
    is OpCodes.GC.RefTest -> OpcodeCategory.GC
    is OpCodes.GC.RefTestNullable -> OpcodeCategory.GC
    is OpCodes.GC.RefCast -> OpcodeCategory.GC
    is OpCodes.GC.RefCastNullable -> OpcodeCategory.GC
    is OpCodes.GC.BrOnCast -> OpcodeCategory.GC
    is OpCodes.GC.BrOnCastFail -> OpcodeCategory.GC
    is OpCodes.GC.ArrayNew -> OpcodeCategory.GC
    is OpCodes.GC.ArrayNewDefault -> OpcodeCategory.GC
    is OpCodes.GC.ArrayNewFixed -> OpcodeCategory.GC
    is OpCodes.GC.ArrayNewData -> OpcodeCategory.GC
    is OpCodes.GC.ArrayNewElement -> OpcodeCategory.GC
    is OpCodes.GC.ArrayFill -> OpcodeCategory.GC
    is OpCodes.GC.ArrayCopy -> OpcodeCategory.GC
    is OpCodes.GC.ArrayInitData -> OpcodeCategory.GC
    is OpCodes.GC.ArrayInitElem -> OpcodeCategory.GC
    is OpCodes.GC.ArrayGet -> OpcodeCategory.GC
    is OpCodes.GC.ArrayGetSigned -> OpcodeCategory.GC
    is OpCodes.GC.ArrayGetUnsigned -> OpcodeCategory.GC
    is OpCodes.GC.ArraySet -> OpcodeCategory.GC
    OpCodes.GC.ArrayLength -> OpcodeCategory.GC
    OpCodes.GC.AnyConvertExtern -> OpcodeCategory.GC
    OpCodes.GC.ExternConvertAny -> OpcodeCategory.GC
    OpCodes.GC.RefI31 -> OpcodeCategory.GC
    OpCodes.GC.I31GetS -> OpcodeCategory.GC
    OpCodes.GC.I31GetU -> OpcodeCategory.GC

    // --- Arithmetic: remaining I32 opcodes ---
    is I32.Const -> OpcodeCategory.Arithmetic
    I32.Add -> OpcodeCategory.Arithmetic
    I32.Sub -> OpcodeCategory.Arithmetic
    I32.Mul -> OpcodeCategory.Arithmetic
    I32.DivS -> OpcodeCategory.Arithmetic
    I32.DivU -> OpcodeCategory.Arithmetic
    I32.RemS -> OpcodeCategory.Arithmetic
    I32.RemU -> OpcodeCategory.Arithmetic
    I32.And -> OpcodeCategory.Arithmetic
    I32.Or -> OpcodeCategory.Arithmetic
    I32.Xor -> OpcodeCategory.Arithmetic
    I32.Shl -> OpcodeCategory.Arithmetic
    I32.ShrS -> OpcodeCategory.Arithmetic
    I32.ShrU -> OpcodeCategory.Arithmetic
    I32.Rotl -> OpcodeCategory.Arithmetic
    I32.Rotr -> OpcodeCategory.Arithmetic
    I32.Clz -> OpcodeCategory.Arithmetic
    I32.Ctz -> OpcodeCategory.Arithmetic
    I32.PopCnt -> OpcodeCategory.Arithmetic
    I32.Eqz -> OpcodeCategory.Arithmetic
    I32.Eq -> OpcodeCategory.Arithmetic
    I32.Ne -> OpcodeCategory.Arithmetic
    I32.LTS -> OpcodeCategory.Arithmetic
    I32.LTU -> OpcodeCategory.Arithmetic
    I32.LES -> OpcodeCategory.Arithmetic
    I32.LEU -> OpcodeCategory.Arithmetic
    I32.GTS -> OpcodeCategory.Arithmetic
    I32.GTU -> OpcodeCategory.Arithmetic
    I32.GES -> OpcodeCategory.Arithmetic
    I32.GEU -> OpcodeCategory.Arithmetic
    I32.WrapI64 -> OpcodeCategory.Arithmetic
    I32.Extend8S -> OpcodeCategory.Arithmetic
    I32.Extend16S -> OpcodeCategory.Arithmetic
    I32.ReinterpretF32 -> OpcodeCategory.Arithmetic
    I32.TruncF32S -> OpcodeCategory.Arithmetic
    I32.TruncF32U -> OpcodeCategory.Arithmetic
    I32.TruncF64S -> OpcodeCategory.Arithmetic
    I32.TruncF64U -> OpcodeCategory.Arithmetic
    I32.TruncSatF32S -> OpcodeCategory.Arithmetic
    I32.TruncSatF32U -> OpcodeCategory.Arithmetic
    I32.TruncSatF64S -> OpcodeCategory.Arithmetic
    I32.TruncSatF64U -> OpcodeCategory.Arithmetic

    // --- Arithmetic: remaining I64 opcodes ---
    is I64.Const -> OpcodeCategory.Arithmetic
    I64.Add -> OpcodeCategory.Arithmetic
    I64.Sub -> OpcodeCategory.Arithmetic
    I64.Mul -> OpcodeCategory.Arithmetic
    I64.DivS -> OpcodeCategory.Arithmetic
    I64.DivU -> OpcodeCategory.Arithmetic
    I64.RemS -> OpcodeCategory.Arithmetic
    I64.RemU -> OpcodeCategory.Arithmetic
    I64.And -> OpcodeCategory.Arithmetic
    I64.Or -> OpcodeCategory.Arithmetic
    I64.Xor -> OpcodeCategory.Arithmetic
    I64.Shl -> OpcodeCategory.Arithmetic
    I64.ShrS -> OpcodeCategory.Arithmetic
    I64.ShrU -> OpcodeCategory.Arithmetic
    I64.RotL -> OpcodeCategory.Arithmetic
    I64.RotR -> OpcodeCategory.Arithmetic
    I64.Clz -> OpcodeCategory.Arithmetic
    I64.Ctz -> OpcodeCategory.Arithmetic
    I64.PopCnt -> OpcodeCategory.Arithmetic
    I64.Eqz -> OpcodeCategory.Arithmetic
    I64.EQ -> OpcodeCategory.Arithmetic
    I64.NE -> OpcodeCategory.Arithmetic
    I64.LTS -> OpcodeCategory.Arithmetic
    I64.LTU -> OpcodeCategory.Arithmetic
    I64.LES -> OpcodeCategory.Arithmetic
    I64.LEU -> OpcodeCategory.Arithmetic
    I64.GTS -> OpcodeCategory.Arithmetic
    I64.GTU -> OpcodeCategory.Arithmetic
    I64.GES -> OpcodeCategory.Arithmetic
    I64.GEU -> OpcodeCategory.Arithmetic
    I64.Extend_I32_S -> OpcodeCategory.Arithmetic
    I64.Extend_I32_U -> OpcodeCategory.Arithmetic
    I64.Extend8S -> OpcodeCategory.Arithmetic
    I64.Extend16S -> OpcodeCategory.Arithmetic
    I64.Extend32S -> OpcodeCategory.Arithmetic
    I64.ReinterpretF64 -> OpcodeCategory.Arithmetic
    I64.TruncF32S -> OpcodeCategory.Arithmetic
    I64.TruncF32U -> OpcodeCategory.Arithmetic
    I64.TruncF64S -> OpcodeCategory.Arithmetic
    I64.TruncF64U -> OpcodeCategory.Arithmetic
    I64.TruncSatF32S -> OpcodeCategory.Arithmetic
    I64.TruncSatF32U -> OpcodeCategory.Arithmetic
    I64.TruncSatF64S -> OpcodeCategory.Arithmetic
    I64.TruncSatF64U -> OpcodeCategory.Arithmetic

    // --- Arithmetic: remaining F32 opcodes ---
    is F32.Const -> OpcodeCategory.Arithmetic
    F32.Add -> OpcodeCategory.Arithmetic
    F32.Sub -> OpcodeCategory.Arithmetic
    F32.Mul -> OpcodeCategory.Arithmetic
    F32.Div -> OpcodeCategory.Arithmetic
    F32.Min -> OpcodeCategory.Arithmetic
    F32.Max -> OpcodeCategory.Arithmetic
    F32.Sqrt -> OpcodeCategory.Arithmetic
    F32.Abs -> OpcodeCategory.Arithmetic
    F32.Neg -> OpcodeCategory.Arithmetic
    F32.Ceil -> OpcodeCategory.Arithmetic
    F32.Floor -> OpcodeCategory.Arithmetic
    F32.Trunc -> OpcodeCategory.Arithmetic
    F32.Nearest -> OpcodeCategory.Arithmetic
    F32.CopySign -> OpcodeCategory.Arithmetic
    F32.EQ -> OpcodeCategory.Arithmetic
    F32.NE -> OpcodeCategory.Arithmetic
    F32.LT -> OpcodeCategory.Arithmetic
    F32.GT -> OpcodeCategory.Arithmetic
    F32.LE -> OpcodeCategory.Arithmetic
    F32.GE -> OpcodeCategory.Arithmetic
    F32.DemoteF64 -> OpcodeCategory.Arithmetic
    F32.ReinterpretI32 -> OpcodeCategory.Arithmetic
    F32.ConvertI32S -> OpcodeCategory.Arithmetic
    F32.ConvertI32U -> OpcodeCategory.Arithmetic
    F32.ConvertI64S -> OpcodeCategory.Arithmetic
    F32.ConvertI64U -> OpcodeCategory.Arithmetic

    // --- Arithmetic: remaining F64 opcodes ---
    is F64.Const -> OpcodeCategory.Arithmetic
    F64.Add -> OpcodeCategory.Arithmetic
    F64.Sub -> OpcodeCategory.Arithmetic
    F64.Mul -> OpcodeCategory.Arithmetic
    F64.Div -> OpcodeCategory.Arithmetic
    F64.Min -> OpcodeCategory.Arithmetic
    F64.Max -> OpcodeCategory.Arithmetic
    F64.Sqrt -> OpcodeCategory.Arithmetic
    F64.Abs -> OpcodeCategory.Arithmetic
    F64.Neg -> OpcodeCategory.Arithmetic
    F64.Ceil -> OpcodeCategory.Arithmetic
    F64.Floor -> OpcodeCategory.Arithmetic
    F64.Trunc -> OpcodeCategory.Arithmetic
    F64.Nearest -> OpcodeCategory.Arithmetic
    F64.Copysign -> OpcodeCategory.Arithmetic
    F64.EQ -> OpcodeCategory.Arithmetic
    F64.NE -> OpcodeCategory.Arithmetic
    F64.LT -> OpcodeCategory.Arithmetic
    F64.GT -> OpcodeCategory.Arithmetic
    F64.LE -> OpcodeCategory.Arithmetic
    F64.GE -> OpcodeCategory.Arithmetic
    F64.PromoteF32 -> OpcodeCategory.Arithmetic
    F64.ReinterpretI64 -> OpcodeCategory.Arithmetic
    F64.ConvertI32S -> OpcodeCategory.Arithmetic
    F64.ConvertI32U -> OpcodeCategory.Arithmetic
    F64.ConvertI64S -> OpcodeCategory.Arithmetic
    F64.ConvertI64U -> OpcodeCategory.Arithmetic
    is UnparsedOpCode -> OpcodeCategory.Unparsed

    // --- SIMD: every 0xFD-prefixed vector instruction (fixed-width + relaxed). ---
    // Marker interface VectorOpCode is implemented by every member of
    // opcodes.vector.{V128, i8x16, i16x8, i32x4, i64x2, f32x4, f64x2}.
    is VectorOpCode -> OpcodeCategory.SIMD

    // --- Other: Local, Global, Table, Ref, Reserved ---
    else -> OpcodeCategory.Other
}