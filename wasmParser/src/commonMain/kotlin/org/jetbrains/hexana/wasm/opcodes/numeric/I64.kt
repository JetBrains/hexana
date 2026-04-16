package org.jetbrains.hexana.wasm.opcodes.numeric

import org.jetbrains.hexana.wasm.opcodes.OpCodeWithValue

open class I64 {
    // supposed to be signed long ???
    class Const(value: Long) : OpCodeWithValue<Long>(value) {
        companion object {
            const val code: UByte = 0x42u
        }
    }

    object Eqz : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x50u
    }

    object EQ : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x51u
    }

    object NE : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x52u
    }

    object LTS : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x53u
    }

    object LTU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x54u
    }

    object GTS : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x55u
    }

    object GTU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x56u
    }

    object LES : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x57u
    }

    object LEU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x58u
    }

    object GES : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x59u
    }

    object GEU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x5Au
    }

    object Clz : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x79u
    }

    object Ctz : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x7Au
    }

    object PopCnt : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x7Bu
    }

    object Add : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x7Cu
    }

    object Sub : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x7Du
    }

    object Mul : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x7Eu
    }

    object DivS : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x7Fu
    }

    object DivU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x80u
    }

    object RemS : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x81u
    }

    object RemU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x82u
    }

    object And : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x83u
    }

    object Or : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x84u
    }

    object Xor : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x85u
    }

    object Shl : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x86u
    }

    object ShrS : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x87u
    }

    object ShrU : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x88u
    }

    object RotL : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x89u
    }

    object RotR : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x8Au
    }

    object Extend_I32_S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xACu
    }

    object Extend_I32_U : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xADu
    }

    object TruncF32S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xAEu
    }

    object TruncF32U : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xAFu
    }

    object TruncF64S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xB0u
    }

    object TruncF64U : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xB1u
    }

    object ReinterpretF64 : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xBDu
    }

    object Extend8S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xC2u
    }

    object Extend16S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xC3u
    }

    object Extend32S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0xC4u
    }

    // FC Multibyte Opcodes
    object TruncSatF32S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x04u
    }

    object TruncSatF32U : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x05u
    }

    object TruncSatF64S : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x06u
    }

    object TruncSatF64U : OpCodeWithValue<Unit>(Unit) {
        const val code: UByte = 0x07u
    }
}