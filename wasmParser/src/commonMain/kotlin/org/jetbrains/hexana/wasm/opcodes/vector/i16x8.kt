package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.VectorOpCode

object i16x8 {
    class ExtractLaneS(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code:UInt = 24u
        }
    }

    class ExtractLaneU(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 25u
        }
    }

    class ReplaceLine(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 26u
        }
    }

    object Splat: OpCode, VectorOpCode {
        const val code: UInt = 16u
    }

    object Eq: OpCode, VectorOpCode {
        const val code: UInt = 45u
    }

    object Ne: OpCode, VectorOpCode {
        const val code: UInt = 46u
    }

    object LtS: OpCode, VectorOpCode {
        const val code: UInt = 47u
    }

    object LtU: OpCode, VectorOpCode {
        const val code: UInt = 48u
    }

    object GtS: OpCode, VectorOpCode {
        const val code: UInt = 49u
    }

    object GtU: OpCode, VectorOpCode {
        const val code: UInt = 50u
    }

    object LeS: OpCode, VectorOpCode {
        const val code: UInt = 51u
    }

    object LeU: OpCode, VectorOpCode {
        const val code: UInt = 52u
    }

    object GeS: OpCode, VectorOpCode {
        const val code: UInt = 53u
    }

    object GeU: OpCode, VectorOpCode {
        const val code: UInt = 54u
    }

    /**/
    object ExtAddPairwiseSi8x16: OpCode, VectorOpCode {
        const val code: UInt = 124u
    }

    object ExtAddPairwiseUi8x16: OpCode, VectorOpCode {
        const val code: UInt = 125u
    }

    object Abs: OpCode, VectorOpCode {
        const val code: UInt = 128u
    }

    object Neg: OpCode, VectorOpCode {
        const val code: UInt = 129u
    }

    object AllTrue: OpCode, VectorOpCode {
        const val code: UInt = 131u
    }

    object BitMask: OpCode, VectorOpCode {
        const val code: UInt = 132u
    }

    object NarrowI32x4S: OpCode, VectorOpCode {
        const val code: UInt = 133u
    }

    object NarrowI32x4U: OpCode, VectorOpCode {
        const val code: UInt = 134u
    }

    object ExtendLowSi8x16: OpCode, VectorOpCode {
        const val code: UInt = 135u
    }

    object ExtendHighSi8x16: OpCode, VectorOpCode {
        const val code: UInt = 136u
    }

    object ExtendLowUi8x16: OpCode, VectorOpCode {
        const val code: UInt = 137u
    }

    object ExtendHighUi8x16: OpCode, VectorOpCode {
        const val code: UInt = 138u
    }

    object Shl: OpCode, VectorOpCode {
        const val code: UInt = 139u
    }

    object ShrS: OpCode, VectorOpCode {
        const val code: UInt = 140u
    }

    object ShrU: OpCode, VectorOpCode {
        const val code: UInt = 141u
    }

    object Q15MulrSatS: OpCode, VectorOpCode {
        const val code: UInt = 130u
    }

    object Add: OpCode, VectorOpCode {
        const val code: UInt = 142u
    }

    object AddSatS: OpCode, VectorOpCode {
        const val code: UInt = 143u
    }

    object AddSatU: OpCode, VectorOpCode {
        const val code: UInt = 144u
    }

    object Sub: OpCode, VectorOpCode {
        const val code: UInt = 145u
    }

    object SubSatS: OpCode, VectorOpCode {
        const val code: UInt = 146u
    }

    object SubSatU: OpCode, VectorOpCode {
        const val code: UInt = 147u
    }

    object Mul: OpCode, VectorOpCode {
        const val code: UInt = 149u
    }

    object MinS: OpCode, VectorOpCode {
        const val code: UInt = 150u
    }

    object MinU: OpCode, VectorOpCode {
        const val code: UInt = 151u
    }

    object MaxS: OpCode, VectorOpCode {
        const val code: UInt = 152u
    }

    object MaxU: OpCode, VectorOpCode {
        const val code: UInt = 153u
    }

    object AvgrU: OpCode, VectorOpCode {
        const val code: UInt = 155u
    }

    object RelaxedQ15MulrSatS: OpCode, VectorOpCode {
        const val code: UInt = 273u
    }

    object ExtmulLowSi8x16: OpCode, VectorOpCode {
        const val code: UInt = 156u
    }


    object ExtmulHighSi8x16: OpCode, VectorOpCode {
        const val code: UInt = 157u
    }

    object ExtmulLowUi8x16: OpCode, VectorOpCode {
        const val code: UInt = 158u
    }

    object ExtmulHighUi8x16: OpCode, VectorOpCode {
        const val code: UInt = 159u
    }

    object RelaxedDotSi8x16: OpCode, VectorOpCode {
        const val code: UInt = 274u
    }

    /**/
    object RelaxedLaneselect: OpCode, VectorOpCode {
        const val code: UInt = 266u
    }

    /**/
    object RelaxedSwizzle: OpCode, VectorOpCode {
        const val code: UInt = 256u
    }
}