package org.jetbrains.hexana.providers.macho

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeParseFailure
import org.jetbrains.hexana.parsing.common.NativeParseSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MachOParserTest {
    @Test
    fun `parses universal Mach-O header and architecture table`() {
        val bytes = fatMachOBytes().toCommonByteBuffer()
        val header = MachOParser.parseFatHeader(bytes)!!
        val architectures = MachOParser.parseFatArchitectures(bytes, header)!!

        assertEquals(NativeBinaryWordSize.Bits32, header.wordSize)
        assertEquals(NativeBinaryEndian.Big, header.endian)
        assertEquals(2u, header.archCount)
        assertEquals(8, header.archTableRange.offset)
        assertEquals(40, header.archTableRange.size)

        assertEquals(2, architectures.size)
        assertEquals(0x01000007, architectures[0].cpuType)
        assertEquals(3, architectures[0].cpuSubtype)
        assertEquals(0x1000uL, architectures[0].offset)
        assertEquals(0x200uL, architectures[0].size)
        assertEquals(12u, architectures[0].alignment)
        assertEquals(0x1000, architectures[0].sliceRange?.offset)
        assertEquals(0x200, architectures[0].sliceRange?.size)

        assertEquals(0x0100000C, architectures[1].cpuType)
        assertEquals(2, architectures[1].cpuSubtype)
        assertEquals(0x2000uL, architectures[1].offset)
        assertEquals(0x300uL, architectures[1].size)
        assertEquals(14u, architectures[1].alignment)
        assertEquals(0x2000, architectures[1].sliceRange?.offset)
        assertEquals(0x300, architectures[1].sliceRange?.size)
    }

    @Test
    fun `returns null when universal Mach-O slice points outside file`() {
        val bytes = fatMachOBytes()
        bytes.putU32Be(40, 0x1000u)
        val header = MachOParser.parseFatHeader(bytes.toCommonByteBuffer())!!

        assertNull(MachOParser.parseFatArchitectures(bytes.toCommonByteBuffer(), header))
    }

    @Test
    fun `returns null when universal Mach-O slices overlap`() {
        val bytes = fatMachOBytes()
        bytes.putU32Be(36, 0x1100u)
        val header = MachOParser.parseFatHeader(bytes.toCommonByteBuffer())!!

        assertNull(MachOParser.parseFatArchitectures(bytes.toCommonByteBuffer(), header))
    }

    @Test
    fun `parses Mach-O 64 little endian header`() {
        val header = MachOParser.parseHeader(machO64LeBytes().toCommonByteBuffer())!!

        assertEquals(NativeBinaryWordSize.Bits64, header.wordSize)
        assertEquals(NativeBinaryEndian.Little, header.endian)
        assertEquals(0xFEEDFACFu, header.magic)
        assertEquals(0x0100000C, header.cpuType)
        assertEquals(3, header.cpuSubtype)
        assertEquals(2u, header.fileType)
        assertEquals(5u, header.loadCommandCount)
        assertEquals(336u, header.loadCommandsSize)
        assertEquals(32, header.headerRange.size)
        assertEquals(32, header.loadCommandsRange.offset)
        assertEquals(336, header.loadCommandsRange.size)
    }

    @Test
    fun `parses thin Mach-O file model`() {
        val result = assertIs<NativeParseSuccess<MachOFile>>(MachOParser.parse(machO64LeBytes().toCommonByteBuffer()))
        val file = assertIs<MachOThinFile>(result.value)

        assertEquals(5, file.loadCommands.size)
        assertEquals(1, file.segments.size)
        assertEquals("01234567-89AB-CDEF-1032-547698BADCFE", file.uuid?.value)
        assertEquals(3, file.symbols.size)
        assertEquals(1, file.dylibs.size)
        assertEquals(emptyList(), file.unresolvedRecords)
    }

    @Test
    fun `complete Mach-O parser reports invalid header failure`() {
        val result = assertIs<NativeParseFailure>(MachOParser.parse(byteArrayOf(1, 2, 3).toCommonByteBuffer()))

        assertEquals("macho.invalid_header", result.diagnostics.single().code)
    }

    @Test
    fun `parses load commands segments sections and uuid`() {
        val bytes = machO64LeBytes().toCommonByteBuffer()
        val header = MachOParser.parseHeader(bytes)!!
        val commands = MachOParser.parseLoadCommands(bytes, header)!!
        val segments = MachOParser.parseSegments(bytes, header, commands)!!
        val uuid = MachOParser.parseUuid(bytes, commands)!!

        assertEquals(5, commands.size)
        assertEquals(LC_SEGMENT_64, commands[0].type)
        assertEquals(152u, commands[0].size)
        assertEquals(LC_UUID, commands[1].type)
        assertEquals(24u, commands[1].size)

        assertEquals("01234567-89AB-CDEF-1032-547698BADCFE", uuid.value)
        assertEquals(184, uuid.commandRange.offset)
        assertEquals(192, uuid.uuidRange.offset)

        val segment = segments.single()
        assertEquals("__TEXT", segment.name)
        assertEquals(0x100000000uL, segment.virtualAddress)
        assertEquals(0x1000uL, segment.virtualSize)
        assertEquals(0uL, segment.fileOffset)
        assertEquals(0x1000uL, segment.fileSize)
        assertEquals(1u, segment.sectionCount)
        assertEquals(0, segment.fileRange?.offset)
        assertEquals(0x1000, segment.fileRange?.size)

        val section = segment.sections.single()
        assertEquals("__text", section.name)
        assertEquals("__TEXT", section.segmentName)
        assertEquals(0x100000F50uL, section.address)
        assertEquals(0x30uL, section.size)
        assertEquals(0xF50uL, section.fileOffset)
        assertEquals(0xF50, section.contentRange?.offset)
        assertEquals(0x30, section.contentRange?.size)
    }

    @Test
    fun `parses symbol table dynamic symbol table and dylib load commands`() {
        val bytes = machO64LeBytes().toCommonByteBuffer()
        val header = MachOParser.parseHeader(bytes)!!
        val commands = MachOParser.parseLoadCommands(bytes, header)!!
        val symtab = MachOParser.parseSymtab(bytes, header, commands)!!
        val dysymtab = MachOParser.parseDysymtab(bytes, header, commands)!!
        val symbols = MachOParser.parseSymbols(bytes, header, symtab)!!
        val dylibs = MachOParser.parseDylibs(bytes, header, commands)!!

        assertEquals(5, commands.size)
        assertEquals(LC_SYMTAB, commands[2].type)
        assertEquals(LC_DYSYMTAB, commands[3].type)
        assertEquals(LC_LOAD_DYLIB, commands[4].type)

        assertEquals(0x400u, symtab.symbolTableOffset)
        assertEquals(3u, symtab.symbolCount)
        assertEquals(0x500u, symtab.stringTableOffset)
        assertEquals(0x40u, symtab.stringTableSize)
        assertEquals(0x400, symtab.symbolTableRange?.offset)
        assertEquals(48, symtab.symbolTableRange?.size)
        assertEquals(0x500, symtab.stringTableRange?.offset)
        assertEquals(0x40, symtab.stringTableRange?.size)

        assertEquals(0u, dysymtab.localSymbolIndex)
        assertEquals(1u, dysymtab.localSymbolCount)
        assertEquals(1u, dysymtab.externalDefinedSymbolIndex)
        assertEquals(1u, dysymtab.externalDefinedSymbolCount)
        assertEquals(2u, dysymtab.undefinedSymbolIndex)
        assertEquals(1u, dysymtab.undefinedSymbolCount)
        assertEquals(0x700u, dysymtab.indirectSymbolTableOffset)
        assertEquals(4u, dysymtab.indirectSymbolTableCount)

        assertEquals(3, symbols.size)
        assertEquals("", symbols[0].name)
        assertEquals("_main", symbols[1].name)
        assertEquals(0x0F, symbols[1].type)
        assertEquals(1, symbols[1].sectionIndex)
        assertEquals(0x100000F50uL, symbols[1].value)
        assertEquals("_puts", symbols[2].name)
        assertEquals(0x01, symbols[2].type)
        assertEquals(0uL, symbols[2].value)

        assertEquals(1, dylibs.size)
        assertEquals("/usr/lib/libSystem.B.dylib", dylibs.single().name)
        assertEquals(LC_LOAD_DYLIB, dylibs.single().type)
        assertEquals(336, dylibs.single().nameRange?.offset)
    }

    @Test
    fun `parses section relocation entries`() {
        val bytes = machO64LeBytes().toCommonByteBuffer()
        val header = MachOParser.parseHeader(bytes)!!
        val commands = MachOParser.parseLoadCommands(bytes, header)!!
        val segments = MachOParser.parseSegments(bytes, header, commands)!!
        val symtab = MachOParser.parseSymtab(bytes, header, commands)!!
        val symbols = MachOParser.parseSymbols(bytes, header, symtab)!!
        val relocations = MachOParser.parseRelocations(bytes, header, segments.flatMap { it.sections }, symbols)!!

        assertEquals(2, relocations.size)

        val externRelocation = relocations[0]
        assertEquals("__text", externRelocation.sectionName)
        assertEquals(0x10u, externRelocation.address)
        assertEquals(2u, externRelocation.symbolOrSectionIndex)
        assertEquals(true, externRelocation.pcrel)
        assertEquals(2, externRelocation.length)
        assertEquals(true, externRelocation.external)
        assertEquals(2, externRelocation.type)
        assertEquals(false, externRelocation.scattered)
        assertEquals("_puts", externRelocation.symbol?.name)
        assertEquals(0x600, externRelocation.recordRange.offset)

        val sectionRelocation = relocations[1]
        assertEquals(0x20u, sectionRelocation.address)
        assertEquals(1u, sectionRelocation.symbolOrSectionIndex)
        assertEquals(false, sectionRelocation.pcrel)
        assertEquals(3, sectionRelocation.length)
        assertEquals(false, sectionRelocation.external)
        assertEquals(0, sectionRelocation.type)
        assertNull(sectionRelocation.symbol)
        assertEquals(0x608, sectionRelocation.recordRange.offset)
    }

    @Test
    fun `returns null when load command range points outside file`() {
        val bytes = machO64LeBytes().copyOf(64)

        assertNull(MachOParser.parseHeader(bytes.toCommonByteBuffer()))
    }

    @Test
    fun `returns null when load commands leave trailing bytes`() {
        val bytes = machO64LeBytes()
        bytes.putU32Le(20, 340u)
        val buffer = bytes.toCommonByteBuffer()
        val header = MachOParser.parseHeader(buffer)!!

        assertNull(MachOParser.parseLoadCommands(buffer, header))
    }

    private fun machO64LeBytes(): ByteArray {
        val bytes = ByteArray(0x1200)
        bytes.putU32Le(0, 0xFEEDFACFu)
        bytes.putU32Le(4, 0x0100000Cu)
        bytes.putU32Le(8, 3u)
        bytes.putU32Le(12, 2u)
        bytes.putU32Le(16, 5u)
        bytes.putU32Le(20, 336u)
        bytes.putU32Le(24, 0x200085u)
        bytes.putU32Le(28, 0u)

        bytes.putU32Le(32, LC_SEGMENT_64)
        bytes.putU32Le(36, 152u)
        bytes.putFixedString(40, "__TEXT", 16)
        bytes.putU64Le(56, 0x100000000uL)
        bytes.putU64Le(64, 0x1000uL)
        bytes.putU64Le(72, 0uL)
        bytes.putU64Le(80, 0x1000uL)
        bytes.putU32Le(88, 7u)
        bytes.putU32Le(92, 5u)
        bytes.putU32Le(96, 1u)
        bytes.putU32Le(100, 0u)

        bytes.putFixedString(104, "__text", 16)
        bytes.putFixedString(120, "__TEXT", 16)
        bytes.putU64Le(136, 0x100000F50uL)
        bytes.putU64Le(144, 0x30uL)
        bytes.putU32Le(152, 0xF50u)
        bytes.putU32Le(156, 4u)
        bytes.putU32Le(160, 0x600u)
        bytes.putU32Le(164, 2u)
        bytes.putU32Le(168, 0x80000400u)

        bytes.putU32Le(184, LC_UUID)
        bytes.putU32Le(188, 24u)
        byteArrayOf(
            0x01, 0x23, 0x45, 0x67,
            0x89.toByte(), 0xAB.toByte(),
            0xCD.toByte(), 0xEF.toByte(),
            0x10, 0x32,
            0x54, 0x76, 0x98.toByte(), 0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(),
        ).copyInto(bytes, destinationOffset = 192)

        bytes.putU32Le(208, LC_SYMTAB)
        bytes.putU32Le(212, 24u)
        bytes.putU32Le(216, 0x400u)
        bytes.putU32Le(220, 3u)
        bytes.putU32Le(224, 0x500u)
        bytes.putU32Le(228, 0x40u)

        bytes.putU32Le(232, LC_DYSYMTAB)
        bytes.putU32Le(236, 80u)
        bytes.putU32Le(240, 0u)
        bytes.putU32Le(244, 1u)
        bytes.putU32Le(248, 1u)
        bytes.putU32Le(252, 1u)
        bytes.putU32Le(256, 2u)
        bytes.putU32Le(260, 1u)
        bytes.putU32Le(288, 0x700u)
        bytes.putU32Le(292, 4u)

        bytes.putU32Le(312, LC_LOAD_DYLIB)
        bytes.putU32Le(316, 56u)
        bytes.putU32Le(320, 24u)
        bytes.putU32Le(324, 0u)
        bytes.putU32Le(328, 0x00010000u)
        bytes.putU32Le(332, 0x00010000u)
        bytes.putNullTerminatedString(336, "/usr/lib/libSystem.B.dylib")

        bytes.putU32Le(0x400, 0u)
        bytes[0x404] = 0
        bytes[0x405] = 0
        bytes.putU16Le(0x406, 0u)
        bytes.putU64Le(0x408, 0u)

        bytes.putU32Le(0x410, 1u)
        bytes[0x414] = 0x0F
        bytes[0x415] = 1
        bytes.putU16Le(0x416, 0u)
        bytes.putU64Le(0x418, 0x100000F50uL)

        bytes.putU32Le(0x420, 7u)
        bytes[0x424] = 0x01
        bytes[0x425] = 0
        bytes.putU16Le(0x426, 0u)
        bytes.putU64Le(0x428, 0u)

        bytes[0x500] = 0
        bytes.putNullTerminatedString(0x501, "_main")
        bytes.putNullTerminatedString(0x507, "_puts")

        bytes.putU32Le(0x600, 0x10u)
        bytes.putU32Le(0x604, 0x2D000002u)
        bytes.putU32Le(0x608, 0x20u)
        bytes.putU32Le(0x60C, 0x06000001u)

        return bytes
    }

    private fun fatMachOBytes(): ByteArray {
        val bytes = ByteArray(0x2400)
        bytes.putU32Be(0, 0xCAFEBABEu)
        bytes.putU32Be(4, 2u)

        bytes.putU32Be(8, 0x01000007u)
        bytes.putU32Be(12, 3u)
        bytes.putU32Be(16, 0x1000u)
        bytes.putU32Be(20, 0x200u)
        bytes.putU32Be(24, 12u)

        bytes.putU32Be(28, 0x0100000Cu)
        bytes.putU32Be(32, 2u)
        bytes.putU32Be(36, 0x2000u)
        bytes.putU32Be(40, 0x300u)
        bytes.putU32Be(44, 14u)

        return bytes
    }
}

private fun ByteArray.putFixedString(offset: Int, value: String, size: Int) {
    val bytes = value.encodeToByteArray()
    bytes.copyInto(this, destinationOffset = offset, endIndex = bytes.size.coerceAtMost(size))
}

private fun ByteArray.putU32Le(offset: Int, value: UInt) {
    repeat(4) { index ->
        this[offset + index] = ((value shr (index * 8)) and 0xFFu).toByte()
    }
}

private fun ByteArray.putU16Le(offset: Int, value: UInt) {
    repeat(2) { index ->
        this[offset + index] = ((value shr (index * 8)) and 0xFFu).toByte()
    }
}

private fun ByteArray.putU32Be(offset: Int, value: UInt) {
    repeat(4) { index ->
        this[offset + index] = ((value shr ((3 - index) * 8)) and 0xFFu).toByte()
    }
}

private fun ByteArray.putU64Le(offset: Int, value: ULong) {
    repeat(8) { index ->
        this[offset + index] = ((value shr (index * 8)) and 0xFFu).toByte()
    }
}

private fun ByteArray.putNullTerminatedString(offset: Int, value: String) {
    val bytes = value.encodeToByteArray()
    bytes.copyInto(this, destinationOffset = offset)
    this[offset + bytes.size] = 0
}
