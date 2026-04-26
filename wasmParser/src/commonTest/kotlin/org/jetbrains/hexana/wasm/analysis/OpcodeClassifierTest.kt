package org.jetbrains.hexana.wasm.analysis

import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f64
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import kotlin.test.Test
import kotlin.test.assertEquals

class OpcodeClassifierTest {
    @Test
    fun `call opcode classified as Call`() {
        assertEquals(OpcodeCategory.Call, classifyOpcode(OpCodes.Flow.Call(0u)))
    }

    @Test
    fun `call indirect classified as Call`() {
        assertEquals(OpcodeCategory.Call, classifyOpcode(OpCodes.Flow.CallIndirect(0, 0)))
    }

    @Test
    fun `return call classified as Call`() {
        assertEquals(OpcodeCategory.Call, classifyOpcode(OpCodes.Flow.ReturnCall(0u)))
    }

    @Test
    fun `return call indirect classified as Call`() {
        assertEquals(OpcodeCategory.Call, classifyOpcode(OpCodes.Flow.ReturnCallIndirect(0u, 0u)))
    }

    @Test
    fun `call ref classified as Call`() {
        assertEquals(OpcodeCategory.Call, classifyOpcode(OpCodes.Flow.CallRef(0u)))
    }

    @Test
    fun `return call ref classified as Call`() {
        assertEquals(OpcodeCategory.Call, classifyOpcode(OpCodes.Flow.ReturnCallRef(0u)))
    }

    @Test
    fun `i32 add classified as Arithmetic`() {
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.Add))
    }

    @Test
    fun `i32 const classified as Arithmetic`() {
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I32.Const(42)))
    }

    @Test
    fun `i64 mul classified as Arithmetic`() {
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(I64.Mul))
    }

    @Test
    fun `f32 sqrt classified as Arithmetic`() {
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(F32.Sqrt))
    }

    @Test
    fun `f64 const classified as Arithmetic`() {
        assertEquals(OpcodeCategory.Arithmetic, classifyOpcode(F64.Const(3.14)))
    }

    @Test
    fun `block classified as Control`() {
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.Flow.Block(null, emptyList())))
    }

    @Test
    fun `br classified as Control`() {
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.Flow.Br(0u)))
    }

    @Test
    fun `terminal classified as Control`() {
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.Flow.Terminal))
    }

    @Test
    fun `return classified as Control`() {
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.Flow.Return))
    }

    @Test
    fun `nop classified as Control`() {
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.ParametricInstructions.Nop))
    }

    @Test
    fun `select classified as Control`() {
        assertEquals(OpcodeCategory.Control, classifyOpcode(OpCodes.ParametricInstructions.Select))
    }

    @Test
    fun `i32 load classified as Memory`() {
        assertEquals(OpcodeCategory.Memory, classifyOpcode(i32.Load(MemArg(0, 0))))
    }

    @Test
    fun `i64 store classified as Memory`() {
        assertEquals(OpcodeCategory.Memory, classifyOpcode(i64.Store(MemArg(0, 0))))
    }

    @Test
    fun `i32 load8_s classified as Memory`() {
        assertEquals(OpcodeCategory.Memory, classifyOpcode(i32.Load8_S(MemArg(0, 0))))
    }

    @Test
    fun `f32 load classified as Memory`() {
        assertEquals(OpcodeCategory.Memory, classifyOpcode(f32.Load(MemArg(0, 0))))
    }

    @Test
    fun `f64 store classified as Memory`() {
        assertEquals(OpcodeCategory.Memory, classifyOpcode(f64.Store(MemArg(0, 0))))
    }

    @Test
    fun `memory size classified as Memory`() {
        assertEquals(OpcodeCategory.Memory, classifyOpcode(OpCodes.Memory.Size(0u)))
    }

    @Test
    fun `memory grow classified as Memory`() {
        assertEquals(OpcodeCategory.Memory, classifyOpcode(OpCodes.Memory.Grow(0u)))
    }

    @Test
    fun `gc struct_new classified as GC`() {
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.StructNew(0u)))
    }

    @Test
    fun `gc struct_get classified as GC`() {
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.StructGet(0u, 0u)))
    }

    @Test
    fun `gc array_get classified as GC`() {
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayGet(0u)))
    }

    @Test
    fun `gc array_length classified as GC`() {
        assertEquals(OpcodeCategory.GC, classifyOpcode(OpCodes.GC.ArrayLength))
    }

    @Test
    fun `local get classified as Other`() {
        assertEquals(OpcodeCategory.Other, classifyOpcode(OpCodes.Local.Get(0u)))
    }

    @Test
    fun `global set classified as Other`() {
        assertEquals(OpcodeCategory.Other, classifyOpcode(OpCodes.Global.Set(0u)))
    }

    @Test
    fun `table get classified as Other`() {
        assertEquals(OpcodeCategory.Other, classifyOpcode(OpCodes.Table.Get(0u)))
    }

    @Test
    fun `ref is_null classified as Other`() {
        assertEquals(OpcodeCategory.Other, classifyOpcode(OpCodes.Ref.IsNull))
    }
}