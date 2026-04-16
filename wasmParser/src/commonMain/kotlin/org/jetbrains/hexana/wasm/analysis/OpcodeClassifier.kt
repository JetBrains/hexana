package org.jetbrains.hexana.wasm.analysis

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.UnparsedOpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f64

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
    // ReturnCall is an object that extends OpCodeWithValue<Unit>; check identity
    OpCodes.Flow.ReturnCall -> OpcodeCategory.Call

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
    OpCodes.ParametricInstructions.SelectWithType -> OpcodeCategory.Control
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
    is OpCodes.GC.StructGet -> OpcodeCategory.GC
    is OpCodes.GC.StructGetSignExtended -> OpcodeCategory.GC
    is OpCodes.GC.StructGetZeroExtended -> OpcodeCategory.GC
    is OpCodes.GC.StructSet -> OpcodeCategory.GC
    is OpCodes.GC.RefTest -> OpcodeCategory.GC
    is OpCodes.GC.RefCast -> OpcodeCategory.GC
    is OpCodes.GC.RefCastNullable -> OpcodeCategory.GC
    is OpCodes.GC.BrOnCastFail -> OpcodeCategory.GC
    is OpCodes.GC.ArrayNewDefault -> OpcodeCategory.GC
    is OpCodes.GC.ArrayNewFixed -> OpcodeCategory.GC
    is OpCodes.GC.ArrayNewData -> OpcodeCategory.GC
    is OpCodes.GC.ArrayCopy -> OpcodeCategory.GC
    is OpCodes.GC.ArrayGet -> OpcodeCategory.GC
    is OpCodes.GC.ArrayGetSigned -> OpcodeCategory.GC
    is OpCodes.GC.ArrayGetUnsigned -> OpcodeCategory.GC
    is OpCodes.GC.ArraySet -> OpcodeCategory.GC
    OpCodes.GC.ArrayLength -> OpcodeCategory.GC
    OpCodes.GC.AnyConvertExtern -> OpcodeCategory.GC
    OpCodes.GC.ExternConvertAny -> OpcodeCategory.GC

    // --- Arithmetic: remaining I32 opcodes ---
    is OpCodes.NumericInstructions.i32.Const -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Add -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Sub -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Mul -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.DivS -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.DivU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.RemS -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.RemU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.And -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Or -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Xor -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Shl -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.ShrS -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.ShrU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Rotl -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Rotr -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Clz -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Ctz -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.PopCnt -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Eqz -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Eq -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Ne -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.LTS -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.LTU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.LES -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.LEU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.GTS -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.GTU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.GES -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.GEU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.WrapI64 -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Extend8S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.Extend16S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.ReinterpretF32 -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.TruncF32S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.TruncF32U -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.TruncF64S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.TruncF64U -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.TruncSatF32S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.TruncSatF32U -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.TruncSatF64S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i32.TruncSatF64U -> OpcodeCategory.Arithmetic

    // --- Arithmetic: remaining I64 opcodes ---
    is OpCodes.NumericInstructions.i64.Const -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Add -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Sub -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Mul -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.DivS -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.DivU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.RemS -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.RemU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.And -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Or -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Xor -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Shl -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.ShrS -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.ShrU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.RotL -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.RotR -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Clz -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Ctz -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.PopCnt -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Eqz -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.EQ -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.NE -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.LTS -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.LTU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.LES -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.LEU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.GTS -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.GTU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.GES -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.GEU -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Extend_I32_S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Extend_I32_U -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Extend8S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Extend16S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.Extend32S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.ReinterpretF64 -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.TruncF32S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.TruncF32U -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.TruncF64S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.TruncF64U -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.TruncSatF32S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.TruncSatF32U -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.TruncSatF64S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.i64.TruncSatF64U -> OpcodeCategory.Arithmetic

    // --- Arithmetic: remaining F32 opcodes ---
    is OpCodes.NumericInstructions.F32.Const -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Add -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Sub -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Mul -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Div -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Min -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Max -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Sqrt -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Abs -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Neg -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Ceil -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Floor -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Trunc -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.Nearest -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.CopySign -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.EQ -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.NE -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.LT -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.GT -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.LE -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.GE -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.DemoteF64 -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.ReinterpretI32 -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.ConvertI32S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.ConvertI32U -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.ConvertI64S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F32.ConvertI64U -> OpcodeCategory.Arithmetic

    // --- Arithmetic: remaining F64 opcodes ---
    is OpCodes.NumericInstructions.F64.Const -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Add -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Sub -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Mul -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Div -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Min -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Max -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Sqrt -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Abs -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Neg -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Ceil -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Floor -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Trunc -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Nearest -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.Copysign -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.EQ -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.NE -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.LT -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.GT -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.LE -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.GE -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.PromoteF32 -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.ReinterpretI64 -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.ConvertI32S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.ConvertI32U -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.ConvertI64S -> OpcodeCategory.Arithmetic
    OpCodes.NumericInstructions.F64.ConvertI64U -> OpcodeCategory.Arithmetic
    is UnparsedOpCode -> OpcodeCategory.Unparsed

    // --- Other: Local, Global, Table, Ref, Reserved ---
    else -> OpcodeCategory.Other
}