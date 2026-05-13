package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke-tests that [watFor] produces correct WAT mnemonics for representative
 * numeric singleton opcodes. Prior to the identity-map fix, all of these
 * rendered as `(; <ClassName> ;)` because the singleton objects do not extend
 * their enclosing open class.
 */
class WatMnemonicsTest {

    @Test
    fun i32Add() = assertEquals("i32.add", watFor(I32.Add))

    @Test
    fun i32ShrU() = assertEquals("i32.shr_u", watFor(I32.ShrU))

    @Test
    fun i32ShrS() = assertEquals("i32.shr_s", watFor(I32.ShrS))

    @Test
    fun i32WrapI64() = assertEquals("i32.wrap_i64", watFor(I32.WrapI64))

    @Test
    fun i32TruncSatF64S() = assertEquals("i32.trunc_sat_f64_s", watFor(I32.TruncSatF64S))

    @Test
    fun i64ShrS() = assertEquals("i64.shr_s", watFor(I64.ShrS))

    @Test
    fun i64RotL() = assertEquals("i64.rotl", watFor(I64.RotL))

    @Test
    fun i64ExtendI32S() = assertEquals("i64.extend_i32_s", watFor(I64.Extend_I32_S))

    @Test
    fun i64TruncSatF32U() = assertEquals("i64.trunc_sat_f32_u", watFor(I64.TruncSatF32U))

    @Test
    fun f32Sqrt() = assertEquals("f32.sqrt", watFor(F32.Sqrt))

    @Test
    fun f32Copysign() = assertEquals("f32.copysign", watFor(F32.CopySign))

    @Test
    fun f32DemoteF64() = assertEquals("f32.demote_f64", watFor(F32.DemoteF64))

    @Test
    fun f64ConvertI32S() = assertEquals("f64.convert_i32_s", watFor(F64.ConvertI32S))

    @Test
    fun f64PromoteF32() = assertEquals("f64.promote_f32", watFor(F64.PromoteF32))

    @Test
    fun f64Copysign() = assertEquals("f64.copysign", watFor(F64.Copysign))
}
