package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.customSectionByName

class DwarfInfoSection(
    contentOffset: Int,
    contentSize: Int,
    private val debugAbbrevSection: DwarfAbbrevSection,
    val debugStringSection: DwarfStrSection
): DwarfSection(contentOffset, contentSize) {

    private var abbrevCache: Map<UInt, List<DwarfAbbrevEntry>>? = null

    private fun CommonByteBuffer.getAbbrevTables(): Map<UInt, List<DwarfAbbrevEntry>> {
        return abbrevCache ?: with(debugAbbrevSection) {
            crossSectionOperation {
                scanDwarfAbbrev()
            }
        }.also { abbrevCache = it }
    }

    private var stringCache: List<StringTableEntry>? = null
    fun CommonByteBuffer.getStringTable(): List<StringTableEntry> {
        return stringCache ?: with(debugStringSection) {
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
        return when(val value = readUInt()) {
            0xFFFFFFFFu -> readDwarf64Header(offset)
            else -> readDwarf32Header(offset, value)
        }
    }

    private fun CommonByteBuffer.readDwarf64Header(offset: Int): CompilationUnitHeader<*> {
        val size = readULong()
        val version = readUShort()
        return when (version.toInt()) {
            1,2,3,4 -> readDwarf64v4Header(offset, size)
            5 -> readDwarf64v5Header(offset, size)
            else -> throw IllegalArgumentException("Unsupported DWARF version: $version")
        }
    }

    private fun CommonByteBuffer.readDwarf64v4Header( offset: Int, size: ULong ): CompilationUnitHeader<*> {
        val debugAbbrevOffset = readULong()
        val addressSize = readUByte()
        return CompilationUnitHeader.Dwarf64Header.V4(offset, size.toInt(), size.toInt() + 12, debugAbbrevOffset, addressSize)
    }

    private fun CommonByteBuffer.readDwarf64v5Header( offset: Int, size: ULong ): CompilationUnitHeader<*> {
        val unitType = readUByte().toUnitType()
        val addressSize = readUByte()
        val debugAbbrevOffset = readULong()
        val next = size.toInt() + 12
        return when (unitType) {
            UnitType.DW_UT_compile, UnitType.DW_UT_partial -> CompilationUnitHeader.Dwarf64Header.V5.CompileUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType)
            UnitType.DW_UT_skeleton, UnitType.DW_UT_split_compile -> CompilationUnitHeader.Dwarf64Header.V5.SkeletonUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType, readULong())
            UnitType.DW_UT_type, UnitType.DW_UT_split_type -> CompilationUnitHeader.Dwarf64Header.V5.TypeUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType, readULong(), readULong())
            else -> CompilationUnitHeader.Dwarf64Header.V5.CompileUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType)
        }

    }

    private fun CommonByteBuffer.readDwarf32Header( offset: Int, size: UInt ): CompilationUnitHeader<*> {
        val version = readUShort()
        return when (version.toInt()) {
            1,2,3,4 -> readDwarf32v4Header(offset, size)
            5 -> readDwarf32v5Header(offset, size)
            else -> throw IllegalArgumentException("Unsupported DWARF version: $version")
        }
    }

    private fun CommonByteBuffer.readDwarf32v4Header(offset: Int, size: UInt): CompilationUnitHeader<*> {
        val debugAbbrevOffset = readUInt()
        val addressSize = readUByte()
        return CompilationUnitHeader.Dwarf32Header.V4(offset, size.toInt(),
            size.toInt() + 4, debugAbbrevOffset, addressSize)
    }

    private fun CommonByteBuffer.readDwarf32v5Header(offset: Int, size: UInt): CompilationUnitHeader<*> {
        val unitType = readUByte().toUnitType()
        val addressSize = readUByte()
        val debugAbbrevOffset = readUInt()
        val next = size.toInt() + 4
        return when (unitType) {
            UnitType.DW_UT_compile, UnitType.DW_UT_partial -> CompilationUnitHeader.Dwarf32Header.V5.CompileUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType)
            UnitType.DW_UT_skeleton, UnitType.DW_UT_split_compile -> CompilationUnitHeader.Dwarf32Header.V5.SkeletonUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType, readULong())
            UnitType.DW_UT_type, UnitType.DW_UT_split_type -> CompilationUnitHeader.Dwarf32Header.V5.TypeUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType, readULong(), readUInt())
            else -> CompilationUnitHeader.Dwarf32Header.V5.CompileUnit(offset, size.toInt(),
                next, debugAbbrevOffset, addressSize, unitType)
        }
    }

    fun CommonByteBuffer.readAttribute(header: CompilationUnitHeader<*>, attribute: DwarfAttribute): DieField {
       return when (attribute.form) {
           Form.DW_FORM_flag, Form.DW_FORM_ref1, Form.DW_FORM_data1, Form.DW_FORM_strx1 -> DieField(attribute, readUByte())
           Form.DW_FORM_ref2, Form.DW_FORM_data2, Form.DW_FORM_strx2 -> DieField(attribute, readUShort())
           Form.DW_FORM_ref4, Form.DW_FORM_data4, Form.DW_FORM_strx4 -> DieField(attribute, readUInt())
           Form.DW_FORM_ref8, Form.DW_FORM_data8, Form.DW_FORM_strx8 -> DieField(attribute, readULong())
           Form.DW_FORM_strp, Form.DW_FORM_sec_offset, Form.DW_FORM_ref_addr -> when (header) {
               is CompilationUnitHeader.Dwarf32Header -> DieField(attribute, readUInt())
               is CompilationUnitHeader.Dwarf64Header -> DieField(attribute ,readULong())
           }
           Form.DW_FORM_addr -> when (header.addressSize) {
               4.toUByte() -> DieField(attribute, readUInt())
               8.toUByte() -> DieField(attribute, readULong())
               else -> throw IllegalArgumentException("Unsupported address size: ${header.addressSize}")
           }
           Form.DW_FORM_flag_present -> DieField(attribute, true)
           Form.DW_FORM_string -> {
               DieField(attribute, readStringZ())
           }
           Form.DW_FORM_exprloc -> {
               val size = getULEB128()
               val buffer = ByteArray(size.toInt())
               get(buffer, 0, size.toInt())
               DieField(attribute, buffer)
           }
           Form.DW_FORM_udata,
           Form.DW_FORM_sdata -> {
               DieField(attribute, getULEB128())
           }
           Form.DW_FORM_block1 -> {
               val length = readUByte().toInt()
               val buffer = ByteArray(length)
               get(buffer, 0, length)
               DieField(attribute, buffer)
           }
           Form.DW_FORM_block2 -> {
               val length = readUShort().toInt()
               val buffer = ByteArray(length)
               get(buffer, 0, length)
               DieField(attribute, buffer)
           }
           Form.DW_FORM_block4 -> {
               val length = readUInt().toInt()
               val buffer = ByteArray(length)
               get(buffer, 0, length)
               DieField(attribute, buffer)
           }
           Form.DW_FORM_block -> {
               val length = getULEB128().toInt()
               val buffer = ByteArray(length)
               get(buffer, 0, length)
               DieField(attribute, buffer)
           }
           else -> TODO("Unsupported form: ${attribute.form}")
       }
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

class DieField(val attribute: DwarfAttribute, val value: Any)

fun BaseModule.debugInfoSection() = customSectionByName(".debug_info")?.let {
    DwarfInfoSection(
        it.contentOffset,
        it.contentSize,
        debugAbbrevSection() ?: throw IllegalStateException("No debug_abbrev section"),
        debugStrSection() ?: throw IllegalArgumentException("No debug_str section"))
}

open class DebugInformationEntry(offset: Int, size: Int, val tag: Tag, val attributes: List<DieField> = listOf(), open val children: List<DebugInformationEntry> = listOf()):DwarfEntry(offset, size)

sealed class CompilationUnitHeader<T>(offset: Int, size: Int, val next: Int, val version: Int, val debugAbbrevOffset: T, val addressSize: UByte) : DebugInformationEntry(offset, size, Tag.DW_TAG_compile_unit) {
    private val children_ = mutableListOf<DebugInformationEntry>()
    override val children: List<DebugInformationEntry> = children_

    internal fun add(child: DebugInformationEntry) {
        children_.add(child)
    }

    sealed class Dwarf32Header(offset: Int, size: Int, next: Int, version: Int, debugAbbrevOffset: UInt, addressSize: UByte) : CompilationUnitHeader<UInt>(offset, size, next, version, debugAbbrevOffset, addressSize) {
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

fun CommonByteBuffer.readUByte(): UByte {
    return get().toUByte()
}

fun CommonByteBuffer.readShort(): Short {
    return ByteArray(2).let {
            get(it, 0, 2)
            it[1].toUByte().toInt().shl(8).or(it[0].toUByte().toInt())
    }.toShort()
}

fun CommonByteBuffer.readUShort(): UShort = readShort().toUShort()

fun CommonByteBuffer.readUInt(): UInt {
    return ByteArray(4).let {
        get(it, 0, 4)
        var value = 0U
        for (i in 0 until 4) {
            value = value.or(it[i].toUByte().toUInt().shl(i * 8))
        }
        value
    }
}

fun CommonByteBuffer.readULong(): ULong {
    return ByteArray(8).let {
        get(it, 0, 8)
        var value = 0UL
        for (i in 0 until 8) {
            value = value.or(it[i].toUByte().toULong().shl(i * 8))
        }
        value
    }
}