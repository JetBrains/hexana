@file:OptIn(ExperimentalUnsignedTypes::class)

package org.jetbrains.hexana.dwarf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation
import org.jetbrains.hexana.wasm.BaseModule
import org.jetbrains.hexana.wasm.customSectionByName

class DwarfLineSection(contentOffset: Int, contentSize: Int, accessor: DwarfAccessor) : DwarfSection(contentOffset, contentSize,
    accessor
) {
    fun CommonByteBuffer.scanLineTable(): List<DwarfLineUnitHeader<*>> {
        position(contentOffset)
        val units = mutableListOf<DwarfLineUnitHeader<*>>()
        while (position() < contentOffset + contentSize) {
            val unitHeader = readLineUnitHeader()
            units.add(unitHeader)
            val unitSize = when (unitHeader.unitSize) {
                is UInt -> unitHeader.unitSize.toInt()
                is ULong -> unitHeader.unitSize.toInt()
                else -> throw IllegalArgumentException("Unsupported DWARF line: ${unitHeader.unitSize}")
            }
            position(unitHeader.offset + unitSize)
        }
        return units
    }

    fun CommonByteBuffer.readLineUnitHeader(): DwarfLineUnitHeader<*> {
        val offset = position()
        return when (val unitSize = readUInt()) {
            0xFFFFFFFFu -> readDwarf64Header(offset)
            else -> readDwarf32Header(offset, unitSize)
        }
    }

    fun CommonByteBuffer.readDwarf64Header(unitOffset: Int): DwarfLineUnitHeader<*> {
        val size = readULong()
        val version = readUShort()
        return when (version.toInt()) {
            1, 2, 3, 4 -> readDwarfLine64V4Header(unitOffset, size)
            5 -> readDwarfLine64V5Header(unitOffset, size)
            else -> throw IllegalArgumentException("Unsupported DWARF version: $version")
        }
    }

    fun CommonByteBuffer.readDwarfLine64V4Header(unitOffset: Int, size: ULong): DwarfLineUnitHeader<*> {
        val headerLength = readULong()
        val maximumInstructionsLength = readUByte()
        val maximumOperationsPerInstruction = readUByte()
        val defaultIsStatement = readUByte()
        val lineBase = get()
        val lineRange = readUByte()
        val opcodeBase = readUByte()
        val standardOpcodeLengths = ByteArray(opcodeBase.toInt() - 1).also {
            get(it, 0, opcodeBase.toInt() - 1)
        }
        val directories = mutableListOf<String>()
        while (true) {
            val str = readStringZ()
            if (str.isEmpty())
                break
            directories.add(str)
        }
        val includeDirectories = directories.toList()

        val fileNames = mutableListOf<LineNumberFileEntryV4>()
        while (true) {
            val fileName = readStringZ()
            if (fileName.isEmpty())
                break
            fileNames.add(
                LineNumberFileEntryV4(
                    fileName,
                    getULEB128().toInt(),
                    getULEB128().toLong(),
                    getULEB128().toLong()
                )
            )
        }
        val includeFiles = fileNames.toList()
        /**
         *   DWARF64:
         *   unit_start + 0:   0xFFFFFFFF     (4 bytes, magic)
         *   unit_start + 4:   unit_length    (8 bytes)
         *   unit_start + 12:  version        (2 bytes)
         *   unit_start + 14:  header_length  (8 bytes)  ◄── offset_of(header_length) = unit_start + 14
         *   Program starts at: unit_start + 14 + 8 + header_length = unit_start + 22 + header_length
         */
        return DwarfLineUnitHeader.Dwarf64LineUnitHeader.V4(
            unitOffset,
            size,
            headerLength,
            maximumInstructionsLength,
            maximumOperationsPerInstruction,
            defaultIsStatement,
            lineBase,
            lineRange,
            standardOpcodeLengths,
            includeDirectories,
            includeFiles,
            headerLength + 22u,
            headerLength + 14u,
            accessor
        )
    }

    fun CommonByteBuffer.readDwarfLine64V5Header(unitOffset: Int, size1: ULong): DwarfLineUnitHeader<*> {
        TODO()
    }

    fun CommonByteBuffer.readDwarf32Header(unitOffset: Int, unitSize: UInt): DwarfLineUnitHeader<*> {
        val version = readUShort()
        return when (version.toInt()) {
            1, 2, 3, 4 -> readDwarfLine32V4Header(unitOffset, unitSize)
            5 -> readDwarfLine32V5Header(unitOffset, unitSize)
            else -> throw IllegalArgumentException("Unsupported DWARF version: $version")
        }
    }

    fun CommonByteBuffer.readDwarfLine32V4Header(unitOffset: Int, unitSize: UInt): DwarfLineUnitHeader<*> {
        val prologLength = readUInt()
        val maximumInstructionsLength = readUByte()
        val maximumOperationsPerInstruction = readUByte()
        val defaultIsStatement = readUByte()
        val lineBase = get()
        val lineRange = readUByte()
        val opcodeBase = readUByte()
        val standardOpcodeLengths = ByteArray(opcodeBase.toInt() - 1).also {
            get(it, 0, opcodeBase.toInt() - 1)
        }
        val directories = mutableListOf<String>()
        while (true) {
            val str = readStringZ()
            if (str.isEmpty())
                break
            directories.add(str)
        }
        val includeDirectories = directories.toList()

        val fileNames = mutableListOf<LineNumberFileEntryV4>()
        while (true) {
            val fileName = readStringZ()
            if (fileName.isEmpty())
                break
            fileNames.add(
                LineNumberFileEntryV4(
                    fileName,
                    getULEB128().toInt(),
                    getULEB128().toLong(),
                    getULEB128().toLong()
                )
            )
        }
        val includeFiles = fileNames.toList()
        /**
         *   DWARF32:
         *   unit_start + 0:  unit_length    (4 bytes)
         *   unit_start + 4:  version        (2 bytes)
         *   unit_start + 6:  header_length  (4 bytes)  ◄── offset_of(header_length) = unit_start + 6
         *   Program starts at: unit_start + 6 + 4 + header_length = unit_start + 10 + header_length
         */
        return DwarfLineUnitHeader.Dwarf32LineUnitHeader.V4(
            unitOffset,
            unitSize,
            prologLength,
            maximumInstructionsLength,
            maximumOperationsPerInstruction,
            defaultIsStatement,
            lineBase,
            lineRange,
            standardOpcodeLengths,
            includeDirectories,
            includeFiles,
            prologLength + 10u,
            unitSize + 4u,
            accessor
        )
    }

    class Entry(
        val formatName: LineNumberHeaderEntryFormatName,
        val form: Form,
        val value: Any
    )

    fun CommonByteBuffer.readDwarfLine32V5Header(unitOffset: Int, unitSize: UInt): DwarfLineUnitHeader<UInt> {
        val addressSize = readUByte()
        val segmentSelectorSize = readUByte()
        val prologLength = readUInt()
        val minimumInstructionLength = readUByte()
        val maximumOperationsPerInstruction = readUByte()
        val defaultIsStatement = readUByte()
        val lineBase = get()
        val lineRange = readUByte()
        val opcodeBase = readUByte()
        val unit = object : DwarfUnit {
            override val addressSize: UByte = addressSize
        }
        val standardOpcodeLengths = ByteArray(opcodeBase.toInt() - 1).also {
            get(it, 0, opcodeBase.toInt() - 1)
        }
        val directoryEntryFormatCount = readUByte()
        val directoryEntryFormats = List(directoryEntryFormatCount.toInt()) { DwarfEntryFormat(getULEB128().toULong(), getULEB128().toULong()) }
        val directoriesCount = getULEB128()
        val directories = List(directoriesCount.toInt()) {
            directoryEntryFormats.map {
                val form = findForm(it.form.toUShort()) ?: throw IllegalArgumentException("Unsupported form: ${it.form}")
                val formatName = findLineNumberHeaderEntryFormatName(it.contentType.toUShort()) ?: throw IllegalArgumentException("Unsupported format name: ${it.contentType}")
                Entry(formatName, form, readForm(form, unit))
            }
        }
        val fileNameEntryFormatCount = readUByte()
        val fileNameEntryFormats = List(fileNameEntryFormatCount.toInt()) { DwarfEntryFormat(getULEB128().toULong(), getULEB128().toULong()) }
        val fileNamesCount = getULEB128()
        val fileNames = List(fileNamesCount.toInt()) {
            fileNameEntryFormats.map {
                val form = findForm(it.form.toUShort()) ?: throw IllegalArgumentException("Unsupported form: ${it.form}")
                val formatName = findLineNumberHeaderEntryFormatName(it.contentType.toUShort()) ?: throw IllegalArgumentException("Unsupported format name: ${it.contentType}")
                Entry(formatName, form, readForm(form, unit))
            }
        }
        return DwarfLineUnitHeader.Dwarf32LineUnitHeader.V5(
            unitOffset,
            unitSize,
            prologLength.toUInt(),
            maximumOperationsPerInstruction,
            minimumInstructionLength,
            defaultIsStatement,
            prologLength + 10u,
            unitSize + 4u,
            lineRange,
            lineBase,
            standardOpcodeLengths,
            segmentSelectorSize,
            directoryEntryFormatCount,
            directoryEntryFormats,
            directoriesCount,
            directories,
            fileNameEntryFormatCount,
            fileNameEntryFormats,
            fileNamesCount,
            fileNames,
            accessor)
    }
}

