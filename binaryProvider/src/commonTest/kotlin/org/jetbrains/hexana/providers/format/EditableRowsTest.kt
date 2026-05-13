package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks in the Phase C inline-editable subset. If a future change extends
 * the encoder to a new family, the corresponding op moves from the
 * read-only block to the editable block here.
 */
class EditableRowsTest {

    private fun opEditable(op: OpCode) =
        assertTrue(EditableRows.isEditable(op), "expected $op to be editable")

    private fun opNotEditable(op: OpCode) =
        assertFalse(EditableRows.isEditable(op), "expected $op to NOT be editable")

    // -- Editable subset ----------------------------------------------------

    @Test
    fun numeric_constants_are_editable() {
        opEditable(I32.Const(value = 0))
        opEditable(I64.Const(value = 0L))
        opEditable(F32.Const(initValue = 0f))
        opEditable(F64.Const(initValue = 0.0))
    }

    @Test
    fun local_and_global_access_are_editable() {
        opEditable(OpCodes.Local.Get(value = 0u))
        opEditable(OpCodes.Local.Set(value = 0u))
        opEditable(OpCodes.Local.Tee(value = 0u))
        opEditable(OpCodes.Global.Get(value = 0u))
        opEditable(OpCodes.Global.Set(value = 0u))
    }

    @Test
    fun direct_call_is_editable() {
        opEditable(OpCodes.Flow.Call(value = 0u))
    }

    @Test
    fun simple_branches_are_editable() {
        opEditable(OpCodes.Flow.Br(value = 0u))
        opEditable(OpCodes.Flow.BrIf(value = 0u))
    }

    @Test
    fun memory_load_store_are_editable() {
        val arg = MemArg(align = 0, offset = 0)
        opEditable(OpCodes.Memory.i32.Load(value = arg))
        opEditable(OpCodes.Memory.i32.Store(value = arg))
        opEditable(OpCodes.Memory.i64.Load(value = arg))
        opEditable(OpCodes.Memory.i64.Store(value = arg))
        opEditable(OpCodes.Memory.f32.Load(value = arg))
        opEditable(OpCodes.Memory.f64.Store(value = arg))
    }

    // -- D.1: parametric / no-immediate ops --------------------------------

    @Test
    fun parametric_ops_are_editable() {
        opEditable(OpCodes.ParametricInstructions.Drop)
        opEditable(OpCodes.ParametricInstructions.Nop)
        opEditable(OpCodes.ParametricInstructions.Unreachable)
        opEditable(OpCodes.ParametricInstructions.Select)
        opEditable(OpCodes.Flow.Return)
    }

    // -- D.1: numeric arithmetic / compare / convert ops -------------------

    @Test
    fun i32_arithmetic_ops_are_editable() {
        opEditable(I32.Add)
        opEditable(I32.Sub)
        opEditable(I32.Mul)
        opEditable(I32.DivS)
        opEditable(I32.DivU)
        opEditable(I32.RemS)
        opEditable(I32.RemU)
        opEditable(I32.And)
        opEditable(I32.Or)
        opEditable(I32.Xor)
        opEditable(I32.Shl)
        opEditable(I32.ShrS)
        opEditable(I32.ShrU)
        opEditable(I32.Rotl)
        opEditable(I32.Rotr)
        opEditable(I32.Clz)
        opEditable(I32.Ctz)
        opEditable(I32.PopCnt)
    }

    @Test
    fun i32_compare_ops_are_editable() {
        opEditable(I32.Eqz)
        opEditable(I32.Eq)
        opEditable(I32.Ne)
        opEditable(I32.LTS)
        opEditable(I32.LTU)
        opEditable(I32.GTS)
        opEditable(I32.GTU)
        opEditable(I32.LES)
        opEditable(I32.LEU)
        opEditable(I32.GES)
        opEditable(I32.GEU)
    }

    @Test
    fun i32_convert_ops_are_editable() {
        opEditable(I32.WrapI64)
        opEditable(I32.TruncF32S)
        opEditable(I32.TruncF32U)
        opEditable(I32.TruncF64S)
        opEditable(I32.TruncF64U)
        opEditable(I32.TruncSatF32S)
        opEditable(I32.TruncSatF32U)
        opEditable(I32.TruncSatF64S)
        opEditable(I32.TruncSatF64U)
        opEditable(I32.ReinterpretF32)
        opEditable(I32.Extend8S)
        opEditable(I32.Extend16S)
    }

