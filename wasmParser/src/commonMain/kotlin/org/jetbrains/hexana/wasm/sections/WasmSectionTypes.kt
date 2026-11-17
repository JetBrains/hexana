package org.jetbrains.hexana.wasm.sections

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmNamedRange
import org.jetbrains.hexana.wasm.WasmRange
import org.jetbrains.hexana.wasm.components.WasmComponentCoreType
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.recType
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.subtype
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.subtypeFromCode

class WasmSectionTypes(sectionSize: Int, sectionOffset: Int, contentSize: Int, contentOffset: Int) :
    WasmSection("Types", sectionSize, sectionOffset, contentSize, contentOffset) {

    companion object {
        const val SECTION_ID: Byte = 1
    }

    constructor(s: WasmSectionUnresolved): this(s.sectionSize, s.sectionOffset, s.contentSize, s.contentOffset)

    override fun getRecordRanges(buffer: CommonByteBuffer): List<WasmNamedRange> {
        // One range per *type index*, not per top-level record. A WasmGC `(rec
        // …)` group declares many types under a single top-level record; in the
        // index space (and in the WAT view) each entry is its own type. Emit one
        // range per leaf type so the chunk tree gets one chunk per type instead
        // of a single multi-MB chunk for the whole rec group (which froze the
        // WAT-virtualized view in Skia paragraph layout on javac-class binaries).
        return with(buffer) {
            var typeIndex = 0
            val (records, _) = parseTypesPartial()
            records.flatMap { record ->
                val leaves = if (record is WasmRecursiveTypeRecord) record.entries else listOf(record)
                leaves.map { leaf -> WasmNamedRange(leaf, "type ${typeIndex++}") }
            }
        }
    }

    /**
     * Parses the type section, degrading to the decoded prefix when an entry
     * cannot be decoded (truncated section, unknown encoding). Use
     * [parseTypesPartial] when the failure offset is needed for reporting;
     * [org.jetbrains.hexana.wasm.sections.ParseProblem] emission relies on it.
     */
    fun CommonByteBuffer.parseTypes(): List<WasmTypeRecord> = parseTypesPartial().first

    /**
     * Parses as many type records as possible. On the first unrecoverable byte,
     * stops and returns the partial list paired with the byte offset at which
     * parsing failed (null second means the section parsed fully).
     */
    fun CommonByteBuffer.parseTypesPartial(): Pair<List<WasmTypeRecord>, Int?> = run {
        position(contentOffset)
        consumeArrayPartial(contentOffset + contentSize) {
            val offset = position()
            // Type-group discriminants are S33 SLEB128 values per the GC spec.
            when (val typCode = getSLEB128().toInt()) {
                -0x32 -> recType()
                else -> subtypeFromCode(typCode, offset)
            }
        }.let { it.values to it.failureOffset }
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

    fun CommonByteBuffer.subtype(offset: Int): WasmTypeRecord {
        val typCode = getSLEB128().toInt()
        return subtypeFromCode(typCode, offset)
    }

    internal fun CommonByteBuffer.subtypeFromCode(typCode: Int, offset: Int): WasmTypeRecord {
        return when (typCode) {
            -0x31, // 0x4f sub final
            -0x30  // 0x50 sub
            -> {
                val isFinal = typCode == -0x31
                val superTypers = mutableListOf<UInt>()
                consumeArray {
                    val idx = consumeUnsignedUInt()
                    superTypers.add(idx)
                }
                // custom-descriptors proposal: optional descriptor clause (0x4D) and/or
                // describes clause (0x4C) appear between the supertype list and the comptype.
                // Their presence is recorded so the proposal detector can flag a module that
                // uses only these clauses; the referenced type index is consumed, not modeled.
                var hasDescriptorClause = false
                var peeked = get()
                while (peeked == 0x4D.toByte() || peeked == 0x4C.toByte()) {
                    hasDescriptorClause = true
                    consumeUnsignedUInt() // clause type index
                    peeked = get()
                }
                position(position() - 1) // un-consume the non-clause byte (comptype prefix)
                compType(offset, superTypers.toList(), isFinal, hasDescriptorClause)
            }

            else -> compTypeFromCode(typCode, offset, emptyList(), true, false)
        }
    }

    fun CommonByteBuffer.compType(
        offset: Int,
        superTypers: List<UInt>,
        isFinal: Boolean,
        hasDescriptorClause: Boolean = false,
    ): WasmTypeRecord {
        val typCode = getSLEB128().toInt()
        return compTypeFromCode(typCode, offset, superTypers, isFinal, hasDescriptorClause)
    }

    private fun CommonByteBuffer.compTypeFromCode(
        typCode: Int,
        offset: Int,
        superTypers: List<UInt>,
        isFinal: Boolean,
        hasDescriptorClause: Boolean,
    ): WasmTypeRecord {
        return when (typCode) {
            -0x22 -> arrayType(offset, superTypers, isFinal, hasDescriptorClause)   // 0x5e array
            -0x21 -> structType(offset, superTypers, isFinal, hasDescriptorClause)  // 0x5f struct
            -0x20 -> funcType(offset, superTypers, isFinal, hasDescriptorClause)    // 0x60 func
            else -> error("unknown type id: 0x${typCode.toUInt().toString(16)}")
        }
    }

    private fun CommonByteBuffer.arrayType(
        offset: Int,
        superTypers: List<UInt>,
        isFinal: Boolean,
        hasDescriptorClause: Boolean,
    ): WasmTypeRecord {
        val fieldType = fieldType()
        return WasmArrayTypeRecord(offset, sizeFromOffset(offset), fieldType, superTypers, isFinal, hasDescriptorClause)
    }
    private fun CommonByteBuffer.structType(
        offset: Int,
        superTypers: List<UInt>,
        isFinal: Boolean,
        hasDescriptorClause: Boolean,
    ): WasmTypeRecord {
        val fields = mutableListOf<WasmFieldType>()
        consumeArray {
            fields.add(fieldType())
        }
        return WasmStructTypeRecord(offset, sizeFromOffset(offset), superTypers, fields, isFinal, hasDescriptorClause)
    }
    private fun CommonByteBuffer.funcType(
        offset: Int,
        superTypers: List<UInt>,
        isFinal: Boolean,
        hasDescriptorClause: Boolean,
    ): WasmTypeRecord {
        val params = mutableListOf<WasmValueType<*>>()
        consumeArray {
            params.add(valueType()!!)
        }

        val retTypes = mutableListOf<WasmValueType<*>>()
        consumeArray {
            retTypes.add(valueType()!!)
        }
        return WasmSectionFunctionTypeRecord(offset, sizeFromOffset(offset), params, retTypes, superTypers, isFinal, hasDescriptorClause)
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
     *
     * Flags byte is a bitfield:
     *   bit 0 = has-max
     *   bit 1 = shared (threads proposal)
     *   bit 2 = 64-bit index type (memory64 proposal)
     *   bit 3 = custom page size (an extra ULEB128 page-size-log2 field follows max or min)
     */
    fun CommonByteBuffer.limits(): WasmLimits<*> {
        val flags = get().toInt() and 0xFF
        val hasMax    = flags and 0x01 != 0
        val shared    = flags and 0x02 != 0
        val is64      = flags and 0x04 != 0
        val hasPageSz = flags and 0x08 != 0

        return if (is64) {
            val min = getULEB128()
            val max = if (hasMax) getULEB128() else null
            val pageSizeLog2 = if (hasPageSz) consumeUnsignedInt() else null
            if (hasMax) {
                WasmFixedI64Limits(min, max!!, shared, pageSizeLog2)
            } else {
                WasmOpenI64Limits(min, shared, pageSizeLog2)
            }
        } else {
            val min = consumeUnsignedInt()
            val max = if (hasMax) consumeUnsignedInt() else null
            val pageSizeLog2 = if (hasPageSz) consumeUnsignedInt() else null
            if (hasMax) {
                WasmFixedI32Limits(min, max!!, shared, pageSizeLog2)
            } else {
                WasmOpenI32Limits(min, shared, pageSizeLog2)
            }
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
        val savedPos = position()
        return absHeapType() ?: run {
            position(savedPos)
            val byte = get()
            if (byte == 0x62.toByte()) {
                WasmExactHeapType(getULEB128())
            } else {
                position(position() - 1)
                WasmIndexHeapType(getULEB128())
            }
        }
    }
}

sealed interface WasmTypeRecord : WasmRange, WasmComponentCoreType {
    /**
     * Whether this definition carried a `descriptor` (0x4D) or `describes` (0x4C) clause of
     * the custom-descriptors proposal. A `rec` group reports it on its entries, not on itself.
     */
    val hasDescriptorClause: Boolean get() = false

    /** Supertype indices from the `sub` prefix, empty when no `sub` clause was present. */
    val superTypes: List<UInt> get() = emptyList()

    /** True when declared `sub final` or when no `sub` clause was present (defaults to final). */
    val isFinal: Boolean get() = true
}

data class WasmFieldType(val storageType: WasmValueType<*>, val isMutable: Boolean)

data class WasmRecursiveTypeRecord(
    override val offset: Int,
    override val size: Int,
    val entries: List<WasmTypeRecord>
) : WasmTypeRecord

data class WasmArrayTypeRecord(
    override val offset: Int,
    override val size: Int,
    val fieldType: WasmFieldType,
    override val superTypes: List<UInt> = emptyList(),
    override val isFinal: Boolean = true,
    override val hasDescriptorClause: Boolean = false
) : WasmTypeRecord

data class WasmStructTypeRecord(
    override val offset: Int,
    override val size: Int,
    override val superTypes: List<UInt>,
    val fields: List<WasmFieldType>,
    override val isFinal: Boolean,
    override val hasDescriptorClause: Boolean = false
) : WasmTypeRecord

data class WasmSectionFunctionTypeRecord(
    override val offset: Int,
    override val size: Int,
    val params: List<WasmValueType<*>>,
    val retTypes: List<WasmValueType<*>>,
    override val superTypes: List<UInt> = emptyList(),
    override val isFinal: Boolean = true,
    override val hasDescriptorClause: Boolean = false
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

/** custom-descriptors proposal: `exact x` heap type, encoded as `0x62 x:u32`. */
class WasmExactHeapType(type: Long) : WasmHeapType(type) {
    override fun equals(other: Any?): Boolean {
        val b = other as? WasmExactHeapType ?: return false
        return type == b.type
    }

    override fun hashCode(): Int = type.hashCode() * 31 + 1
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
