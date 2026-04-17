package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.OpCode

object i16x8 {
    class ExtractLaneS(val laneIdx: UByte): OpCode {
        companion object {
            const val code:UInt = 24u
        }
    }

    class ExtractLaneU(val laneIdx: UByte): OpCode {
        companion object {
            const val code: UInt = 25u
        }
    }

    class ReplaceLine(val laneIdx: UByte): OpCode {
        companion object {
            const val code: UInt = 26u
        }
    }

    object Splat: OpCode {
        const val code: UInt = 16u
    }

    object Eq: OpCode {
        const val code: UInt = 45u
    }

    object Ne: OpCode {
        const val code: UInt = 46u
    }

    object LtS: OpCode {
        const val code: UInt = 47u
    }

    object LtU: OpCode {
        const val code: UInt = 48u
    }

    object GtS: OpCode {
        const val code: UInt = 49u
    }

    object GtU: OpCode {
        const val code: UInt = 50u
    }

    object LeS: OpCode {
        const val code: UInt = 51u
    }

    object LeU: OpCode {
        const val code: UInt = 52u
    }

    object GeS: OpCode {
        const val code: UInt = 53u
    }

    object GeU: OpCode {
        const val code: UInt = 54u
    }

    /**/
    object ExtAddPairwiseSi8x16: OpCode {
        const val code: UInt = 124u
    }

    object ExtAddPairwiseUi8x16: OpCode {
        const val code: UInt = 125u
    }

    object Abs: OpCode {
        const val code: UInt = 128u
    }

    object Neg: OpCode {
        const val code: UInt = 129u
    }

    object AllTrue: OpCode {
        const val code: UInt = 131u
    }

    object BitMask: OpCode {
        const val code: UInt = 132u
    }

    object NarrowI32x4S: OpCode {
        const val code: UInt = 133u
    }

    object NarrowI32x4U: OpCode {
        const val code: UInt = 134u
    }

    object ExtendLowSi8x16: OpCode {
        const val code: UInt = 135u
    }

    object ExtendHighSi8x16: OpCode {
        const val code: UInt = 136u
    }

    object ExtendLowUi8x16: OpCode {
        const val code: UInt = 137u
    }

    object ExtendHighUi8x16: OpCode {
        const val code: UInt = 138u
    }

    object Shl: OpCode {
        const val code: UInt = 139u
    }

    object ShrS: OpCode {
        const val code: UInt = 140u
    }

    object ShrU: OpCode {
        const val code: UInt = 141u
    }

    object Q15MulrSatS: OpCode {
        const val code: UInt = 130u
    }

    object Add: OpCode {
        const val code: UInt = 142u
    }

    object AddSatS: OpCode {
        const val code: UInt = 143u
    }

    object AddSatU: OpCode {
        const val code: UInt = 144u
    }

    object Sub: OpCode {
        const val code: UInt = 145u
    }

    object SubSatS: OpCode {
        const val code: UInt = 146u
    }

    object SubSatU: OpCode {
        const val code: UInt = 147u
    }

    object Mul: OpCode {
        const val code: UInt = 149u
    }

    object MinS: OpCode {
        const val code: UInt = 150u
    }

    object MinU: OpCode {
        const val code: UInt = 151u
    }

    object MaxS: OpCode {
        const val code: UInt = 152u
    }

    object MaxU: OpCode {
        const val code: UInt = 153u
    }

    object AvgrU: OpCode {
        const val code: UInt = 155u
    }

    object RelaxedQ15MulrSatS: OpCode {
        const val code: UInt = 273u
    }

    object ExtmulLowSi8x16: OpCode {
        const val code: UInt = 156u
    }


    object ExtmulHighSi8x16: OpCode {
        const val code: UInt = 157u
    }

    object ExtmulLowUi8x16: OpCode {
        const val code: UInt = 158u
    }

    object ExtmulHighUi8x16: OpCode {
        const val code: UInt = 159u
    }

    object RelaxedDotSi8x16: OpCode {
        const val code: UInt = 274u
    }

    /**/
    object RelaxedLaneselect: OpCode {
        const val code: UInt = 266u
    }

    /**/
    object RelaxedSwizzle: OpCode {
        const val code: UInt = 256u
    }
}