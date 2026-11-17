package org.jetbrains.hexana.wasm.opcodes.vector

import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.VectorOpCode

/**
 * laneIdx (u8) 2.4.9
 */
object V128 {
    class Load(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 0u
        }
    }

    class Load8x8S(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 1u
        }
    }

    class Load8x8U(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 2u
        }
    }

    class Load16x4S(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 3u
        }
    }

    class Load16x4U(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 4u
        }
    }

    class Load32x2S(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 5u
        }
    }

    class Load32x2U(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 6u
        }
    }

    class Load8Splat(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 7u
        }
    }

    class Load16Splat(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 8u
        }
    }

    class Load32Splat(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 9u
        }
    }

    class Load64Splat(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 10u
        }
    }

    class Store(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 11u
        }
    }

    class Load8Lane(val mem: MemArg, val laneIdx: UByte, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 84u
        }
    }

    class Load16Lane(val mem: MemArg, val laneIdx: UByte, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 85u
        }
    }

    class Load32Lane(val mem: MemArg, val laneIdx: UByte, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 86u
        }
    }

    class Load64Lane(val mem: MemArg, val laneIdx: UByte, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 87u
        }
    }

    class Store8Lane(val mem: MemArg, val laneIdx: UByte, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 88u
        }
    }

    class Store16Lane(val mem: MemArg, val laneIdx: UByte, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 89u
        }
    }

    class Store32Lane(val mem: MemArg, val laneIdx: UByte, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 90u
        }
    }

    class Store64Lane(val mem: MemArg, val laneIdx: UByte, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 91u
        }
    }

    class Load32Zero(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 92u
        }
    }

    class Load64Zero(val mem: MemArg, offset: Int = 0) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 93u
        }
    }

    class Const(
        val v0: Byte,
        val v1: Byte,
        val v2: Byte,
        val v3: Byte,
        val v4: Byte,
        val v5: Byte,
        val v6: Byte,
        val v7: Byte,
        val v8: Byte,
        val v9: Byte,
        val va: Byte,
        val vb: Byte,
        val vc: Byte,
        val vd: Byte,
        val ve: Byte,
        val vf: Byte,
        offset: Int = 0,
    ) : OpCodeWithOffset(offset), VectorOpCode {
        companion object {
            const val code: UInt = 12u
        }
    }

    object Not: OpCode, VectorOpCode {
        const val code: UInt = 77u
    }

    object And: OpCode, VectorOpCode {
        const val code: UInt = 78u
    }

    object AndNot: OpCode, VectorOpCode {
        const val code: UInt = 79u
    }

    object Or: OpCode, VectorOpCode {
        const val code: UInt = 80u
    }

    object Xor: OpCode, VectorOpCode {
        const val code: UInt = 81u
    }

    object BitSelect: OpCode, VectorOpCode {
        const val code: UInt = 82u
    }

    object AnyTrue: OpCode, VectorOpCode {
        const val code: UInt = 83u
    }
}
