package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import kotlin.collections.emptyList

class WasmSectionTypes(size: Int, offset: Int) : WasmSection(size, offset) {
    companion object {
        const val SECTION_ID: Byte = 1
    }

    fun CommonByteBuffer.parseTypes(): List<WasmTypeRecord> {
        position(offset)

        val typeRecords = mutableListOf<WasmTypeRecord>()

        consumeArray {
            val type = get().toUByte()
            when (type) {
                0x4E.toUByte() -> {
                    typeRecords.add(recType())
                }
                else -> {
                    position(position() - 1)
                    typeRecords.add(subtype())
                }
            }
        }

        return typeRecords
    }

    private fun CommonByteBuffer.recType(): WasmTypeRecord {
        val subtype = mutableListOf<WasmTypeRecord>()
        consumeArray { subtype.add(subtype()) }
        return WasmRecursiveTypeRecord(subtype)
    }
    private fun CommonByteBuffer.subtype():WasmTypeRecord {
        val subType = get()
        return when (subType) {
            0x4f.toByte(),
            0x50.toByte() -> {
                val superTypers = mutableListOf<UInt>()
                consumeArray {
                    val subType = getULEB128().toUInt()
                    superTypers.add(subType)
                }
                compType(superTypers.toList(), subType.toUByte() == 0x4f.toUByte())
            }

            else -> {
                position(position() - 1)
                compType(emptyList(), true)
            }
        }
    }

    private fun CommonByteBuffer.compType(superTypers: List<UInt>, isFinal: Boolean): WasmTypeRecord {
        return when (get()) {
            0x5e.toByte() -> arrayType()
            0x5f.toByte() -> structType(superTypers, isFinal)
            0x60.toByte() -> funcType()
            else -> error("unknown type id: 0x${get().toHexString()}")
        }
    }

    private fun CommonByteBuffer.arrayType(): WasmTypeRecord {
        return WasmArrayTypeRecord(fieldType())
    }
    private fun CommonByteBuffer.structType(superTypers: List<UInt>, isFinal: Boolean): WasmTypeRecord {
        val fields = mutableListOf<WasmFieldType>()
        consumeArray {
            fields.add(fieldType())
        }
        return WasmStructTypeRecord(superTypers, fields, isFinal)
    }
    private fun CommonByteBuffer.funcType(): WasmTypeRecord {
        val params = mutableListOf<WasmValueType<*>>()
        consumeArray {
            params.add(valueType())
        }

        val retTypes = mutableListOf<WasmValueType<*>>()
        consumeArray {
            retTypes.add(valueType())
        }

        return WasmSectionFunctionTypeRecord(params, retTypes)
    }

    private fun CommonByteBuffer.fieldType(): WasmFieldType {
        val storageType = storageType()
        val isMutable = get() == 0x01.toByte()
        return WasmFieldType(storageType, isMutable)
    }

    private fun CommonByteBuffer.storageType(): WasmValueType<*> {
        return pactType() ?: run {
            position(position() - 1)
            valueType()
        }
    }

    private fun CommonByteBuffer.pactType(): WasmPactType? {
        return when (get()) {
            0x77.toByte() -> WasmPactTypeI16
            0x78.toByte() -> WasmPactTypeI8
            else -> null
        }
    }
}

internal fun CommonByteBuffer.referenceType(): WasmReferenceType {
    return when (get()) {
        0x63.toByte() -> {
            WasmNullableReferenceType(heapType())
        }

        0x64.toByte() -> {
            WasmNonNullableReferenceType(heapType())
        }

        else -> {
            position(position() - 1)
            WasmReferenceHeapType(heapType())
        }
    }

}

internal fun CommonByteBuffer.limits(): WasmLimits<*> {
    return when (get()) {
        0x00.toByte() -> WasmOpenI32Limits(getULEB128().toInt())
        0x01.toByte() -> WasmFixedI32Limits(getULEB128().toInt(), getULEB128().toInt())
        0x02.toByte() -> WasmOpenI64Limits(getULEB128())
        0x03.toByte() -> WasmFixedI64Limits(getULEB128(), getULEB128())
        else -> error("unknown limits type")
    }
}

