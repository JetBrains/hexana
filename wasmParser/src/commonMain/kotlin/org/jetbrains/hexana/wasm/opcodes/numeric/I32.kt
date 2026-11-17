package org.jetbrains.hexana.wasm.opcodes.numeric

import org.jetbrains.hexana.wasm.opcodes.OpCodeWithValue

open class I32 {
    object Shl : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x74u
    }

    object PopCnt : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x69u
    }

    object And : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x71u
    }

    object Or : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x72u
    }

    object Xor : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x73u
    }

    object Add : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x6Au
    }

    object Sub : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x6Bu
    }

    object Mul : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x6Cu
    }

    object Rotl : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x77u
    }

    object Rotr : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x78u
    }

    object Clz : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x67u
    }

    object Ctz : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x68u
    }

    object Eqz : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x45u
    }

    object Eq : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x46u
    }

    object Ne : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x47u
    }

    object LTS : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x48u
    }

    object LTU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x49u
    }

    object LES : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x4Cu
    }

    object LEU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x4Du
    }

    object GTS : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x4Au
    }

    object GES : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x4Eu
    }

    object GTU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x4Bu
    }

    object GEU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x4Fu
    }

    object RemS : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x6Fu
    }

    object RemU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x70u
    }

    object WrapI64 : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xA7u
    }

    object Extend8S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xC0u
    }

    object Extend16S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xC1u
    }

    object ReinterpretF32 : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xBCu
    }

    object TruncF32S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xA8u
    }

    object TruncF32U : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xA9u
    }

    object TruncF64S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xAAu
    }

    object TruncF64U : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xABu
    }

    object DivU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x6Eu
    }

    object DivS : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x6Du
    }

    object ShrU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x76u
    }

    object ShrS : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x75u
    }

    // FC Multibyte Opcodes
    object TruncSatF32S : OpCodeWithValue<Unit>(Unit) {
        const val code: UInt = 0x00u
    }

    object TruncSatF32U : OpCodeWithValue<Unit>(Unit) {
        const val code: UInt = 0x01u
    }

    object TruncSatF64S : OpCodeWithValue<Unit>(Unit) {
        const val code: UInt = 0x02u
    }

    object TruncSatF64U : OpCodeWithValue<Unit>(Unit) {
        const val code: UInt = 0x03u
    }

    class Const(value: Int, offset: Int = 0) : OpCodeWithValue<Int>(value, offset) {
        companion object {
            const val code: UByte = 0x41u
        }
    }
}