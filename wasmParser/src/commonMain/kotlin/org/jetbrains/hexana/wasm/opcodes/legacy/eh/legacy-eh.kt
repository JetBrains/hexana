package org.jetbrains.hexana.wasm.opcodes.legacy.eh

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithValue
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.StandardTypesParser

object LegacyEH {
    class Try(type: StandardTypesParser.WasmBlockType?, value: List<OpCode>, off: Int) : OpCodes.Flow.Block(type, value, off) {
        companion object {
            const val code: UByte = 0x06u
        }
    }

    class Catch(index: Int, off: Int): OpCodeWithValue<Int>(index, off) {
        companion object {
            const val code: UByte = 0x07u
        }
    }

    class CatchAll(off: Int): OpCodeWithOffset(off) {
        companion object {
            const val code: UByte = 0x19u
        }
    }

    class Delegate(relativeDepth: UInt, off: Int): OpCodeWithValue<UInt>(relativeDepth, off) {
        companion object {
            const val code: UByte = 0x18u
        }
    }

    class Rethrow(relativeDepth: UInt, off: Int): OpCodeWithValue<UInt>(relativeDepth, off) {
        companion object {
            const val code: UByte = 0x09u
        }
    }
}