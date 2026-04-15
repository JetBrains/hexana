package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange
import org.jetbrains.hexana.wasm.components.WasmComponentCoreType
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.recType
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.subtype

class WasmSectionTypes(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Types", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 1
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    override fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        return with (buffer) {
            parseTypes().mapIndexed { index, record ->
                WasmNamedRange(record, "type $index")
            }
        }
    }

    fun CommonByteBuffer.parseTypes(): List<WasmTypeRecord> {
        position(contentOffset)

        val typeRecords = mutableListOf<WasmTypeRecord>()

        consumeArray {
            val offset = position()
            val type = get().toUByte()
            when (type) {
                0x4E.toUByte() -> {
                    typeRecords.add(recType())
                }
                else -> {
                    position(position() - 1)
                    typeRecords.add(subtype(offset))
                }
            }
        }

        return typeRecords
    }

}

object StandardTypesParser {
    fun CommonByteBuffer.recType(): WasmTypeRecord {
        val subtype = mutableListOf<WasmTypeRecord>()
        val offset = position()
        consumeArray {
            val offset = position()
            subtype.add(subtype(offset))
        }
        return WasmRecursiveTypeRecord(offset, sizeFromOffset(offset), subtype)
    }

    fun CommonByteBuffer.subtype(offset: Int):WasmTypeRecord {
        val subType = get()
        return when (subType) {
            0x4f.toByte(),
            0x50.toByte() -> {
                val superTypers = mutableListOf<UInt>()
                consumeArray {
                    val subType = getULEB128().toUInt()
                    superTypers.add(subType)
                }
                compType(offset, superTypers.toList(), subType.toUByte() == 0x4f.toUByte())
            }

            else -> {
                position(position() - 1)
                compType(offset, emptyList(), true)
            }
        }
    }

    fun CommonByteBuffer.compType(offset: Int, superTypers: List<UInt>, isFinal: Boolean): WasmTypeRecord {
        return when (get()) {
            0x5e.toByte() -> arrayType(offset)
            0x5f.toByte() -> structType(offset, superTypers, isFinal)
            0x60.toByte() -> funcType(offset)
            else -> error("unknown type id: 0x${get().toHexString()}")
        }
    }

    private fun CommonByteBuffer.arrayType(offset: Int): WasmTypeRecord {
        val fieldType = fieldType()
        return WasmArrayTypeRecord(offset, sizeFromOffset(offset), fieldType)
    }
    private fun CommonByteBuffer.structType(offset: Int, superTypers: List<UInt>, isFinal: Boolean): WasmTypeRecord {
        val fields = mutableListOf<WasmFieldType>()
        consumeArray {
            fields.add(fieldType())
        }
        return WasmStructTypeRecord(offset, sizeFromOffset(offset), superTypers, fields, isFinal)
    }
    private fun CommonByteBuffer.funcType(offset: Int): WasmTypeRecord {
        val params = mutableListOf<WasmValueType<*>>()
        consumeArray {
            params.add(valueType()!!)
        }

        val retTypes = mutableListOf<WasmValueType<*>>()
        consumeArray {
            retTypes.add(valueType()!!)
        }
        return WasmSectionFunctionTypeRecord(offset, sizeFromOffset(offset), params, retTypes)
    }

    private fun CommonByteBuffer.fieldType(): WasmFieldType {
        val storageType = storageType()
        val isMutable = get() == 0x01.toByte()
        return WasmFieldType(storageType, isMutable)
    }

    private fun CommonByteBuffer.storageType(): WasmValueType<*> {
        return pactType() ?: run {
            position(position() - 1)
            valueType()!!
        }
    }

    private fun CommonByteBuffer.pactType(): WasmPactType? {
        return when (get()) {
            0x77.toByte() -> WasmPactTypeI16
            0x78.toByte() -> WasmPactTypeI8
            else -> null
        }
    }
    fun CommonByteBuffer.referenceType(): WasmReferenceType? {
        return when (get()) {
            0x63.toByte() -> {
                WasmNullableReferenceType(heapType())
            }

            0x64.toByte() -> {
                WasmNonNullableReferenceType(heapType())
            }

            else -> {
                position(position() - 1)
                absHeapType()?.let {
                    WasmReferenceHeapType(it)
                }
            }
        }
    }

