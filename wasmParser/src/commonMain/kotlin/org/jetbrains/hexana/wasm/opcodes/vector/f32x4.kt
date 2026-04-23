package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset

object f32x4 {
    class ExtractLaneU(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset) {
        companion object {
            const val code: UInt = 31u
        }
    }

    class ReplaceLine(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset) {
        companion object {
            const val code: UInt = 32u
        }
    }

    object Splat: OpCode {
        const val code: UInt = 19u
    }

    object Eq: OpCode {
        const val code: UInt = 65u
    }

    object Ne: OpCode {
        const val code: UInt = 66u
    }

    object Lt: OpCode {
        const val code: UInt = 67u
    }

    object Gt: OpCode {
        const val code: UInt = 68u
    }

    object Le: OpCode {
        const val code: UInt = 69u
    }

    object Ge: OpCode {
        const val code: UInt = 70u
    }

    /**/
    object Ceil: OpCode {
        const val code: UInt = 103u
    }

    object Floor: OpCode {
        const val code: UInt = 104u
    }

    object Trunc: OpCode {
        const val code: UInt = 105u
    }

    object Nearest: OpCode {
        const val code: UInt = 106u
    }

    object Abs: OpCode {
        const val code: UInt = 224u
    }

    object Neg: OpCode {
        const val code: UInt = 225u
    }

    object Sqrt: OpCode {
        const val code: UInt = 227u
    }

    object Add: OpCode {
        const val code: UInt = 228u
    }

    object Sub: OpCode {
        const val code: UInt = 229u
    }

    object Mul: OpCode {
        const val code: UInt = 230u
    }

    object Div: OpCode {
        const val code: UInt = 231u
    }

    object Min: OpCode {
        const val code: UInt = 232u
    }

    object Max: OpCode {
        const val code: UInt = 233u
    }

    object PMin: OpCode {
        const val code: UInt = 234u
    }

    object PMax: OpCode {
        const val code: UInt = 235u
    }

    object RelaxedMin: OpCode {
        const val code: UInt = 269u
    }

    object RelaxedMax: OpCode {
        const val code: UInt = 270u
    }

    object RelaxedMAdd: OpCode {
        const val code: UInt = 261u
    }

    object RelaxedNMAdd: OpCode {
        const val code: UInt = 262u
    }

    /**/
    object DemoteZeroF64x2: OpCode {
        const val code: UInt = 94u
    }

    object ConvertSI32x4: OpCode {
        const val code: UInt = 250u
    }

    object ConvertUF32x4: OpCode {
        const val code: UInt = 251u
    }
}