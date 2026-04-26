package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset

object i32x4 {
    class ExtractLaneU(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset) {
        companion object {
            const val code: UInt = 27u
        }
    }

    class ReplaceLine(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset) {
        companion object {
            const val code: UInt = 28u
        }
    }

    object Splat: OpCode {
        const val code: UInt = 17u
    }

    object Eq: OpCode {
        const val code: UInt = 55u
    }

    object Ne: OpCode {
        const val code: UInt = 56u
    }

    object LtS: OpCode {
        const val code: UInt = 57u
    }

    object LtU: OpCode {
        const val code: UInt = 58u
    }

    object GtS: OpCode {
        const val code: UInt = 59u
    }

    object GtU: OpCode {
        const val code: UInt = 60u
    }

    object LeS: OpCode {
        const val code: UInt = 61u
    }

    object LeU: OpCode {
        const val code: UInt = 62u
    }

    object GeS: OpCode {
        const val code: UInt = 63u
    }

    object GeU: OpCode {
        const val code: UInt = 64u
    }

    /**/
    object ExtAddPairwiseSi16x8: OpCode {
        const val code: UInt = 126u
    }

    object ExtAddPairwiseUi16x8: OpCode {
        const val code: UInt = 127u
    }

    object Abs: OpCode {
        const val code: UInt = 160u
    }

    object Neg: OpCode {
        const val code: UInt = 161u
    }

    object AllTrue: OpCode {
        const val code: UInt = 163u
    }

    object BitMask: OpCode {
        const val code: UInt = 164u
    }

    object ExtendLowSi16x8: OpCode {
        const val code: UInt = 167u
    }

    object ExtendHighSi16x8: OpCode {
        const val code: UInt = 168u
    }

    object ExtendLowUi16x8: OpCode {
        const val code: UInt = 169u
    }

    object ExtendHighUi16x8: OpCode {
        const val code: UInt = 170u
    }

    object Shl: OpCode {
        const val code: UInt = 171u
    }

    object ShrS: OpCode {
        const val code: UInt = 172u
    }

    object ShrU: OpCode {
        const val code: UInt = 173u
    }

    object Add: OpCode {
        const val code: UInt = 174u
    }

    object Sub: OpCode {
        const val code: UInt = 177u
    }

    object Mul: OpCode {
        const val code: UInt = 181u
    }

    object MinS: OpCode {
        const val code: UInt = 182u
    }

    object MinU: OpCode {
        const val code: UInt = 183u
    }

    object MaxS: OpCode {
        const val code: UInt = 184u
    }

    object MaxU: OpCode {
        const val code: UInt = 185u
    }

    object DotSi16x8: OpCode {
        const val code: UInt = 186u
    }

    object ExtmulLowSi16x8: OpCode {
        const val code: UInt = 188u
    }

    object ExtmulHighSi16x8: OpCode {
        const val code: UInt = 189u
    }

    object ExtmulLowUi16x8: OpCode {
        const val code: UInt = 190u
    }

    object ExtmulHighUi16x8: OpCode {
        const val code: UInt = 191u
    }

    object RelaxedDotAddSi16x8: OpCode {
        const val code: UInt = 275u
    }

    /**/
    object RelaxedLaneselect: OpCode {
        const val code: UInt = 267u
    }

    /**/
    object TruncSatSF32x4: OpCode {
        const val code: UInt = 248u
    }

    object TruncSatUF32x4: OpCode {
        const val code: UInt = 249u
    }

    object TruncSatSZeroF64x2: OpCode {
        const val code: UInt = 252u
    }

    object TruncSatUZeroF64x2: OpCode {
        const val code: UInt = 253u
    }

    object RelaxedTruncSF32x4: OpCode {
        const val code: UInt = 257u
    }

    object RelaxedTruncUF32x4: OpCode {
        const val code: UInt = 258u
    }

    object RelaxedTruncSZeroF32x4: OpCode {
        const val code: UInt = 259u
    }

    object RelaxedTruncUZeroF32x4: OpCode {
        const val code: UInt = 260u
    }

    /**/
    object RelaxedTruncF32x4S: OpCode {
        const val code: UInt = 257u
    }

    object RelaxedTruncF32x4U: OpCode {
        const val code: UInt = 258u
    }

    object RelaxedTruncF32x4SZero: OpCode {
        const val code: UInt = 259u
    }

    object RelaxedTruncF32x4UZero: OpCode {
        const val code: UInt = 260u
    }
}