class DwarfEntryFormat(val contentType:  ULong, val form: ULong)

class LineNumberFileEntryV4(val fileName: String, val dirIndex: Int, val lastModified: Long, val fileSize: Long)

sealed class DwarfLineUnitHeader<T>(
    contentOffset: Int,
    contentSize: Int,
    val prologLength: T,
    val maximumInstructionsLength: UByte,
    val maximumOperationsPerInstruction: UByte,
    val defaultIsStatement: UByte,
    val programOffset: T,
    val unitSize: T,
    val lineRange: UByte,
    val lineBase: Byte,
    val standardOpcodeLengths: ByteArray,
    accessor: DwarfAccessor
) : DwarfSection(contentOffset, contentSize.toInt(), accessor) {
    sealed class Dwarf32LineUnitHeader(
        contentOffset: Int,
        prologLength: UInt,
        maximumInstructionsLength: UByte,
        maximumOperationsPerInstruction: UByte,
        defaultIsStatement: UByte,
        contentSize: UInt,
        programOffset: UInt,
        unitSize: UInt,
        lineRange: UByte,
        lineBase: Byte,
        standardOpcodeLengths: ByteArray,
        accessor: DwarfAccessor
    ) :
        DwarfLineUnitHeader<UInt>(
            contentOffset,
            contentSize.toInt(),
            prologLength,
            maximumInstructionsLength,
            maximumOperationsPerInstruction,
            defaultIsStatement,
            programOffset,
            unitSize,
            lineRange,
            lineBase,
            standardOpcodeLengths,
            accessor
        ) {
        class V4(
            contentOffset: Int,
            contentSize: UInt,
            prologLength: UInt,
            maximumInstructionsLength: UByte,
            maximumOperationsPerInstruction: UByte,
            defaultIsStatement: UByte,
            lineBase: Byte,
            lineRange: UByte,
            standardOpcodeLengths: ByteArray,
            val includeDirectories: List<String>,
            val fileNames: List<LineNumberFileEntryV4>,
            programOffset: UInt,
            unitSize: UInt, accessor: DwarfAccessor
        ) : Dwarf32LineUnitHeader(
            contentOffset,
            prologLength,
            maximumInstructionsLength,
            maximumOperationsPerInstruction,
            defaultIsStatement,
            contentSize,
            programOffset,
            unitSize,
            lineRange,
            lineBase,
            standardOpcodeLengths,
            accessor
        )

        class V5(
            contentOffset: Int,
            contentSize: UInt,
            prologLength: UInt,
            maximumInstructionsLength: UByte,
            maximumOperationsPerInstruction: UByte,
            defaultIsStatement: UByte,
            programOffset: UInt,
            unitSize: UInt,
            lineRange: UByte,
            lineBase: Byte,
            standardOpcodeLengths: ByteArray,
            val segmentSelectorSize: UByte,
            val directoryEntryFormatCount: UByte,
            val directoryEntryFormats: List<DwarfEntryFormat>,
            val directoriesCount: Long,
            val directories: List<List<DwarfLineSection.Entry>>,
            val fileNameEntryFormatCount: UByte,
            val fileNameEntryFormats: List<DwarfEntryFormat>,
            val fileNamesCount: Long,
            val fileNames: List<List<DwarfLineSection.Entry>>, accessor: DwarfAccessor,
        ) : Dwarf32LineUnitHeader(
            contentOffset,
            prologLength,
            maximumInstructionsLength,
            maximumOperationsPerInstruction,
            defaultIsStatement,
            contentSize,
            programOffset,
            unitSize,
            lineRange,
            lineBase,
            standardOpcodeLengths,
            accessor
        )
    }

    sealed class Dwarf64LineUnitHeader(
        contentOffset: Int,
        contentSize: ULong,
        prologLength: ULong,
        maximumInstructionsLength: UByte,
        maximumOperationsPerInstruction: UByte,
        defaultIsStatement: UByte,
        lineBase: Byte,
        lineRange: UByte,
        standardOpcodeLengths: ByteArray,
        programOffset: ULong,
        unitSize: ULong, accessor: DwarfAccessor
    ) : DwarfLineUnitHeader<ULong>(
        contentOffset,
        contentSize.toInt(),
        prologLength,
        maximumInstructionsLength,
        maximumOperationsPerInstruction,
        defaultIsStatement,
        programOffset,
        unitSize,
        lineRange,
        lineBase,
        standardOpcodeLengths,
        accessor
    ) {

        class V4(
            contentOffset: Int,
            contentSize: ULong,
            prologLength: ULong,
            maximumInstructionsLength: UByte,
            maximumOperationsPerInstruction: UByte,
            defaultIsStatement: UByte,
            lineBase: Byte,
            lineRange: UByte,
            standardOpcodeLengths: ByteArray,
            val includeDirectories: List<String>,
            val includeFiles: List<LineNumberFileEntryV4>,
            programOffset: ULong,
            unitSize: ULong, accessor: DwarfAccessor
        ) : Dwarf64LineUnitHeader(
            contentOffset,
            contentSize,
            prologLength,
            maximumInstructionsLength,
            maximumOperationsPerInstruction,
            defaultIsStatement,
            lineBase,
            lineRange,
            standardOpcodeLengths,
            programOffset,
            unitSize,
            accessor
        )

        class V5(
            contentOffset: Int,
            contentSize: ULong,
            prologLength: ULong,
            maximumInstructionsLength: UByte,
            maximumOperationsPerInstruction: UByte,
            defaultIsStatement: UByte,
            lineBase: Byte,
            lineRange: UByte,
            standardOpcodeLengths: ByteArray,
            programOffset: ULong,
            unitSize: ULong, accessor: DwarfAccessor,
        ) : Dwarf64LineUnitHeader(
            contentOffset,
            contentSize,
            prologLength,
            maximumInstructionsLength,
            maximumOperationsPerInstruction,
            defaultIsStatement,
            lineBase,
            lineRange,
            standardOpcodeLengths,
            programOffset,
            unitSize,
            accessor
        )

    }

    fun CommonByteBuffer.standardArgumentsLoading(
        lengthsEncoding: Int
    ): ULongArray {
        val numberOfArguments = standardOpcodeLengths[lengthsEncoding]
        val arguments = (0 until numberOfArguments).map {
            getULEB128().toULong()
        }
        return arguments.toULongArray()
    }

    fun CommonByteBuffer.lineProgram(): List<LineProgramOpcode> {
        val result = mutableListOf<LineProgramOpcode>()
        crossSectionOperation {
            val programOffset = when (programOffset) {
                is UInt -> programOffset.toInt()
                is ULong -> programOffset.toInt()
                else -> throw IllegalArgumentException("Unsupported program offset type: programOffset")
            }
            val unitSize = when (unitSize) {
                is UInt -> unitSize.toInt()
                is ULong -> unitSize.toInt()
                else -> throw IllegalArgumentException("Unsupported unit size type: unitSize")
            }
            position(contentOffset + programOffset)
            while (position() < contentOffset + unitSize) {
                val standardOpcodeOffset = position()
                val opEncoding = readUByte()
                val op = when (opEncoding) {
                    0x0.toUByte() -> {
                        val extendedLength = getULEB128().toInt()
                        val extendedOffset = position()
                        val extendedOpCode = readUByte()
                        when (extendedOpCode) {
                            0x01.toUByte() -> {
                                LneOpcode.DW_LNE_end_sequence(extendedOffset, extendedLength, ULongArray(0))
                            }

                            0x02.toUByte() -> {
                                val address = readUInt().toULong()
                                LneOpcode.DW_LNE_set_address(
                                    extendedOffset, extendedLength,
                                    arrayOf(address).toULongArray()
                                )
                            }

                            0x03.toUByte() -> {
                                LneOpcode.DW_LNE_define_file(
                                    extendedOffset, extendedLength,
                                    extendedArgumentsLoading(extendedOffset, extendedLength).toULongArray()
                                )
                            }

                            0x04.toUByte() -> {
                                LneOpcode.DW_LNE_set_descriminanator(
                                    extendedOffset, extendedLength,
                                    extendedArgumentsLoading(extendedOffset, extendedLength).toULongArray()
                                )
                            }

                            in 0x05.toUByte()..0xff.toUByte() -> {
                                LneOpcode.DW_LNE_unknown(
                                    opEncoding, extendedOffset, extendedLength,
                                    extendedArgumentsLoading(extendedOffset, extendedLength).toULongArray()
                                )
                            }

                            else -> {
                                LneOpcode.DW_LNE_unknown(
                                    opEncoding, extendedOffset, extendedLength,
                                    extendedArgumentsLoading(extendedOffset, extendedLength).toULongArray()
                                )
                            }
                        }
                    }

                    LnsOpcode.DW_LNS_copy.std -> {

                        val params = standardArgumentsLoading(LnsOpcode.DW_LNS_copy.standardOpcodeLengthsEncoding)
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_copy(standardOpcodeOffset, size, params)
                    }

                    LnsOpcode.DW_LNS_advance_pc.std -> {
                        val params =
                            standardArgumentsLoading(LnsOpcode.DW_LNS_advance_pc.standardOpcodeLengthsEncoding)
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_advance_pc(standardOpcodeOffset, size, params)
                    }

                    LnsOpcode.DW_LNS_advance_line.std -> {
                        val params =
                            standardArgumentsLoading(LnsOpcode.DW_LNS_advance_line.standardOpcodeLengthsEncoding)
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_advance_line(standardOpcodeOffset, size, params)
                    }

                    LnsOpcode.DW_LNS_set_file.std -> {
                        val params =
                            standardArgumentsLoading(LnsOpcode.DW_LNS_set_file.standardOpcodeLengthsEncoding)
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_set_file(standardOpcodeOffset, size, params)
                    }

                    LnsOpcode.DW_LNS_set_column.std -> {
                        val params =
                            standardArgumentsLoading(LnsOpcode.DW_LNS_set_column.standardOpcodeLengthsEncoding)
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_set_column(standardOpcodeOffset, size, params)
                    }

                    LnsOpcode.DW_LNS_negate_stmt.std -> {
                        val params =
                            standardArgumentsLoading(LnsOpcode.DW_LNS_negate_stmt.standardOpcodeLengthsEncoding)
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_negate_stmt(standardOpcodeOffset, size, params)
                    }

                    LnsOpcode.DW_LNS_set_basic_block.std -> {
                        val params =
                            standardArgumentsLoading(LnsOpcode.DW_LNS_set_basic_block.standardOpcodeLengthsEncoding)
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_set_basic_block(standardOpcodeOffset, size, params)
                    }

                    LnsOpcode.DW_LNS_const_add_pc.std -> {
                        val params =
                            standardArgumentsLoading(LnsOpcode.DW_LNS_const_add_pc.standardOpcodeLengthsEncoding)
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_const_add_pc(standardOpcodeOffset, size, params)
                    }

                    LnsOpcode.DW_LNS_fixed_advance_pc.std -> {
                        val param = readUShort().toULong()
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_fixed_advance_pc(
                            standardOpcodeOffset, size,
                            arrayOf(param).toULongArray()
                        )
                    }

                    LnsOpcode.DW_LNS_set_prologue_end.std -> {
                        val params =
                            standardArgumentsLoading(LnsOpcode.DW_LNS_set_prologue_end.standardOpcodeLengthsEncoding)
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_set_prologue_end(standardOpcodeOffset, size, params)
                    }

                    LnsOpcode.DW_LNS_set_epilogue_begin.std -> {
                        val params =
                            standardArgumentsLoading(LnsOpcode.DW_LNS_set_epilogue_begin.standardOpcodeLengthsEncoding)
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_set_epilogue_begin(standardOpcodeOffset, size, params)
                    }

                    LnsOpcode.DW_LNS_set_isa.std -> {
                        val params =
                            standardArgumentsLoading(LnsOpcode.DW_LNS_set_isa.standardOpcodeLengthsEncoding)
                        val size = position() - standardOpcodeOffset
                        LnsOpcode.DW_LNS_set_isa(standardOpcodeOffset, size, params)
                    }

                    else -> LnsSpecialOpcode(opEncoding, standardOpcodeOffset)
                }
                result.add(op)
            }
        }
        return result
    }

    private fun CommonByteBuffer.extendedArgumentsLoading(
        extendedOffset: Int,
        extendedLength: Int
    ): MutableList<ULong> {
        val params = mutableListOf<ULong>()
        while (position() < extendedOffset + extendedLength) {
            params.add(getULEB128().toULong())
        }
        position(extendedOffset + extendedLength)
        return params
    }
}

