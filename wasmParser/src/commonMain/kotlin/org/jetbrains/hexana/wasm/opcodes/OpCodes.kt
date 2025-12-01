package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.wasm.sections.WasmHeapType
import org.jetbrains.hexana.wasm.sections.WasmValueType
import kotlin.jvm.JvmInline

object OpCodes {
    object Flow {
        object Terminal : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x0Bu
        }

        object Return : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x0Fu
        }

        object ReturnCall : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x12u
        }

        object Drop : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x1Au
        }

        object Select : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x1Bu
        }

        object Unreachable : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x00u
        }

        object Nop : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x01u
        }

        class Call(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x10u
            }
        }

        class CallIndirect(value: Int) : OpCodeWithValue<Int>(value) {
            companion object {
                const val code: UByte = 0x11u
            }
        }

        class Br(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x0Cu
            }
        }

        class BrIf(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x0Du
            }
        }

        class BrTable(values: List<Int>) : OpCodeWithValue<List<Int>>(values) {
            companion object {
                const val code: UByte = 0x0Eu
            }
        }

        open class Block(val type: WasmValueType<*>?, value: List<OpCode>) : OpCodeWithValue<List<OpCode>>(value) {
            companion object {
                const val code: UByte = 0x02u
            }
        }

        class Loop(type: WasmValueType<*>, value: List<OpCode>) : Block(type, value) {
            companion object {
                const val code: UByte = 0x03u
            }
        }

        class If(type: WasmValueType<*>, value: List<OpCode>) : Block(type, value) {
            companion object {
                const val code: UByte = 0x04u
            }
        }

        class Else(type: WasmValueType<*>?, value: List<OpCode>) :  Block(type, value){
            companion object {
                const val code: UByte = 0x05u
            }
        }
    }

    object Local {
        class Get(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x20u
            }
        }

        class Set(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x21u
            }
        }

        class Tee(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x22u
            }
        }
    }

    object Global {
        class Get(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x23u
            }
        }

        class Set(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x24u
            }
        }
    }

    object I32 {
        class Load(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x28u
            }
        }

        class Load8_S(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x2Cu
            }
        }

        class Load8_U(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x2Du
            }
        }

        class Load16_S(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x2Eu
            }
        }

        class Load16_U(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x2Fu
            }
        }

        class Store8(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x3Au
            }
        }

        class Store16(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x3Bu
            }
        }

        class Store(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x36u
            }
        }

        object Shl : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x74u
        }

        object PopCnt : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x69u
        }

        object And : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x71u
        }

        object Or : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x72u
        }

        object Xor : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x73u
        }

        object Add : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x6Au
        }

        object Sub : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x6Bu
        }

        object Mul : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x6Cu
        }

        object Rotl : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x77u
        }

        object Rotr : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x78u
        }

        object Clz : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x67u
        }

        object Ctz : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x68u
        }

        class Const(value: Int) : OpCodeWithValue<Int>(value) {
            companion object {
                const val code: UByte = 0x41u
            }
        }

        object Eqz : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x45u
        }

        object Eq : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x46u
        }

        object Ne : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x47u
        }

        object LTS : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x48u
        }

        object LTU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x49u
        }

        object LES : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x4Cu
        }

        object LEU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x4Du
        }

        object GTS : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x4Au
        }

        object GES : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x4Eu
        }

        object GTU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x4Bu
        }

        object GEU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x4Fu
        }

        object RemS : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x6Fu
        }

        object RemU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x70u
        }

        object WrapI64 : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xA7u
        }

        object Extend8S : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xC0u
        }

        object Extend16S : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xC1u
        }

        object ReinterpretF32 : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xBCu
        }

        object TruncF32S : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xA8u
        }

        object TruncF32U : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xA9u
        }

        object TruncF64S: OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xAAu
        }

        object TruncF64U: OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xABu
        }

        object DivU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x6Eu
        }

        object DivS : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x6Du
        }

        object ShrU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x76u
        }

        object ShrS : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x75u
        }
    }

    object I64 {
        // supposed to be signed long ???
        class Const(value: Long) : OpCodeWithValue<Long>(value) {
            companion object {
                const val code: UByte = 0x42u
            }
        }

        class Load(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x29u
            }
        }

        class Load8_S(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x30u
            }
        }

        class Load8_U(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x31u
            }
        }

        class Load16_S(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x32u
            }
        }

        class Load16_U(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x33u
            }
        }

        class Load32_S(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x34u
            }
        }

        class Load32_U(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x35u
            }
        }

        class Store(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x37u
            }
        }

        class Store8(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x3Cu
            }
        }

        class Store16(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x3Du
            }
        }

        class Store32(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x3Eu
            }
        }

        object Eqz : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x50u
        }

        object EQ : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x51u
        }

        object NE : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x52u
        }

        object LTS : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x53u
        }

        object LTU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x54u
        }

        object GTS : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x55u
        }

        object GTU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x56u
        }

        object LES : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x57u
        }

        object LEU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x58u
        }

        object GES : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x59u
        }

        object GEU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x5Au
        }

        object Clz : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x79u
        }

        object Ctz : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x7Au
        }

        object PopCnt : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x7Bu
        }

        object Add : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x7Cu
        }

        object Sub : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x7Du
        }

        object Mul : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x7Eu
        }

        object DivS : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x7Fu
        }

        object DivU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x80u
        }

        object RemS : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x81u
        }

        object RemU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x82u
        }

        object And : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x83u
        }

        object Or : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x84u
        }

        object Xor : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x85u
        }

        object Shl : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x86u
        }

        object ShrS : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x87u
        }

        object ShrU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x88u
        }

        object RotL : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x89u
        }

        object RotR : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x8Au
        }

        object Extend_I32_S : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xACu
        }

        object Extend_I32_U : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xADu
        }

        object TruncF32S : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xAEu
        }

        object TruncF32U : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xAFu
        }

        object TruncF64S : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xB0u
        }

        object TruncF64U : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xB1u
        }

        object ReinterpretF64 : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xBDu
        }

        object Extend8S : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xC2u
        }

        object Extend16S : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xC3u
        }

        object Extend32S : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xC4u
        }
    }

    object F32 {
        class Const(initValue: Float) : OpCodeWithValue<Float>(initValue) {
            companion object {
                const val code: UByte = 0x43u
            }
        }

        object Nearest : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x90u
        }

        object NE : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x5Cu
        }

        object LE : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x5Fu
        }

        object Ceil : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x8du
        }

        object Floor : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x8eu
        }

        object Sub : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x93u
        }

        object Mul : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x94u
        }

        object Trunc : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x8fu
        }

        object CopySign : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x98u
        }

        class Load(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x2Au
            }
        }

        class Store(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                const val code: UByte = 0x38u
            }
        }

        object Sqrt : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x91u
        }

        object Add : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x92u
        }

        object Div : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x95u
        }

        object Min : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x96u
        }

        object Max : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x97u
        }

        object DemoteF64 : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xB6u
        }

        object ReinterpretI32 : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xBEu
        }

        object Neg : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x8Cu
        }

        object ConvertI32S : OpCodeWithValue<Unit>(Unit) {
            val code: UByte = 0xB2u
        }

        object ConvertI32U : OpCodeWithValue<Unit>(Unit) {
            val code: UByte = 0xB3u
        }

        object ConvertI64S : OpCodeWithValue<Unit>(Unit) {
            val code: UByte = 0xB4u
        }

        object ConvertI64U : OpCodeWithValue<Unit>(Unit) {
            val code: UByte = 0xB5u
        }

        object EQ : OpCodeWithValue<Unit>(Unit) {
            val code: UByte = 0x5Bu
        }

        object GE : OpCodeWithValue<Unit>(Unit) {
            val code: UByte = 0x60u
        }

        object LT : OpCodeWithValue<Unit>(Unit) {
            val code: UByte = 0x5Du
        }

        object GT : OpCodeWithValue<Unit>(Unit) {
            val code: UByte = 0x5Eu
        }

        object Abs : OpCodeWithValue<Unit>(Unit) {
            val code: UByte = 0x8Bu
        }
    }

    object F64 {
        class Load(value: MemArg) : OpCodeWithValue<MemArg>(value) {
            companion object {
                val code: UByte = 0x2Bu
            }
        }

        object EQ : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x61u
        }

        object NE : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x62u
        }

        object LT : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x63u
        }

        object GT : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x64u
        }

        object LE : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x65u
        }

        object GE : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x66u
        }

        object ConvertI32S : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xB7u
        }

        object ConvertI32U : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xB8u
        }

        object ConvertI64S : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xB9u
        }

        object ConvertI64U : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xBAu
        }

        object PromoteF32 : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xBBu
        }

        object ReinterpretI64 : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xBFu
        }

        object Add : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xA0u
        }

        object Sub : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xA1u
        }

        object Mul : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xA2u
        }

        object Div : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xA3u
        }

        object Min : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xA4u
        }

        object Max : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xA5u
        }

        object Copysign : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0xA6u
        }

        object Abs : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x99u
        }

        object Neg : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x9Au
        }

        object Ceil : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x9Bu
        }

        object Floor : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x9Cu
        }

        object Trunc : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x9Du
        }

        object Nearest : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x9Eu
        }

        object Sqrt : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x9Fu
        }

        class Const(initValue: Double) : OpCodeWithValue<Double>(initValue) {
            companion object {
                const val code: UByte = 0x44u
            }
        }
    }

    object GC {
        class StructNew(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x00u
            }
        }

        class StructGet(typeIdIndex: UInt, fieldIdIndex: UInt) : OpCodeGCStruct(typeIdIndex, fieldIdIndex) {
            companion object {
                const val code: UByte = 0x02u
            }
        }

        class StructGetSignExtended(typeIdIndex: UInt, fieldIdIndex: UInt) : OpCodeGCStruct(typeIdIndex, fieldIdIndex) {
            companion object {
                const val code: UByte = 0x03u
            }
        }

        class StructGetZeroExtended(typeIdIndex: UInt, fieldIdIndex: UInt) : OpCodeGCStruct(typeIdIndex, fieldIdIndex) {
            companion object {
                const val code: UByte = 0x04u
            }
        }

        class StructSet(typeIdIndex: UInt, fieldIdIndex: UInt) : OpCodeGCStruct(typeIdIndex, fieldIdIndex) {
            companion object {
                const val code: UByte = 0x05u
            }
        }

        class RefTest(initValue: HeapType) : OpCodeWithValue<HeapType>(initValue) {
            companion object {
                const val code: UByte = 0x14u
            }
        }

        class RefCast(initValue: HeapType) : OpCodeWithValue<HeapType>(initValue) {
            companion object {
                const val code: UByte = 0x16u
            }
        }

        object AnyConvertExtern : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x1Au
        }

        object ExternConvertAny : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x1Bu
        }

        class RefCastNullable(initValue: HeapType) : OpCodeWithValue<HeapType>(initValue) {
            companion object {
                const val code: UByte = 0x17u
            }
        }

        class BrOnCastFail(label: Int, srcType: UInt, dstType: UInt) : OpCodeGCBreak(label, srcType, dstType) {
            companion object {
                const val code: UByte = 0x19u
            }
        }

        class ArrayNewDefault(initValue: UInt): OpCodeWithValue<UInt>(initValue) {
            companion object {
                const val code: UByte = 0x07u
            }
        }

        class ArrayNewData(typeIndexDst: UInt, typeIndexSrc: UInt): OpCodeGCSrcDst(typeIndexDst, typeIndexSrc) {
            companion object {
                const val code: UByte = 0x09u
            }
        }

        class ArrayCopy(typeIndexDst: UInt, typeIndexSrc: UInt): OpCodeGCSrcDst(typeIndexDst, typeIndexSrc) {
            companion object {
                const val code: UByte = 0x11u
            }
        }

        class ArrayGet(initValue: UInt): OpCodeWithValue<UInt>(initValue) {
            companion object {
                const val code: UByte = 0x0Bu
            }
        }

        class ArrayGetSigned(initValue: UInt): OpCodeWithValue<UInt>(initValue) {
            companion object {
                const val code: UByte = 0x0Cu
            }
        }

        class ArrayGetUnsigned(initValue: UInt): OpCodeWithValue<UInt>(initValue) {
            companion object {
                const val code: UByte = 0x0Du
            }
        }

        class ArraySet(initValue: UInt) : OpCodeWithValue<UInt>(initValue) {
            companion object {
                const val code: UByte = 0x0Eu
            }
        }

        object ArrayLength : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x0Fu
        }
    }
    object Ref {
        class Null(heapType: WasmHeapType): OpCodeWithValue<WasmHeapType>(heapType) {
            companion object {
                const val code: UByte = 0xD0u
            }
        }

        object IsNull: OpCode {
            const val code: UByte = 0xD1u
        }

        class Func(index: UInt): OpCodeWithValue<UInt>(index) {
            companion object {
                const val code: UByte = 0xD2u
            }
        }

        object Eq: OpCode {
            const val code: UByte = 0xD3u
        }

        object AsNonNull: OpCode {
            const val code: UByte = 0xD4u
        }
    }
}


