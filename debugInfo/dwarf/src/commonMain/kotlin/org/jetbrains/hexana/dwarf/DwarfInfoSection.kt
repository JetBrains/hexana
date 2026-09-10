package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation

class DwarfInfoSection(
    contentOffset: Int,
    contentSize: Int,
    accessor: DwarfAccessor
): DwarfSection(contentOffset, contentSize, accessor) {

    private var abbrevCache: Map<UInt, List<DwarfAbbrevEntry>>? = null

    private fun CommonByteBuffer.getAbbrevTables(): Map<UInt, List<DwarfAbbrevEntry>> {
        return abbrevCache ?: with(accessor.debugAbbrevSection()!!) {
            crossSectionOperation {
                scanDwarfAbbrev()
            }
        }.also { abbrevCache = it }
    }

    private var stringCache: List<StringTableEntry>? = null
    fun CommonByteBuffer.getStringTable(): List<StringTableEntry> {
        return stringCache ?: with(accessor.debugStrSection()!!) {
            crossSectionOperation {
                scanDwarfStr()
            }
        }.also { stringCache = it }
    }

    fun CommonByteBuffer.scanDwarfInfo(): List<CompilationUnitHeader<*>> {
        position(contentOffset)
        val compilationUnitHeaders = mutableListOf<CompilationUnitHeader<*>>()
        var headerOffset = contentOffset
        while (position() < contentOffset + contentSize) {
            headerOffset = position()
            val header = readCompilationUnitHeader()
            val limit = headerOffset + header.size
            while (position() < limit) {
                val child = readDebugInformationEntry(header, limit) ?: break
                header.add(child)
            }
            compilationUnitHeaders.add(header)
            position(headerOffset + header.next)
            headerOffset = position()
        }
        return compilationUnitHeaders
    }

    fun CommonByteBuffer.readCompilationUnitHeader(): CompilationUnitHeader<*> {
        val offset = position()
        return when(val value = getIntLE().toUInt()) {
            0xFFFFFFFFu -> readDwarf64Header(offset)
            else -> readDwarf32Header(offset, value)
        }
    }

    private fun CommonByteBuffer.readDwarf64Header(offset: Int): CompilationUnitHeader<*> {
        val size = getLongLE().toULong()
        val version = getShortLE().toUShort()
        return when (version.toInt()) {
            1,2,3,4 -> readDwarf64v4Header(offset, size)
            5 -> readDwarf64v5Header(offset, size)
            else -> throw IllegalArgumentException("Unsupported DWARF version: $version")
        }
    }

    private fun CommonByteBuffer.readDwarf64v4Header( offset: Int, size: ULong ): CompilationUnitHeader<*> {
        val debugAbbrevOffset = getLongLE().toULong()
        val addressSize = get().toUByte()
        return CompilationUnitHeader.Dwarf64Header.V4(offset, size.toInt(), size.toInt() + 12, debugAbbrevOffset, addressSize)
    }

    private fun CommonByteBuffer.readDwarf64v5Header( offset: Int, size: ULong ): CompilationUnitHeader<*> {
        val unitType = get().toUByte().toUnitType()
        val addressSize = get().toUByte()
        val debugAbbrevOffset = getLongLE().toULong()
        val next = size.toInt() + 12
        return when (unitType) {
            UnitType.DW_UT_compile, UnitType.DW_UT_partial -> CompilationUnitHeader.Dwarf64Header.V5.CompileUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType)
            UnitType.DW_UT_skeleton, UnitType.DW_UT_split_compile -> CompilationUnitHeader.Dwarf64Header.V5.SkeletonUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType, getLongLE().toULong())
            UnitType.DW_UT_type, UnitType.DW_UT_split_type -> CompilationUnitHeader.Dwarf64Header.V5.TypeUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType, getLongLE().toULong(), getLongLE().toULong())
            else -> CompilationUnitHeader.Dwarf64Header.V5.CompileUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType)
        }

    }

    private fun CommonByteBuffer.readDwarf32Header( offset: Int, size: UInt ): CompilationUnitHeader<*> {
        val version = getShortLE().toUShort()
        return when (version.toInt()) {
            1,2,3,4 -> readDwarf32v4Header(offset, size)
            5 -> readDwarf32v5Header(offset, size)
            else -> throw IllegalArgumentException("Unsupported DWARF version: $version")
        }
    }

    private fun CommonByteBuffer.readDwarf32v4Header(offset: Int, size: UInt): CompilationUnitHeader<*> {
        val debugAbbrevOffset = getIntLE().toUInt()
        val addressSize = get().toUByte()
        return CompilationUnitHeader.Dwarf32Header.V4(offset, size.toInt(),
            size.toInt() + 4, debugAbbrevOffset, addressSize)
    }

    private fun CommonByteBuffer.readDwarf32v5Header(offset: Int, size: UInt): CompilationUnitHeader<*> {
        val unitType = get().toUByte().toUnitType()
        val addressSize = get().toUByte()
        val debugAbbrevOffset = getIntLE().toUInt()
        val next = size.toInt() + 4
        return when (unitType) {
            UnitType.DW_UT_compile, UnitType.DW_UT_partial -> CompilationUnitHeader.Dwarf32Header.V5.CompileUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType)
            UnitType.DW_UT_skeleton, UnitType.DW_UT_split_compile -> CompilationUnitHeader.Dwarf32Header.V5.SkeletonUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType, getLongLE().toULong())
            UnitType.DW_UT_type, UnitType.DW_UT_split_type -> CompilationUnitHeader.Dwarf32Header.V5.TypeUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType, getLongLE().toULong(), getIntLE().toUInt())
            else -> CompilationUnitHeader.Dwarf32Header.V5.CompileUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType)
        }
    }

    fun CommonByteBuffer.readAttribute(header: DwarfUnit, attribute: DwarfAttribute): DieField {
        val form = attribute.form
        val valueOffset = position()
        val value = readForm(form, header)
        return DieField(attribute, value, valueOffset, position() - valueOffset)
    }

    private fun CommonByteBuffer.readDebugInformationEntry(header: CompilationUnitHeader<*>,  limit: Int): DebugInformationEntry? {
        val offset = position()
        val number = getULEB128()
        if (number == 0L)
            return null
        val key = when (val off = header.debugAbbrevOffset) {
            is UInt -> off
            is ULong -> off.toUInt()
            else -> error("Unexpected type")
        }
        val table = getAbbrevTables()[key] ?: error("No abbrev for key $key")
        val dieAbbrev = table.find { it.number == number.toInt() } ?: error("No abbrev for code $number")

        val attributes = dieAbbrev.attributes.map { attr ->
            readAttribute(header,attr)
        }
        val children = mutableListOf<DebugInformationEntry>()
        if(dieAbbrev.hasChildren) {
            while (position() < limit) {
                readDebugInformationEntry(header, limit)?.let { children.add(it) } ?: break
            }
        }
        return DebugInformationEntry(offset, position() - offset, dieAbbrev.tag, attributes, children)
    }
}