internal fun CommonByteBuffer.valueType(): WasmValueType<*> {
    return when (get()) {
        0x7c.toByte() -> WasmF32Type
        0x7d.toByte() -> WasmF64Type
        0x7e.toByte() -> WasmI64Type
        0x7f.toByte() -> WasmI32Type
        0x7b.toByte() -> WasmVecType

        else -> {
            position(position() - 1)
            WasmRefType(referenceType())
        }
    }
}

internal fun CommonByteBuffer.heapType(): WasmHeapType {
    return when (get()) {
        0x69.toByte() -> WasmExnRefHeapType()
        0x6a.toByte() -> WasmArrayRefHeapType()
        0x6b.toByte() -> WasmStructRefHeapType()
        0x6c.toByte() -> WasmI31RefHeapType()
        0x6d.toByte() -> WasmEqRefHeapType()
        0x6e.toByte() -> WasmAnyRefHeapType()
        0x6f.toByte() -> WasmExternRefHeapType()
        0x70.toByte() -> WasmFuncHeapType()
        0x71.toByte() -> WasmNoneHeapType()
        0x72.toByte() -> WasmNoExternRefHeapType()
        0x73.toByte() -> WasmNoFuncHeapType()
        0x74.toByte() -> WasmNoExnHeapType()
        else -> {
            position(position() - 1)
            WasmIndexHeapType(getULEB128())
        }
    }
}

sealed class WasmTypeRecord

data class WasmFieldType(val storageType: WasmValueType<*>, val isMutable: Boolean)

data class WasmRecursiveTypeRecord(val entries: List<WasmTypeRecord>): WasmTypeRecord()
data class WasmArrayTypeRecord(val fieldType: WasmFieldType ): WasmTypeRecord()
data class WasmStructTypeRecord(val superTypes: List<UInt>, val fields: List<WasmFieldType>, val isFinal: Boolean): WasmTypeRecord()

data class WasmSectionFunctionTypeRecord(
    val params: List<WasmValueType<*>>,
    val retTypes: List<WasmValueType<*>>
): WasmTypeRecord()

sealed class  WasmValueType<T>(open val code: T)

sealed class WasmNumType(code: Byte) : WasmValueType<Byte>(code)
object WasmF32Type : WasmNumType(0x7c)
object WasmF64Type : WasmNumType(0x7d)
object WasmI32Type : WasmNumType(0x7f)
object WasmI64Type : WasmNumType(0x7e)
object WasmVecType : WasmValueType<Byte>(0x7b)

sealed class WasmPactType(override val code: Byte):WasmValueType<Byte>(0x70)
object WasmPactTypeI16 : WasmPactType(0x77)
object WasmPactTypeI8 : WasmPactType(0x78)

class WasmRefType(code: WasmReferenceType) : WasmValueType<WasmReferenceType>(code)

class WasmTagType(val index: WasmTypeIndexExternalType) : WasmExternalType(0x4)
sealed class WasmReferenceType(val heapType: WasmHeapType)

/**
 * spec 5.3.3
 */
sealed class WasmHeapType(val type: Long)
class WasmExnRefHeapType: WasmHeapType(0x69)
class WasmArrayRefHeapType: WasmHeapType(0x6a)
class WasmStructRefHeapType: WasmHeapType(0x6b)
class WasmI31RefHeapType: WasmHeapType(0x6c)
class WasmEqRefHeapType: WasmHeapType(0x6d)
class WasmAnyRefHeapType: WasmHeapType(0x6e)
class WasmExternRefHeapType: WasmHeapType(0x6f)
class WasmFuncHeapType: WasmHeapType(0x70)
class WasmNoneHeapType: WasmHeapType(0x71)
class WasmNoExternRefHeapType: WasmHeapType(0x72)
class WasmNoFuncHeapType: WasmHeapType(0x73)
class WasmNoExnHeapType: WasmHeapType(0x74)
class WasmIndexHeapType(type: Long): WasmHeapType(type)

/**
 * spec 5.3.4
 */
class WasmNonNullableReferenceType(heapType: WasmHeapType): WasmReferenceType(heapType)
class WasmNullableReferenceType(heapType: WasmHeapType): WasmReferenceType(heapType)
class WasmReferenceHeapType(heapType: WasmHeapType): WasmReferenceType(heapType)