    @Test
    fun i64_arithmetic_ops_are_editable() {
        opEditable(I64.Add)
        opEditable(I64.Sub)
        opEditable(I64.Mul)
        opEditable(I64.DivS)
        opEditable(I64.DivU)
        opEditable(I64.RemS)
        opEditable(I64.RemU)
        opEditable(I64.And)
        opEditable(I64.Or)
        opEditable(I64.Xor)
        opEditable(I64.Shl)
        opEditable(I64.ShrS)
        opEditable(I64.ShrU)
        opEditable(I64.RotL)
        opEditable(I64.RotR)
        opEditable(I64.Clz)
        opEditable(I64.Ctz)
        opEditable(I64.PopCnt)
    }

    @Test
    fun i64_compare_ops_are_editable() {
        opEditable(I64.Eqz)
        opEditable(I64.EQ)
        opEditable(I64.NE)
        opEditable(I64.LTS)
        opEditable(I64.LTU)
        opEditable(I64.GTS)
        opEditable(I64.GTU)
        opEditable(I64.LES)
        opEditable(I64.LEU)
        opEditable(I64.GES)
        opEditable(I64.GEU)
    }

    @Test
    fun i64_convert_ops_are_editable() {
        opEditable(I64.Extend_I32_S)
        opEditable(I64.Extend_I32_U)
        opEditable(I64.TruncF32S)
        opEditable(I64.TruncF32U)
        opEditable(I64.TruncF64S)
        opEditable(I64.TruncF64U)
        opEditable(I64.TruncSatF32S)
        opEditable(I64.TruncSatF32U)
        opEditable(I64.TruncSatF64S)
        opEditable(I64.TruncSatF64U)
        opEditable(I64.ReinterpretF64)
        opEditable(I64.Extend8S)
        opEditable(I64.Extend16S)
        opEditable(I64.Extend32S)
    }

    @Test
    fun f32_ops_are_editable() {
        opEditable(F32.Add)
        opEditable(F32.Sub)
        opEditable(F32.Mul)
        opEditable(F32.Div)
        opEditable(F32.Abs)
        opEditable(F32.Neg)
        opEditable(F32.Sqrt)
        opEditable(F32.Ceil)
        opEditable(F32.Floor)
        opEditable(F32.Trunc)
        opEditable(F32.Nearest)
        opEditable(F32.Min)
        opEditable(F32.Max)
        opEditable(F32.CopySign)
        opEditable(F32.EQ)
        opEditable(F32.NE)
        opEditable(F32.LT)
        opEditable(F32.GT)
        opEditable(F32.LE)
        opEditable(F32.GE)
        opEditable(F32.ConvertI32S)
        opEditable(F32.ConvertI32U)
        opEditable(F32.ConvertI64S)
        opEditable(F32.ConvertI64U)
        opEditable(F32.DemoteF64)
        opEditable(F32.ReinterpretI32)
    }

    @Test
    fun f64_ops_are_editable() {
        opEditable(F64.Add)
        opEditable(F64.Sub)
        opEditable(F64.Mul)
        opEditable(F64.Div)
        opEditable(F64.Abs)
        opEditable(F64.Neg)
        opEditable(F64.Sqrt)
        opEditable(F64.Ceil)
        opEditable(F64.Floor)
        opEditable(F64.Trunc)
        opEditable(F64.Nearest)
        opEditable(F64.Min)
        opEditable(F64.Max)
        opEditable(F64.Copysign)
        opEditable(F64.EQ)
        opEditable(F64.NE)
        opEditable(F64.LT)
        opEditable(F64.GT)
        opEditable(F64.LE)
        opEditable(F64.GE)
        opEditable(F64.ConvertI32S)
        opEditable(F64.ConvertI32U)
        opEditable(F64.ConvertI64S)
        opEditable(F64.ConvertI64U)
        opEditable(F64.PromoteF32)
        opEditable(F64.ReinterpretI64)
    }

    // -- Read-only (structural / variable-length / unsupported) ------------

    @Test
    fun structural_control_flow_is_read_only() {
        // We can't construct full Block/Loop/If instances without children;
        // verifying via the singleton terminal/end variants is sufficient.
        opNotEditable(OpCodes.Flow.Terminal)
    }

    @Test
    fun memory_size_grow_are_read_only() {
        // Single-byte-immediate ops (the memidx, almost always 0) — could
        // be added later but excluded from the initial subset for clarity.
        opNotEditable(OpCodes.Memory.Size(value = 0u))
        opNotEditable(OpCodes.Memory.Grow(value = 0u))
    }
}