sealed class LineProgramOpcode(val offset: Int, val size: Int, val params: ULongArray)
sealed class LnsOpcode(val value: UByte, offset: Int, size: Int, params: ULongArray) : LineProgramOpcode(
    offset, size,
    params
) {
    class DW_LNS_copy(offset: Int, size: Int, params: ULongArray) : LnsOpcode(std, offset, size, params) {
        companion object {
            val std = 0x01.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1

        }
    }

    class DW_LNS_advance_pc(offset: Int, size: Int, params: ULongArray) : LnsOpcode(std, offset, size, params) {
        companion object {
            val std = 0x02.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1

        }
    }

    class DW_LNS_advance_line(offset: Int, size: Int, params: ULongArray) : LnsOpcode(std, offset, size, params) {
        companion object {
            val std = 0x03.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1

        }
    }

    class DW_LNS_set_file(offset: Int, size: Int, params: ULongArray) : LnsOpcode(std, offset, size, params) {
        companion object {
            val std = 0x04.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1

        }
    }

    class DW_LNS_set_column(offset: Int, size: Int, params: ULongArray) : LnsOpcode(std, offset, size, params) {
        companion object {
            val std = 0x05.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1

        }
    }

    class DW_LNS_negate_stmt(offset: Int, size: Int, params: ULongArray) : LnsOpcode(std, offset, size, params) {
        companion object {
            val std = 0x06.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1

        }
    }

    class DW_LNS_set_basic_block(offset: Int, size: Int, params: ULongArray) :
        LnsOpcode(std, offset, size, params) {
        companion object {
            val std = 0x07.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1

        }
    }

    class DW_LNS_const_add_pc(offset: Int, size: Int, params: ULongArray) : LnsOpcode(std, offset, size, params) {
        companion object {
            val std = 0x08.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1
        }
    }

    class DW_LNS_fixed_advance_pc(offset: Int, size: Int, params: ULongArray) :
        LnsOpcode(std, offset, size, params) {
        companion object {
            val std = 0x09.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1
        }
    }

    class DW_LNS_set_prologue_end(offset: Int, size: Int, params: ULongArray) :
        LnsOpcode(std, offset, size, params) {
        companion object {
            val std = 0x0A.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1
        }
    }

    class DW_LNS_set_epilogue_begin(offset: Int, size: Int, params: ULongArray) :
        LnsOpcode(std, offset, size, params) {
        companion object {
            val std = 0x0B.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1
        }
    }

    class DW_LNS_set_isa(offset: Int, size: Int, params: ULongArray) :
        LnsOpcode(0x0C.toUByte(), offset, size, params) {
        companion object {
            val std = 0x0C.toUByte()
            val standardOpcodeLengthsEncoding: Int
                get() = std.toInt() - 1
        }
    }
}