fun CommonByteBuffer.readStringZ(): String {
    val start = position()
    while (get() != 0.toByte()) {
        /* empty */
    }
    val end = position()
    val length = end - 1 - start
    val buffer = ByteArray(length)
    position(start)
    get(buffer, 0, length)
    get() // null byte
    val string = buffer.decodeToString()
    return string
}

/**
 * One attribute of a DIE, as encoded in `.debug_info`.
 *
 * [attribute] is the declaration from `.debug_abbrev`, shared by every DIE that uses the same
 * abbrev code - so its `offset`/`size` describe the declaration, in a different section, not this
 * value. Use [valueOffset]/[valueSize] to address the bytes this field was actually read from.
 */
class DieField(
    val attribute: DwarfAttribute,
    val value: Any,
    /** Offset in `.debug_info` of this field's encoded value. */
    val valueOffset: Int,
    /** Encoded size in bytes; `0` for forms that occupy none (`DW_FORM_flag_present`, `DW_FORM_implicit_const`). */
    val valueSize: Int,
) {
    val intValue: Int?
        get () = when (value) {
            is Int -> value
            is Long -> value.toInt()
            is ULong -> value.toInt()
            is UInt -> value.toInt()
            is UByte -> value.toInt()
            is UShort -> value.toInt()
            is Byte -> value.toInt()
            is Short -> value.toInt()
            is Boolean -> if (value) 1 else 0
            else -> null
        }
}

