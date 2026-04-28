package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.VectorOpCode

object f32x4 {
    class ExtractLaneU(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 31u
        }
    }

    class ReplaceLine(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 32u
        }
    }

    object Splat: OpCode, VectorOpCode {
        const val code: UInt = 19u
    }

    object Eq: OpCode, VectorOpCode {
        const val code: UInt = 65u
    }

    object Ne: OpCode, VectorOpCode {
        const val code: UInt = 66u
    }

    object Lt: OpCode, VectorOpCode {
        const val code: UInt = 67u
    }

    object Gt: OpCode, VectorOpCode {
        const val code: UInt = 68u
    }

    object Le: OpCode, VectorOpCode {
        const val code: UInt = 69u
    }

    object Ge: OpCode, VectorOpCode {
        const val code: UInt = 70u
    }

    /**/
    object Ceil: OpCode, VectorOpCode {
        const val code: UInt = 103u
    }

    object Floor: OpCode, VectorOpCode {
        const val code: UInt = 104u
    }

    object Trunc: OpCode, VectorOpCode {
        const val code: UInt = 105u
    }

    object Nearest: OpCode, VectorOpCode {
        const val code: UInt = 106u
    }

    object Abs: OpCode, VectorOpCode {
        const val code: UInt = 224u
    }

    object Neg: OpCode, VectorOpCode {
        const val code: UInt = 225u
    }

    object Sqrt: OpCode, VectorOpCode {
        const val code: UInt = 227u
    }

    object Add: OpCode, VectorOpCode {
        const val code: UInt = 228u
    }

    object Sub: OpCode, VectorOpCode {
        const val code: UInt = 229u
    }

    object Mul: OpCode, VectorOpCode {
        const val code: UInt = 230u
    }

    object Div: OpCode, VectorOpCode {
        const val code: UInt = 231u
    }

    object Min: OpCode, VectorOpCode {
        const val code: UInt = 232u
    }

    object Max: OpCode, VectorOpCode {
        const val code: UInt = 233u
    }

    object PMin: OpCode, VectorOpCode {
        const val code: UInt = 234u
    }

    object PMax: OpCode, VectorOpCode {
        const val code: UInt = 235u
    }

    object RelaxedMin: OpCode, VectorOpCode {
        const val code: UInt = 269u
    }

    object RelaxedMax: OpCode, VectorOpCode {
        const val code: UInt = 270u
    }

    object RelaxedMAdd: OpCode, VectorOpCode {
        const val code: UInt = 261u
    }

    object RelaxedNMAdd: OpCode, VectorOpCode {
        const val code: UInt = 262u
    }

    /**/
    object DemoteZeroF64x2: OpCode, VectorOpCode {
        const val code: UInt = 94u
    }

    object ConvertSI32x4: OpCode, VectorOpCode {
        const val code: UInt = 250u
    }

    object ConvertUF32x4: OpCode, VectorOpCode {
        const val code: UInt = 251u
    }
}