class LnsSpecialOpcode(value: UByte, offset: Int) : LnsOpcode(value, offset, 1, ULongArray(0))

sealed class LneOpcode(val value: UByte, offset: Int, size: Int, params: ULongArray) : LineProgramOpcode(
    offset, size,
    params
) {
    class DW_LNE_end_sequence(offset: Int, size: Int, params: ULongArray) : LneOpcode(std, offset, size, params) {
        companion object {
            val std = 0x01.toUByte()
        }
    }

    class DW_LNE_set_address(offset: Int, size: Int, params: ULongArray) : LneOpcode(std, offset, size, params) {
        companion object {
            val std = 0x02.toUByte()
        }
    }

    class DW_LNE_define_file(offset: Int, size: Int, params: ULongArray) : LneOpcode(std, offset, size, params) {
        companion object {
            val std = 0x03.toUByte()
        }
    }

    class DW_LNE_set_descriminanator(offset: Int, size: Int, params: ULongArray) :
        LneOpcode(std, offset, size, params) {
        companion object {
            val std = 0x04.toUByte()
        }
    }

    class DW_LNE_lo_user(offset: Int, size: Int, params: ULongArray) : LneOpcode(std, offset, size, params) {
        companion object {
            val std = 0x80.toUByte()
        }
    }

    class DW_LNE_hi_user(offset: Int, size: Int, params: ULongArray) : LneOpcode(std, offset, size, params) {
        companion object {
            val std = 0xff.toUByte()
        }
    }

    class DW_LNE_unknown(value: UByte, offset: Int, size: Int, params: ULongArray) :
        LneOpcode(value, offset, size, params)

    class DW_LNE_user(value: UByte, offset: Int, size: Int, params: ULongArray) :
        LneOpcode(value, offset, size, params)
}

