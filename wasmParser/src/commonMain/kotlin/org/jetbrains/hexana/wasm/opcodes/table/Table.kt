package org.jetbrains.hexana.wasm.opcodes.table

import org.jetbrains.hexana.wasm.opcodes.OpCodeWithTwoValue
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithValue

object Table {
    class Get(value: UInt, offset: Int = 0) : OpCodeWithValue<UInt>(value, offset) {
        companion object {
            const val code: UByte = 0x25u
        }
    }

    class Set(value: UInt, offset: Int = 0) : OpCodeWithValue<UInt>(value, offset) {
        companion object {
            const val code: UByte = 0x26u
        }
    }

    class Init(elementIdx: UInt, tableIdx: UInt, offset: Int) : OpCodeWithTwoValue<UInt, UInt>(elementIdx, tableIdx, offset) {
        companion object {
            const val code: UInt = 12u
        }
    }

    class Copy(tableIdx0: UInt, tableIdx1: UInt, offset: Int) : OpCodeWithTwoValue<UInt, UInt>(tableIdx0, tableIdx1, offset) {
        companion object {
            const val code: UInt = 14u
        }
    }

    class Grow(tableIdx: UInt, offset: Int) : OpCodeWithValue<UInt>(tableIdx, offset) {
        companion object {
            const val code: UInt = 15u
        }
    }

    class Size(tableIdx: UInt, offset: Int) : OpCodeWithValue<UInt>(tableIdx, offset) {
        companion object {
            const val code: UInt = 16u
        }
    }

    class Fill(tableIdx: UInt, offset: Int) : OpCodeWithValue<UInt>(tableIdx, offset) {
        companion object {
            const val code: UInt = 17u
        }
    }
}

object Element {
    class Drop(elementIdx: UInt, offset: Int): OpCodeWithValue<UInt>(elementIdx, offset) {
        companion object {
            const val code: UInt = 13u
        }
    }
}