    /**
     * 5.3.9 Limits
     */
    fun CommonByteBuffer.limits(): WasmLimits<*> {
        val limitType = get()
        return when (limitType) {
            0x00.toByte() -> WasmOpenI32Limits(getULEB128().toInt())
            0x01.toByte() -> WasmFixedI32Limits(getULEB128().toInt(), getULEB128().toInt())
            0x03.toByte() -> WasmFixedI32Limits(uint32().toInt(), uint32().toInt(), true)
            0x04.toByte() -> WasmOpenI64Limits(getULEB128())
            0x05.toByte() -> WasmFixedI64Limits(getULEB128(), getULEB128())
            else -> error("unknown limits type")
        }
    }

    fun CommonByteBuffer.valueType(): WasmValueType<*>? {
        return when (val code = get()) {
            WasmVecType.code -> WasmVecType
            else -> {
                val numType = code.toWasmNumTypeOrNull()
                if (numType != null) {
                    numType
                } else {
                    position(position() - 1)
                    referenceType()?.let { WasmRefType(it) }
                }
            }
        }
    }

    interface WasmBlockType {
        object Empty : WasmBlockType
        class S33(private val _value: UInt) : WasmBlockType, WasmS33 {
            override val value: UInt
                get() = _value
        }

    }


    fun CommonByteBuffer.blockType(): WasmBlockType {
        return when (val code = get()) {
            0x40.toByte() -> WasmBlockType.Empty
            else -> {
                position(position() - 1)
                valueType()?: run {
                    position(position() - 1)
                    WasmBlockType.S33(getULEB128().toUInt())
                }
            }
        }
    }

    fun CommonByteBuffer.absHeapType() : WasmAbsHeapType? {
        return when (get()) {
            0x69.toByte() -> WasmExnRefHeapType
            0x6a.toByte() -> WasmArrayRefHeapType
            0x6b.toByte() -> WasmStructRefHeapType
            0x6c.toByte() -> WasmI31RefHeapType
            0x6d.toByte() -> WasmEqRefHeapType
            0x6e.toByte() -> WasmAnyRefHeapType
            0x6f.toByte() -> WasmExternRefHeapType
            0x70.toByte() -> WasmFuncHeapType
            0x71.toByte() -> WasmNoneHeapType
            0x72.toByte() -> WasmNoExternRefHeapType
            0x73.toByte() -> WasmNoFuncHeapType
            0x74.toByte() -> WasmNoExnHeapType
            else -> null
        }
    }

    fun CommonByteBuffer.heapType(): WasmHeapType {
        return absHeapType() ?: run {
                position(position() - 1)
                WasmIndexHeapType(getULEB128())
        }
    }
}

sealed interface WasmTypeRecord : WasmRange, WasmComponentCoreType

data class WasmFieldType(val storageType: WasmValueType<*>, val isMutable: Boolean)

data class WasmRecursiveTypeRecord(
    override val offset: Int,
    override val size: Int,
    val entries: List<WasmTypeRecord>
) : WasmTypeRecord

data class WasmArrayTypeRecord(
    override val offset: Int,
    override val size: Int,
    val fieldType: WasmFieldType
) : WasmTypeRecord

data class WasmStructTypeRecord(
    override val offset: Int,
    override val size: Int,
    val superTypes: List<UInt>,
    val fields: List<WasmFieldType>,
    val isFinal: Boolean
) : WasmTypeRecord

data class WasmSectionFunctionTypeRecord(
    override val offset: Int,
    override val size: Int,
    val params: List<WasmValueType<*>>,
    val retTypes: List<WasmValueType<*>>
) : WasmTypeRecord

sealed class  WasmValueType<T>(open val code: T) : StandardTypesParser.WasmBlockType

