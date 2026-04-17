package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.OpCode

object f64x2 {
    class ExtractLaneU(val laneIdx: UByte): OpCode {
        companion object {
            const val code: UInt = 33u
        }
    }

    class ReplaceLine(val laneIdx: UByte): OpCode {
        companion object {
            const val code: UInt = 34u
        }
    }

    object Splat: OpCode {
        const val code: UInt = 20u
    }

    object Eq: OpCode {
        const val code: UInt = 71u
    }

    object Ne: OpCode {
        const val code: UInt = 72u
    }

    object Lt: OpCode {
        const val code: UInt = 73u
    }

    object Gt: OpCode {
        const val code: UInt = 74u
    }

    object Le: OpCode {
        const val code: UInt = 75u
    }

    object Ge: OpCode {
        const val code: UInt = 76u
    }

    /**/
    object Ceil: OpCode {
        const val code: UInt = 116u
    }

    object Floor: OpCode {
        const val code: UInt = 117u
    }

    object Trunc: OpCode {
        const val code: UInt = 122u
    }

    object Nearest: OpCode {
        const val code: UInt = 148u
    }

    object Abs: OpCode {
        const val code: UInt = 236u
    }

    object Neg: OpCode {
        const val code: UInt = 237u
    }

    object Sqrt: OpCode {
        const val code: UInt = 239u
    }

    object Add: OpCode {
        const val code: UInt = 240u
    }

    object Sub: OpCode {
        const val code: UInt = 241u
    }

    object Mul: OpCode {
        const val code: UInt = 242u
    }

    object Div: OpCode {
        const val code: UInt = 243u
    }

    object Min: OpCode {
        const val code: UInt = 244u
    }

    object Max: OpCode {
        const val code: UInt = 245u
    }

    object PMin: OpCode {
        const val code: UInt = 246u
    }

    object PMax: OpCode {
        const val code: UInt = 247u
    }

    object RelaxedMin: OpCode {
        const val code: UInt = 271u
    }

    object RelaxedMax: OpCode {
        const val code: UInt = 272u
    }

    object RelaxedMAdd: OpCode {
        const val code: UInt = 263u
    }

    object RelaxedNMAdd: OpCode {
        const val code: UInt = 264u
    }

    /**/
    object PromoteLowF32x4: OpCode {
        const val code: UInt = 95u
    }

    object ConvertLowSI32x4: OpCode {
        const val code: UInt = 254u
    }

    object ConvertLowUF32x4: OpCode {
        const val code: UInt = 255u
    }
}