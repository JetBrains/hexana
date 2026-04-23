package org.jetbrains.hexana.wasm.opcodes.numeric

import org.jetbrains.hexana.wasm.opcodes.OpCodeWithValue

open class F64 {
    object EQ : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x61u
    }

    object NE : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x62u
    }

    object LT : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x63u
    }

    object GT : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x64u
    }

    object LE : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x65u
    }

    object GE : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x66u
    }

    object ConvertI32S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xB7u
    }

    object ConvertI32U : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xB8u
    }

    object ConvertI64S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xB9u
    }

    object ConvertI64U : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xBAu
    }

    object PromoteF32 : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xBBu
    }

    object ReinterpretI64 : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xBFu
    }

    object Add : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xA0u
    }

    object Sub : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xA1u
    }

    object Mul : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xA2u
    }

    object Div : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xA3u
    }

    object Min : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xA4u
    }

    object Max : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xA5u
    }

    object Copysign : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xA6u
    }

    object Abs : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x99u
    }

    object Neg : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x9Au
    }

    object Ceil : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x9Bu
    }

    object Floor : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x9Cu
    }

    object Trunc : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x9Du
    }

    object Nearest : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x9Eu
    }

    object Sqrt : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x9Fu
    }

    class Const(initValue: Double, offset: Int = 0) : OpCodeWithValue<Double>(initValue, offset) {
        companion object {
            const val code: UByte = 0x44u
        }
    }
}