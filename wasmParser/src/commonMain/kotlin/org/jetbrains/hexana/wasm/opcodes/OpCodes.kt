package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.sections.StandardTypesParser
import org.jetbrains.hexana.wasm.sections.WasmHeapType
import org.jetbrains.hexana.wasm.sections.WasmValueType
import kotlin.jvm.JvmInline

object OpCodes {
    // Wasm spec 5.4.1
    object ParametricInstructions {
        object Unreachable : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x00u
        }

        object Nop : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x01u
        }

        object Drop : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x1Au
        }

        object Select : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x1Bu
        }

        class SelectWithType(value: List<WasmValueType<*>>) : OpCodeWithValue<List<WasmValueType<*>>>(value) {
            companion object{
                const val code: UByte = 0x1Cu
            }
        }
    }
    object NumericInstructions {
        object i32 : I32()
        object i64 : I64()
        object f64 : F64()
        object f32 : F32()
    }
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

        class Call(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x10u
            }
        }
        class ReturnCallIndirect(value0: UInt, value1: UInt) : OpCodeWithTwoValue<UInt, UInt>(value0, value1) {
            companion object {
                const val code: UByte = 0x13u
            }
        }

        class CallRef(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x14u
            }
        }

        class ReturnCallRef(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x15u
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

        class BrTable(values: List<UInt>, val labelIdx: UInt) : OpCodeWithValue<List<UInt>>(values) {
            companion object {
                const val code: UByte = 0x0Eu
            }
        }

        open class Block(val type: StandardTypesParser.WasmBlockType?, value: List<OpCode>) : OpCodeWithValue<List<OpCode>>(value) {
            companion object {
                const val code: UByte = 0x02u
            }
        }

        class Loop(type: StandardTypesParser.WasmBlockType, value: List<OpCode>) : Block(type, value) {
            companion object {
                const val code: UByte = 0x03u
            }
        }

        class If(type: StandardTypesParser.WasmBlockType, value: List<OpCode>) : Block(type, value) {
            companion object {
                const val code: UByte = 0x04u
            }
        }

        class Else(value: List<OpCode>) :  Block(null, value){
            companion object {
                const val code: UByte = 0x05u
            }
        }

        class BrOnNull(value: UInt): OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0xD5u
            }
        }

        class BrOnNonNull(value: UInt): OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0xD6u
            }
        }

        class Trow(tagIndx: UInt) : OpCodeWithValue<UInt>(tagIndx) {
            companion object {
                const val code: UByte = 0x08u
            }
        }

        object ThrowRef: OpCode {
            const val code: UByte = 0x0Au
        }

        class TryTable(val blockType: StandardTypesParser.WasmBlockType, val catches: List<CatchType>, val code: List<OpCode>) : OpCode {
            companion object {
                const val code: UByte = 0x1Fu
            }
        }

        sealed class CatchType(val labelIdx: UInt) {
            class Catch(val tagIdx: UInt, labelIdx: UInt) : CatchType(labelIdx)
            class CatchRef(val tagIdx: UInt, labelIdx: UInt) : CatchType(labelIdx)
            class CatchAll(labelIdx: UInt) : CatchType(labelIdx)
            class CatchAllRef(labelIdx: UInt) : CatchType(labelIdx)
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

    // Wasm spec 5.4.5
    object Memory {
        object i32 {
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

        }
        object i64 {
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
        }

        object f32 {
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
        }
        object f64 {
            class Load(value: MemArg) : OpCodeWithValue<MemArg>(value) {
                companion object {
                    val code: UByte = 0x2Bu
                }
            }

            class Store(value: MemArg) : OpCodeWithValue<MemArg>(value) {
                companion object {
                    val code: UByte = 0x39u
                }
            }
        }
        class Size(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x3Fu
            }
        }
        class Grow(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x40u
            }
        }

        class Init(val dataIdx: UInt, val memIdx: UInt) : OpCode {
            companion object {
                const val code: UByte = 0x08u
            }
        }

        class Copy(val memIdxY: UInt, val memIdxX: UInt): OpCode {
            companion object {
                const val code: UByte = 0x0au
            }
        }

        class Fill(val memIdx: UInt): OpCode {
            companion object {
                const val code: UByte = 0x0bu
            }
        }

        object Data {
            class Drop (val dataIdx: UInt): OpCode {
                companion object {
                    const val code: UByte = 0x09u
                }
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

    object Table {
        class Get(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x25u
            }
        }

        class Set(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x26u
            }
        }
    }

    object GC {
        class StructNew(value: UInt) : OpCodeWithValue<UInt>(value) {
            companion object {
                const val code: UByte = 0x00u
            }
        }

        class StructNewDefault(typeIdIndex: UInt) : OpCodeWithValue<UInt>(typeIdIndex) {
            companion object {
                const val code: UByte = 0x01u
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

        class ArrayNew(typeIdIndex: UInt) : OpCodeWithValue<UInt>(typeIdIndex) {
            companion object {
                const val code: UByte = 0x06u
            }
        }

        class RefTest(initValue: HeapType) : OpCodeWithValue<HeapType>(initValue) {
            companion object {
                const val code: UByte = 0x14u
            }
        }

        class RefTestNullable(initValue: HeapType) : OpCodeWithValue<HeapType>(initValue) {
            companion object {
                const val code: UByte = 0x15u
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

        object RefI31 : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x1Cu
        }

        object I31GetS : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x1Du
        }

        object I31GetU : OpCodeWithValue<Unit>(Unit) {
            const val code: UByte = 0x1Eu
        }

        class RefCastNullable(initValue: HeapType) : OpCodeWithValue<HeapType>(initValue) {
            companion object {
                const val code: UByte = 0x17u
            }
        }

        class BrOnCast(label: UInt, val srcType: HeapType, val dstType: HeapType) : OpCodeWithValue<UInt>(label) {
            companion object {
                const val code: UByte = 0x18u
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

        class ArrayNewFixed(typeIndex: UInt, size: UInt): OpCodeWithTwoValue<UInt, UInt>(typeIndex, size) {
            companion object {
                const val code: UByte = 0x08u
            }
        }

        class ArrayNewData(typeIndexDst: UInt, typeIndexSrc: UInt): OpCodeGCSrcDst(typeIndexDst, typeIndexSrc) {
            companion object {
                const val code: UByte = 0x09u
            }
        }

        class ArrayFill(typeIndex: UInt): OpCodeWithValue<UInt>(typeIndex) {
            companion object {
                const val code: UByte = 0x10u
            }
        }

        class ArrayCopy(typeIndexDst: UInt, typeIndexSrc: UInt): OpCodeGCSrcDst(typeIndexDst, typeIndexSrc) {
            companion object {
                const val code: UByte = 0x11u
            }
        }

        class ArrayInitData(typeIndex: UInt, val dataIndex: UInt): OpCodeWithValue<UInt>(typeIndex) {
            companion object {
                const val code: UByte = 0x12u
            }
        }

        class ArrayInitElem(typeIndex: UInt, val elemIndex: UInt): OpCodeWithValue<UInt>(typeIndex) {
            companion object {
                const val code: UByte = 0x13u
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