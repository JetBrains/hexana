package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.OpCode

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
    ): OpCode {
        companion object {
            const val code: UInt = 13u
        }
    }

    object Swizzle : OpCode {
        const val code: UInt = 14u
    }

    class ExtractLaneS(val laneIdx: UByte): OpCode {
        companion object {
            const val code:UInt = 21u
        }
    }

    class ExtractLaneU(val laneIdx: UByte): OpCode {
        companion object {
            const val code: UInt = 22u
        }
    }

    class ReplaceLine(val laneIdx: UByte): OpCode {
        companion object {
            const val code: UInt = 23u
        }
    }

    object Splat: OpCode {
        const val code: UInt = 15u
    }

    object Eq: OpCode {
        const val code: UInt = 35u
    }

    object Ne: OpCode {
        const val code: UInt = 36u
    }

    object LtS: OpCode {
        const val code: UInt = 37u
    }

    object LtU: OpCode {
        const val code: UInt = 38u
    }

    object GtS: OpCode {
        const val code: UInt = 39u
    }

    object GtU: OpCode {
        const val code: UInt = 40u
    }

    object LeS: OpCode {
        const val code: UInt = 41u
    }

    object LeU: OpCode {
        const val code: UInt = 42u
    }

    object GeS: OpCode {
        const val code: UInt = 43u
    }

    object GeU: OpCode {
        const val code: UInt = 44u
    }

    /**/
    object Abs: OpCode {
        const val code: UInt = 96u
    }

    object Neg: OpCode {
        const val code: UInt = 97u
    }

    object PopCnt: OpCode {
        const val code: UInt = 98u
    }

    object AllTrue: OpCode {
        const val code: UInt = 99u
    }

    object BitMask: OpCode {
        const val code: UInt = 100u
    }

    object NarrowI16x8S: OpCode {
        const val code: UInt = 101u
    }

    object NarrowI16x8U: OpCode {
        const val code: UInt = 102u
    }

    object Shl: OpCode {
        const val code: UInt = 107u
    }

    object ShrS: OpCode {
        const val code: UInt = 108u
    }

    object ShrU: OpCode {
        const val code: UInt = 109u
    }

    object Add: OpCode {
        const val code: UInt = 110u
    }

    object AddSatS: OpCode {
        const val code: UInt = 111u
    }

    object AddSatU: OpCode {
        const val code: UInt = 112u
    }

    object Sub: OpCode {
        const val code: UInt = 113u
    }

    object SubSatS: OpCode {
        const val code: UInt = 114u
    }

    object SubSatU: OpCode {
        const val code: UInt = 115u
    }

    object MinS: OpCode {
        const val code: UInt = 118u
    }

    object MinU: OpCode {
        const val code: UInt = 119u
    }

    object MaxS: OpCode {
        const val code: UInt = 120u
    }

    object MaxU: OpCode {
        const val code: UInt = 121u
    }

    object AvgrU: OpCode {
        const val code: UInt = 123u
    }

    /**/
    object RelaxedLaneselect: OpCode {
        const val code: UInt = 265u
    }
}