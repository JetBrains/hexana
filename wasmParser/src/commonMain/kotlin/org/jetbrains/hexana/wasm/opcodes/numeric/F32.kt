package org.jetbrains.hexana.wasm.opcodes.numeric

import org.jetbrains.hexana.wasm.opcodes.OpCodeWithValue

open class F32 {
    class Const(initValue: Float, offset: Int = 0) : OpCodeWithValue<Float>(initValue, offset) {
        companion object {
            const val code: UByte = 0x43u
        }
    }

    object Nearest : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x90u
    }

    object NE : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x5Cu
    }

    object LE : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x5Fu
    }

    object Ceil : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x8du
    }

    object Floor : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x8eu
    }

    object Sub : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x93u
    }

    object Mul : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x94u
    }

    object Trunc : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x8fu
    }

    object CopySign : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x98u
    }

    object Sqrt : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x91u
    }

    object Add : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x92u
    }

    object Div : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x95u
    }

    object Min : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x96u
    }

    object Max : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x97u
    }

    object DemoteF64 : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xB6u
    }

    object ReinterpretI32 : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xBEu
    }

    object Neg : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x8Cu
    }

    object ConvertI32S : OpCodeWithValue<Unit>(Unit) {
        val code: UByte = 0xB2u
    }

    object ConvertI32U : OpCodeWithValue<Unit>(Unit) {
        val code: UByte = 0xB3u
    }

    object ConvertI64S : OpCodeWithValue<Unit>(Unit) {
        val code: UByte = 0xB4u
    }

    object ConvertI64U : OpCodeWithValue<Unit>(Unit) {
        val code: UByte = 0xB5u
    }

    object EQ : OpCodeWithValue<Unit>(Unit) {
        val code: UByte = 0x5Bu
    }

    object GE : OpCodeWithValue<Unit>(Unit) {
        val code: UByte = 0x60u
    }

    object LT : OpCodeWithValue<Unit>(Unit) {
        val code: UByte = 0x5Du
    }

    object GT : OpCodeWithValue<Unit>(Unit) {
        val code: UByte = 0x5Eu
    }

    object Abs : OpCodeWithValue<Unit>(Unit) {
        val code: UByte = 0x8Bu
    }
}