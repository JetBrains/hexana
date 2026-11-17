package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.VectorOpCode

object i32x4 {
    class ExtractLaneU(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 27u
        }
    }

    class ReplaceLine(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 28u
        }
    }

    object Splat: OpCode, VectorOpCode {
        const val code: UInt = 17u
    }

    object Eq: OpCode, VectorOpCode {
        const val code: UInt = 55u
    }

    object Ne: OpCode, VectorOpCode {
        const val code: UInt = 56u
    }

    object LtS: OpCode, VectorOpCode {
        const val code: UInt = 57u
    }

    object LtU: OpCode, VectorOpCode {
        const val code: UInt = 58u
    }

    object GtS: OpCode, VectorOpCode {
        const val code: UInt = 59u
    }

    object GtU: OpCode, VectorOpCode {
        const val code: UInt = 60u
    }

    object LeS: OpCode, VectorOpCode {
        const val code: UInt = 61u
    }

    object LeU: OpCode, VectorOpCode {
        const val code: UInt = 62u
    }

    object GeS: OpCode, VectorOpCode {
        const val code: UInt = 63u
    }

    object GeU: OpCode, VectorOpCode {
        const val code: UInt = 64u
    }

    /**/
    object ExtAddPairwiseSi16x8: OpCode, VectorOpCode {
        const val code: UInt = 126u
    }

    object ExtAddPairwiseUi16x8: OpCode, VectorOpCode {
        const val code: UInt = 127u
    }

    object Abs: OpCode, VectorOpCode {
        const val code: UInt = 160u
    }

    object Neg: OpCode, VectorOpCode {
        const val code: UInt = 161u
    }

    object AllTrue: OpCode, VectorOpCode {
        const val code: UInt = 163u
    }

    object BitMask: OpCode, VectorOpCode {
        const val code: UInt = 164u
    }

    object ExtendLowSi16x8: OpCode, VectorOpCode {
        const val code: UInt = 167u
    }

    object ExtendHighSi16x8: OpCode, VectorOpCode {
        const val code: UInt = 168u
    }

    object ExtendLowUi16x8: OpCode, VectorOpCode {
        const val code: UInt = 169u
    }

    object ExtendHighUi16x8: OpCode, VectorOpCode {
        const val code: UInt = 170u
    }

    object Shl: OpCode, VectorOpCode {
        const val code: UInt = 171u
    }

    object ShrS: OpCode, VectorOpCode {
        const val code: UInt = 172u
    }

    object ShrU: OpCode, VectorOpCode {
        const val code: UInt = 173u
    }

    object Add: OpCode, VectorOpCode {
        const val code: UInt = 174u
    }

    object Sub: OpCode, VectorOpCode {
        const val code: UInt = 177u
    }

    object Mul: OpCode, VectorOpCode {
        const val code: UInt = 181u
    }

    object MinS: OpCode, VectorOpCode {
        const val code: UInt = 182u
    }

    object MinU: OpCode, VectorOpCode {
        const val code: UInt = 183u
    }

    object MaxS: OpCode, VectorOpCode {
        const val code: UInt = 184u
    }

    object MaxU: OpCode, VectorOpCode {
        const val code: UInt = 185u
    }

    object DotSi16x8: OpCode, VectorOpCode {
        const val code: UInt = 186u
    }

    object ExtmulLowSi16x8: OpCode, VectorOpCode {
        const val code: UInt = 188u
    }

    object ExtmulHighSi16x8: OpCode, VectorOpCode {
        const val code: UInt = 189u
    }

    object ExtmulLowUi16x8: OpCode, VectorOpCode {
        const val code: UInt = 190u
    }

    object ExtmulHighUi16x8: OpCode, VectorOpCode {
        const val code: UInt = 191u
    }

    object RelaxedDotAddSi16x8: OpCode, VectorOpCode {
        const val code: UInt = 275u
    }

    /**/
    object RelaxedLaneselect: OpCode, VectorOpCode {
        const val code: UInt = 267u
    }

    /**/
    object TruncSatSF32x4: OpCode, VectorOpCode {
        const val code: UInt = 248u
    }

    object TruncSatUF32x4: OpCode, VectorOpCode {
        const val code: UInt = 249u
    }

    object TruncSatSZeroF64x2: OpCode, VectorOpCode {
        const val code: UInt = 252u
    }

    object TruncSatUZeroF64x2: OpCode, VectorOpCode {
        const val code: UInt = 253u
    }

    object RelaxedTruncSF32x4: OpCode, VectorOpCode {
        const val code: UInt = 257u
    }

    object RelaxedTruncUF32x4: OpCode, VectorOpCode {
        const val code: UInt = 258u
    }

    object RelaxedTruncSZeroF32x4: OpCode, VectorOpCode {
        const val code: UInt = 259u
    }

    object RelaxedTruncUZeroF32x4: OpCode, VectorOpCode {
        const val code: UInt = 260u
    }

    /**/
    object RelaxedTruncF32x4S: OpCode, VectorOpCode {
        const val code: UInt = 257u
    }

    object RelaxedTruncF32x4U: OpCode, VectorOpCode {
        const val code: UInt = 258u
    }

    object RelaxedTruncF32x4SZero: OpCode, VectorOpCode {
        const val code: UInt = 259u
    }

    object RelaxedTruncF32x4UZero: OpCode, VectorOpCode {
        const val code: UInt = 260u
    }
}