open class OpCodeGCStruct(val typeIndex: UInt, val fieldIndex: UInt) : OpCode {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as OpCodeGCStruct
        return (this.typeIndex == other.typeIndex) && (this.fieldIndex == other.fieldIndex)
    }

    override fun hashCode(): Int {
        var result = typeIndex.hashCode()
        result = 31 * result + fieldIndex.hashCode()
        return result
    }
}

open class OpCodeGCSrcDst(val typeIndexDst: UInt, val typeIndexSrc: UInt) : OpCode {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as OpCodeGCSrcDst
        return (this.typeIndexDst == other.typeIndexDst) && (this.typeIndexSrc == other.typeIndexSrc)
    }

    override fun hashCode(): Int {
        var result = typeIndexDst.hashCode()
        result = 31 * result + typeIndexSrc.hashCode()
        return result
    }
}

open class OpCodeGCBreak(val label: Int, val srcType: UInt, val dstType: UInt) : OpCode {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as OpCodeGCBreak
        return (this.label == other.label) && (this.srcType == other.srcType) && (this.dstType == other.dstType)
    }

    override fun hashCode(): Int {
        var result = label
        result = 31 * result + srcType.hashCode()
        result = 31 * result + dstType.hashCode()
        return result
    }
}


@JvmInline
value class HeapType(val type: Int)