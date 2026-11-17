package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.VectorOpCode

object i8x16 {
    class Shuffle(
        val laneIdx0: UByte,
        val laneIdx1: UByte,
        val laneIdx2: UByte,
        val laneIdx3: UByte,
        val laneIdx4: UByte,
        val laneIdx5: UByte,
        val laneIdx6: UByte,
        val laneIdx7: UByte,
        val laneIdx8: UByte,
        val laneIdx9: UByte,
        val laneIdxA: UByte,
        val laneIdxB: UByte,
        val laneIdxC: UByte,
        val laneIdxD: UByte,
        val laneIdxE: UByte,
        val laneIdxF: UByte,
        offset: Int = 0,
    ): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 13u
        }
    }

    object Swizzle : OpCode, VectorOpCode {
        const val code: UInt = 14u
    }

    class ExtractLaneS(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code:UInt = 21u
        }
    }

    class ExtractLaneU(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 22u
        }
    }

    class ReplaceLine(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 23u
        }
    }

    object Splat: OpCode, VectorOpCode {
        const val code: UInt = 15u
    }

    object Eq: OpCode, VectorOpCode {
        const val code: UInt = 35u
    }

    object Ne: OpCode, VectorOpCode {
        const val code: UInt = 36u
    }

    object LtS: OpCode, VectorOpCode {
        const val code: UInt = 37u
    }

    object LtU: OpCode, VectorOpCode {
        const val code: UInt = 38u
    }

    object GtS: OpCode, VectorOpCode {
        const val code: UInt = 39u
    }

    object GtU: OpCode, VectorOpCode {
        const val code: UInt = 40u
    }

    object LeS: OpCode, VectorOpCode {
        const val code: UInt = 41u
    }

    object LeU: OpCode, VectorOpCode {
        const val code: UInt = 42u
    }

    object GeS: OpCode, VectorOpCode {
        const val code: UInt = 43u
    }

    object GeU: OpCode, VectorOpCode {
        const val code: UInt = 44u
    }

    /**/
    object Abs: OpCode, VectorOpCode {
        const val code: UInt = 96u
    }

    object Neg: OpCode, VectorOpCode {
        const val code: UInt = 97u
    }

    object PopCnt: OpCode, VectorOpCode {
        const val code: UInt = 98u
    }

    object AllTrue: OpCode, VectorOpCode {
        const val code: UInt = 99u
    }

    object BitMask: OpCode, VectorOpCode {
        const val code: UInt = 100u
    }

    object NarrowI16x8S: OpCode, VectorOpCode {
        const val code: UInt = 101u
    }

    object NarrowI16x8U: OpCode, VectorOpCode {
        const val code: UInt = 102u
    }

    object Shl: OpCode, VectorOpCode {
        const val code: UInt = 107u
    }

    object ShrS: OpCode, VectorOpCode {
        const val code: UInt = 108u
    }

    object ShrU: OpCode, VectorOpCode {
        const val code: UInt = 109u
    }

    object Add: OpCode, VectorOpCode {
        const val code: UInt = 110u
    }

    object AddSatS: OpCode, VectorOpCode {
        const val code: UInt = 111u
    }

    object AddSatU: OpCode, VectorOpCode {
        const val code: UInt = 112u
    }

    object Sub: OpCode, VectorOpCode {
        const val code: UInt = 113u
    }

    object SubSatS: OpCode, VectorOpCode {
        const val code: UInt = 114u
    }

    object SubSatU: OpCode, VectorOpCode {
        const val code: UInt = 115u
    }

    object MinS: OpCode, VectorOpCode {
        const val code: UInt = 118u
    }

    object MinU: OpCode, VectorOpCode {
        const val code: UInt = 119u
    }

    object MaxS: OpCode, VectorOpCode {
        const val code: UInt = 120u
    }

    object MaxU: OpCode, VectorOpCode {
        const val code: UInt = 121u
    }

    object AvgrU: OpCode, VectorOpCode {
        const val code: UInt = 123u
    }

    /**/
    object RelaxedLaneselect: OpCode, VectorOpCode {
        const val code: UInt = 265u
    }
}