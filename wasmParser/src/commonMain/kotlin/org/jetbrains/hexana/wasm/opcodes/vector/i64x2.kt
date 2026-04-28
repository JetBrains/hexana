package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.VectorOpCode

object i64x2 {
    class ExtractLaneU(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 29u
        }
    }

    class ReplaceLine(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 30u
        }
    }

    object Splat: OpCode, VectorOpCode {
        const val code: UInt = 18u
    }

    object Eq: OpCode, VectorOpCode {
        const val code: UInt = 214u
    }

    object Ne: OpCode, VectorOpCode {
        const val code: UInt = 215u
    }

    object LtS: OpCode, VectorOpCode {
        const val code: UInt = 216u
    }

    object GtS: OpCode, VectorOpCode {
        const val code: UInt = 217u
    }

    object LeS: OpCode, VectorOpCode {
        const val code: UInt = 218u
    }

    object GeS: OpCode, VectorOpCode {
        const val code: UInt = 219u
    }

    /**/
    object Abs: OpCode, VectorOpCode {
        const val code: UInt = 192u
    }

    object Neg: OpCode, VectorOpCode {
        const val code: UInt = 193u
    }

    object AllTrue: OpCode, VectorOpCode {
        const val code: UInt = 195u
    }

    object BitMask: OpCode, VectorOpCode {
        const val code: UInt = 196u
    }

    object ExtendLowSi32x4: OpCode, VectorOpCode {
        const val code: UInt = 199u
    }

    object ExtendHighSi32x4: OpCode, VectorOpCode {
        const val code: UInt = 200u
    }

    object ExtendLowUi32x4: OpCode, VectorOpCode {
        const val code: UInt = 201u
    }

    object ExtendHighUi32x4: OpCode, VectorOpCode {
        const val code: UInt = 202u
    }

    object Shl: OpCode, VectorOpCode {
        const val code: UInt = 203u
    }

    object ShrS: OpCode, VectorOpCode {
        const val code: UInt = 204u
    }

    object ShrU: OpCode, VectorOpCode {
        const val code: UInt = 205u
    }

    object Add: OpCode, VectorOpCode {
        const val code: UInt = 206u
    }

    object Sub: OpCode, VectorOpCode {
        const val code: UInt = 209u
    }

    object Mul: OpCode, VectorOpCode {
        const val code: UInt = 213u
    }

    object ExtmulLowSi32x4: OpCode, VectorOpCode {
        const val code: UInt = 220u
    }

    object ExtmulHighSi32x4: OpCode, VectorOpCode {
        const val code: UInt = 221u
    }

    object ExtmulLowUi32x4: OpCode, VectorOpCode {
        const val code: UInt = 222u
    }

    object ExtmulHighUi32x4: OpCode, VectorOpCode {
        const val code: UInt = 223u
    }

    /**/
    object RelaxedLaneselect: OpCode, VectorOpCode {
        const val code: UInt = 268u
    }
}