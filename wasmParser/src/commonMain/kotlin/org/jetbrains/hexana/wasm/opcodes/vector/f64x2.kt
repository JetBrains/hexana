package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.VectorOpCode

object f64x2 {
    class ExtractLaneU(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 33u
        }
    }

    class ReplaceLine(val laneIdx: UByte, offset: Int = 0): OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 34u
        }
    }

    object Splat: OpCode, VectorOpCode {
        const val code: UInt = 20u
    }

    object Eq: OpCode, VectorOpCode {
        const val code: UInt = 71u
    }

    object Ne: OpCode, VectorOpCode {
        const val code: UInt = 72u
    }

    object Lt: OpCode, VectorOpCode {
        const val code: UInt = 73u
    }

    object Gt: OpCode, VectorOpCode {
        const val code: UInt = 74u
    }

    object Le: OpCode, VectorOpCode {
        const val code: UInt = 75u
    }

    object Ge: OpCode, VectorOpCode {
        const val code: UInt = 76u
    }

    /**/
    object Ceil: OpCode, VectorOpCode {
        const val code: UInt = 116u
    }

    object Floor: OpCode, VectorOpCode {
        const val code: UInt = 117u
    }

    object Trunc: OpCode, VectorOpCode {
        const val code: UInt = 122u
    }

    object Nearest: OpCode, VectorOpCode {
        const val code: UInt = 148u
    }

    object Abs: OpCode, VectorOpCode {
        const val code: UInt = 236u
    }

    object Neg: OpCode, VectorOpCode {
        const val code: UInt = 237u
    }

    object Sqrt: OpCode, VectorOpCode {
        const val code: UInt = 239u
    }

    object Add: OpCode, VectorOpCode {
        const val code: UInt = 240u
    }

    object Sub: OpCode, VectorOpCode {
        const val code: UInt = 241u
    }

    object Mul: OpCode, VectorOpCode {
        const val code: UInt = 242u
    }

    object Div: OpCode, VectorOpCode {
        const val code: UInt = 243u
    }

    object Min: OpCode, VectorOpCode {
        const val code: UInt = 244u
    }

    object Max: OpCode, VectorOpCode {
        const val code: UInt = 245u
    }

    object PMin: OpCode, VectorOpCode {
        const val code: UInt = 246u
    }

    object PMax: OpCode, VectorOpCode {
        const val code: UInt = 247u
    }

    object RelaxedMin: OpCode, VectorOpCode {
        const val code: UInt = 271u
    }

    object RelaxedMax: OpCode, VectorOpCode {
        const val code: UInt = 272u
    }

    object RelaxedMAdd: OpCode, VectorOpCode {
        const val code: UInt = 263u
    }

    object RelaxedNMAdd: OpCode, VectorOpCode {
        const val code: UInt = 264u
    }

    /**/
    object PromoteLowF32x4: OpCode, VectorOpCode {
        const val code: UInt = 95u
    }

    object ConvertLowSI32x4: OpCode, VectorOpCode {
        const val code: UInt = 254u
    }

    object ConvertLowUF32x4: OpCode, VectorOpCode {
        const val code: UInt = 255u
    }
}