sealed class WasmNumType(code: Byte) : WasmValueType<Byte>(code)
object WasmF32Type : WasmNumType(0x7d)
object WasmF64Type : WasmNumType(0x7c)
object WasmI32Type : WasmNumType(0x7f)
object WasmI64Type : WasmNumType(0x7e)
object WasmVecType : WasmValueType<Byte>(0x7b)

fun Byte.toWasmNumTypeOrNull(): WasmNumType? =
    when (this) {
        WasmI32Type.code -> WasmI32Type
        WasmI64Type.code -> WasmI64Type
        WasmF32Type.code -> WasmF32Type
        WasmF64Type.code -> WasmF64Type
        else -> null
    }

fun Byte.toWasmNumType(): WasmNumType =
    toWasmNumTypeOrNull() ?: error("Unhandled numeric type byte: 0x${toUByte().toString(16)}")

sealed class WasmPactType(override val code: Byte):WasmValueType<Byte>(0x70)
object WasmPactTypeI16 : WasmPactType(0x77)
object WasmPactTypeI8 : WasmPactType(0x78)

class WasmRefType(code: WasmReferenceType) : WasmValueType<WasmReferenceType>(code) {
    override fun equals(other: Any?): Boolean {
        val b = other as? WasmRefType ?: return false
        return code == b.code
    }

    override fun hashCode(): Int = code.hashCode()
}

class WasmTagType(val index: WasmTypeIndexExternalType) : WasmExternalType(0x4) {
    override fun equals(other: Any?): Boolean {
        val b = other as? WasmTagType ?: return false
        return index == b.index
    }

    override fun hashCode(): Int = index.hashCode()
}
sealed interface WasmReferenceType {
    val heapType: WasmHeapType
}

interface WasmS33 {
    val value: UInt
}

/**
 * spec 5.3.3
 */
sealed class WasmHeapType(val type: Long)
sealed class WasmAbsHeapType(type: Long) : WasmHeapType(type)
sealed class WasmHeapS33(type: Long) : WasmHeapType(type), WasmS33 {
    override val value: UInt
        get() = type.toUInt()
}

object WasmExnRefHeapType: WasmAbsHeapType(0x69)
object WasmArrayRefHeapType: WasmAbsHeapType(0x6a)
object WasmStructRefHeapType: WasmAbsHeapType(0x6b)
object WasmI31RefHeapType: WasmAbsHeapType(0x6c)
object WasmEqRefHeapType: WasmAbsHeapType(0x6d)
object WasmAnyRefHeapType: WasmAbsHeapType(0x6e)
object WasmExternRefHeapType: WasmAbsHeapType(0x6f)
object WasmFuncHeapType: WasmAbsHeapType(0x70)
object WasmNoneHeapType: WasmAbsHeapType(0x71)
object WasmNoExternRefHeapType: WasmAbsHeapType(0x72)
object WasmNoFuncHeapType: WasmAbsHeapType(0x73)
object WasmNoExnHeapType: WasmAbsHeapType(0x74)

class WasmIndexHeapType(type: Long): WasmHeapType(type) {
    override fun equals(other: Any?): Boolean {
        val b = other as? WasmIndexHeapType ?: return false
        return type == b.type
    }

    override fun hashCode(): Int = type.hashCode()
}

/**
 * spec 5.3.4
 */
class WasmNonNullableReferenceType(override val heapType: WasmHeapType): WasmReferenceType {
    override fun equals(other: Any?): Boolean {
        return other is WasmNonNullableReferenceType && other.heapType == heapType
    }

    override fun hashCode(): Int = 31 * this::class.hashCode() + heapType.hashCode()
}

class WasmNullableReferenceType(override val heapType: WasmHeapType): WasmReferenceType {
    override fun equals(other: Any?): Boolean {
        return other is WasmNullableReferenceType && other.heapType == heapType
    }

    override fun hashCode(): Int = 31 * this::class.hashCode() + heapType.hashCode()
}

class WasmReferenceHeapType(override val heapType: WasmHeapType): WasmReferenceType {
    override fun equals(other: Any?): Boolean {
        return other is WasmReferenceHeapType && other.heapType == heapType
    }

    override fun hashCode(): Int = 31 * this::class.hashCode() + heapType.hashCode()
}