open class DebugInformationEntry(offset: Int, size: Int, val tag: Tag, val attributes: List<DieField> = listOf(), open val children: List<DebugInformationEntry> = listOf()):DwarfEntry(offset, size)

sealed class CompilationUnitHeader<T>(offset: Int, size: Int, val next: Int, override val version: Int, val debugAbbrevOffset: T, override val addressSize: UByte) : DebugInformationEntry(offset, size, Tag.DW_TAG_compile_unit), DwarfUnit {
    private val children_ = mutableListOf<DebugInformationEntry>()
    override val children: List<DebugInformationEntry> = children_

    internal fun add(child: DebugInformationEntry) {
        children_.add(child)
    }

    sealed class Dwarf32Header(offset: Int, size: Int, next: Int, version: Int, debugAbbrevOffset: UInt, addressSize: UByte) : CompilationUnitHeader<UInt>(offset, size, next, version, debugAbbrevOffset, addressSize) {
        override val offsetSize: UByte = 4.toUByte()

        class V4(offset: Int, size: Int, next: Int, debugAbbrevOffset: UInt, addressSize: UByte) : Dwarf32Header(offset, size, next,4, debugAbbrevOffset, addressSize)
        sealed class V5(
            offset: Int,
            size: Int,
            next: Int,
            debugAbbrevOffset: UInt,
            addressSize: UByte,
            val type: UnitType,
        ) : Dwarf32Header(offset, size, next, 5, debugAbbrevOffset, addressSize) {
            class CompileUnit(offset: Int, size: Int, next: Int, debugAbbrevOffset: UInt, addressSize: UByte, type: UnitType) : V5(offset, size, next, debugAbbrevOffset, addressSize, type)
            class SkeletonUnit(offset: Int, size: Int, next: Int, debugAbbrevOffset: UInt, addressSize: UByte, type: UnitType, val dwoId: ULong) : V5(offset, size, next, debugAbbrevOffset, addressSize, type)
            class TypeUnit(offset: Int, size: Int, next: Int, debugAbbrevOffset: UInt, addressSize: UByte, type: UnitType, val typeSignature: ULong, val typeOffset: UInt) : V5(offset, size, next, debugAbbrevOffset, addressSize, type)
        }
    }
    sealed class Dwarf64Header(offset: Int, size: Int, next: Int, version: Int, debugAbbrevOffset: ULong, addressSize: UByte) : CompilationUnitHeader<ULong>(offset, size, next, version, debugAbbrevOffset, addressSize){
        override val offsetSize: UByte = 8.toUByte()

        class V4(offset: Int, size: Int, next: Int, debugAbbrevOffset: ULong, addressSize: UByte) : Dwarf64Header(offset, size, next, 4, debugAbbrevOffset, addressSize)
        sealed class V5(
            offset: Int,
            size: Int,
            next: Int,
            debugAbbrevOffset: ULong,
            addressSize: UByte,
            val type: UnitType,
        ) : Dwarf64Header(offset, size, next, 5,debugAbbrevOffset, addressSize) {
            class CompileUnit(offset: Int, size: Int, next: Int, debugAbbrevOffset: ULong, addressSize: UByte, type: UnitType) : V5(offset, size, next, debugAbbrevOffset, addressSize, type)
            class SkeletonUnit(offset: Int, size: Int, next: Int, debugAbbrevOffset: ULong, addressSize: UByte, type: UnitType, val dwoId: ULong) : V5(offset, size, next, debugAbbrevOffset, addressSize, type)
            class TypeUnit(offset: Int, size: Int, next: Int, debugAbbrevOffset: ULong, addressSize: UByte, type: UnitType, val typeSignature: ULong, val typeOffset: ULong) : V5(offset, size, next, debugAbbrevOffset, addressSize, type)
        }
    }
}