sealed class LineNumberHeaderEntryFormatName(val value: UShort) {
    object DW_LNCT_path: LineNumberHeaderEntryFormatName(0x01.toUShort())
    object DW_LNCT_directory_index: LineNumberHeaderEntryFormatName(0x02.toUShort())
    object DW_LNCT_timestamp: LineNumberHeaderEntryFormatName(0x03.toUShort())
    object DW_LNCT_size: LineNumberHeaderEntryFormatName(0x04.toUShort())
    object DW_LNCT_MD5: LineNumberHeaderEntryFormatName(0x05.toUShort())
    object DW_LNCT_lo_user: LineNumberHeaderEntryFormatName(0x2000.toUShort())
    object DW_LNCT_hi_user: LineNumberHeaderEntryFormatName(0x3fff.toUShort())
    class DW_LNCT_user(value: UShort): LineNumberHeaderEntryFormatName(value)
}

fun findLineNumberHeaderEntryFormatName(value: UShort): LineNumberHeaderEntryFormatName? = when (value) {
    LineNumberHeaderEntryFormatName.DW_LNCT_path.value -> LineNumberHeaderEntryFormatName.DW_LNCT_path
    LineNumberHeaderEntryFormatName.DW_LNCT_directory_index.value -> LineNumberHeaderEntryFormatName.DW_LNCT_directory_index
    LineNumberHeaderEntryFormatName.DW_LNCT_timestamp.value -> LineNumberHeaderEntryFormatName.DW_LNCT_timestamp
    LineNumberHeaderEntryFormatName.DW_LNCT_size.value -> LineNumberHeaderEntryFormatName.DW_LNCT_size
    LineNumberHeaderEntryFormatName.DW_LNCT_MD5.value -> LineNumberHeaderEntryFormatName.DW_LNCT_MD5
    in LineNumberHeaderEntryFormatName.DW_LNCT_lo_user.value until
    LineNumberHeaderEntryFormatName.DW_LNCT_hi_user.value -> LineNumberHeaderEntryFormatName.DW_LNCT_user(value)
    else -> null
}


