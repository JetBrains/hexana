package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeParseFailure
import org.jetbrains.hexana.parsing.common.NativeParseSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ElfParserTest {
    @Test
    fun `parses ELF64 little endian header`() {
        val bytes = elf64LeHeader()

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!

        assertEquals(NativeBinaryWordSize.Bits64, header.wordSize)
        assertEquals(NativeBinaryEndian.Little, header.endian)
        assertEquals(0, header.osAbi)
        assertEquals(0, header.abiVersion)
        assertEquals(2, header.fileType)
        assertEquals(0x3E, header.machine)
        assertEquals(1u, header.version)
        assertEquals(0x401000uL, header.entryPoint)
        assertEquals(64uL, header.programHeaderOffset)
        assertEquals(0x200uL, header.sectionHeaderOffset)
        assertEquals(0u, header.flags)
        assertEquals(64, header.headerSize)
        assertEquals(56, header.programHeaderEntrySize)
        assertEquals(3, header.programHeaderCount)
        assertEquals(64, header.sectionHeaderEntrySize)
        assertEquals(5, header.sectionHeaderCount)
        assertEquals(4, header.sectionNameStringTableIndex)
        assertEquals(0, header.identRange.offset)
        assertEquals(16, header.identRange.size)
        assertEquals(0, header.headerRange.offset)
        assertEquals(64, header.headerRange.size)
        assertEquals(0x18, header.fieldRanges.entryPoint.offset)
        assertEquals(8, header.fieldRanges.entryPoint.size)
        assertEquals(0x3E, header.fieldRanges.sectionNameStringTableIndex.offset)
        assertEquals(2, header.fieldRanges.sectionNameStringTableIndex.size)
    }

    @Test
    fun `parses ELF32 big endian header`() {
        val bytes = ByteArray(52)
        bytes.writeElfIdent(wordSize = 1, endian = 2)
        bytes.putU16Be(0x10, 1)
        bytes.putU16Be(0x12, 0x28)
        bytes.putU32Be(0x14, 1u)
        bytes.putU32Be(0x18, 0x8000u)
        bytes.putU32Be(0x1C, 52u)
        bytes.putU32Be(0x20, 0x180u)
        bytes.putU32Be(0x24, 0u)
        bytes.putU16Be(0x28, 52)
        bytes.putU16Be(0x2A, 32)
        bytes.putU16Be(0x2C, 2)
        bytes.putU16Be(0x2E, 40)
        bytes.putU16Be(0x30, 6)
        bytes.putU16Be(0x32, 5)

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!

        assertEquals(NativeBinaryWordSize.Bits32, header.wordSize)
        assertEquals(NativeBinaryEndian.Big, header.endian)
        assertEquals(1, header.fileType)
        assertEquals(0x28, header.machine)
        assertEquals(0x8000uL, header.entryPoint)
        assertEquals(52uL, header.programHeaderOffset)
        assertEquals(0x180uL, header.sectionHeaderOffset)
        assertEquals(52, header.headerSize)
        assertEquals(0x18, header.fieldRanges.entryPoint.offset)
        assertEquals(4, header.fieldRanges.entryPoint.size)
        assertEquals(0x32, header.fieldRanges.sectionNameStringTableIndex.offset)
        assertEquals(2, header.fieldRanges.sectionNameStringTableIndex.size)
    }

    @Test
    fun `returns null for non ELF input`() {
        assertNull(ElfParser.parseHeader(byteArrayOf(1, 2, 3, 4).toCommonByteBuffer()))
    }

    @Test
    fun `returns null for truncated ELF input`() {
        val bytes = ByteArray(16)
        bytes.writeElfIdent(wordSize = 2, endian = 1)

        assertNull(ElfParser.parseHeader(bytes.toCommonByteBuffer()))
    }

    @Test
    fun `returns null when ELF header size points outside file`() {
        val bytes = elf64LeHeader()
        bytes.putU16Le(0x34, 128)

        assertNull(ElfParser.parseHeader(bytes.toCommonByteBuffer()))
    }

    @Test
    fun `returns null for unsupported ELF class`() {
        val bytes = ByteArray(64)
        bytes.writeElfIdent(wordSize = 9, endian = 1)

        assertNull(ElfParser.parseHeader(bytes.toCommonByteBuffer()))
    }

    @Test
    fun `does not move input buffer cursor`() {
        val buffer = elf64LeHeader().toCommonByteBuffer()
        buffer.position(9)

        ElfParser.parseHeader(buffer)

        assertEquals(9, buffer.position())
    }

    @Test
    fun `parses complete ELF file model`() {
        val bytes = elf64LeHeader()
        bytes.putU16Le(0x38, 0)
        bytes.putU16Le(0x3C, 0)

        val result = assertIs<NativeParseSuccess<ElfFile>>(ElfParser.parse(bytes.toCommonByteBuffer()))

        assertEquals(NativeBinaryWordSize.Bits64, result.value.header.wordSize)
        assertEquals(emptyList(), result.value.sections)
        assertEquals(emptyList(), result.value.programHeaders)
        assertEquals(emptyList(), result.value.unresolvedRecords)
        assertEquals(emptyList(), result.diagnostics)
    }

    @Test
    fun `parses ELF file with omitted section header table`() {
        val bytes = elf64LeHeader()
        bytes.putU64Le(0x28, 0uL)
        bytes.putU16Le(0x3A, 0)
        bytes.putU16Le(0x3C, 0)
        bytes.putU16Le(0x3E, 0)
        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!

        val sections = ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header)!!

        assertEquals(emptyList(), sections)
    }

    @Test
    fun `complete ELF parser reports invalid header failure`() {
        val result = assertIs<NativeParseFailure>(ElfParser.parse(byteArrayOf(1, 2, 3).toCommonByteBuffer()))

        assertEquals("elf.invalid_header", result.diagnostics.single().code)
    }

    @Test
    fun `parses ELF64 section headers and names`() {
        val bytes = elf64LeHeader().copyOf(0x300)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 3)
        bytes.putU16Le(0x3E, 2)

        val shstrtab = byteArrayOf(
            0,
            '.'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 't'.code.toByte(), 0,
            '.'.code.toByte(), 's'.code.toByte(), 'h'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
            'r'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 0,
        )
        shstrtab.copyInto(bytes, destinationOffset = 0x240)

        bytes.writeElf64SectionHeader(
            offset = 0x100 + 64,
            nameOffset = 1u,
            type = 1u,
            flags = 0x6uL,
            address = 0x401000uL,
            fileOffset = 0x200uL,
            size = 0x20uL,
            addressAlignment = 16uL,
        )
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 128,
            nameOffset = 7u,
            type = 3u,
            fileOffset = 0x240uL,
            size = shstrtab.size.toULong(),
            addressAlignment = 1uL,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val sections = ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header)!!

        assertEquals(3, sections.size)
        assertEquals("", sections[0].name)
        assertEquals(".text", sections[1].name)
        assertEquals(1u, sections[1].type)
        assertEquals(0x6uL, sections[1].flags)
        assertEquals(0x401000uL, sections[1].address)
        assertEquals(0x200uL, sections[1].fileOffset)
        assertEquals(0x20uL, sections[1].size)
        assertEquals(0x100 + 64, sections[1].headerRange.offset)
        assertEquals(64, sections[1].headerRange.size)
        assertEquals(0x200, sections[1].contentRange?.offset)
        assertEquals(0x20, sections[1].contentRange?.size)
        assertEquals(".shstrtab", sections[2].name)
    }

    @Test
    fun `parses ELF64 SHT_NOBITS section without file-backed content`() {
        val bytes = elf64LeHeader().copyOf(0x240)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 2)
        bytes.putU16Le(0x3E, 0)

        bytes.writeElf64SectionHeader(
            offset = 0x100 + 64,
            nameOffset = 0u,
            type = 8u,
            flags = 0x3uL,
            address = 0x404000uL,
            fileOffset = 0x1000uL,
            size = 0x80uL,
            addressAlignment = 16uL,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val sections = ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header)!!

        assertEquals(2, sections.size)
        assertEquals(8u, sections[1].type)
        assertEquals(0x80uL, sections[1].size)
        assertNull(sections[1].contentRange)
    }

    @Test
    fun `parses ELF64 extended section count and section name string table index`() {
        val bytes = elf64LeHeader().copyOf(0x380)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 0)
        bytes.putU16Le(0x3E, 0xFFFF)

        val shstrtab = byteArrayOf(
            0,
            '.'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 't'.code.toByte(), 0,
            '.'.code.toByte(), 's'.code.toByte(), 'h'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
            'r'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 0,
        )
        shstrtab.copyInto(bytes, destinationOffset = 0x300)

        bytes.writeElf64SectionHeader(
            offset = 0x100,
            nameOffset = 0u,
            type = 0u,
            size = 3uL,
            link = 2u,
        )
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 64,
            nameOffset = 1u,
            type = 1u,
            flags = 0x6uL,
            fileOffset = 0x280uL,
            size = 0x20uL,
            addressAlignment = 16uL,
        )
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 128,
            nameOffset = 7u,
            type = 3u,
            fileOffset = 0x300uL,
            size = shstrtab.size.toULong(),
            addressAlignment = 1uL,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val sections = ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header)!!

        assertEquals(3, sections.size)
        assertEquals(".text", sections[1].name)
        assertEquals(".shstrtab", sections[2].name)
    }

    @Test
    fun `parses ELF64 extended program header count`() {
        val bytes = elf64LeHeader().copyOf(0x300)
        bytes.putU64Le(0x20, 64uL)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x38, 0xFFFF)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 1)
        bytes.writeElf64ProgramHeader(
            offset = 64,
            type = 1u,
            flags = 0x5u,
            fileOffset = 0x200uL,
            virtualAddress = 0x401000uL,
            physicalAddress = 0x401000uL,
            fileSize = 0x40uL,
            memorySize = 0x40uL,
            alignment = 0x1000uL,
        )
        bytes.writeElf64SectionHeader(
            offset = 0x100,
            nameOffset = 0u,
            type = 0u,
            info = 1u,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val segments = ElfParser.parseProgramHeaders(bytes.toCommonByteBuffer(), header)!!

        assertEquals(1, segments.size)
        assertEquals(1u, segments.single().type)
        assertEquals(0x200, segments.single().fileRange?.offset)
    }

    @Test
    fun `returns null when ELF section table points outside file`() {
        val bytes = elf64LeHeader()
        bytes.putU64Le(0x28, 0x1000uL)

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!

        assertNull(ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header))
    }

    @Test
    fun `returns null when ELF file backed section range is unrepresentable`() {
        val bytes = elf64LeHeader().copyOf(0x180)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 2)
        bytes.putU16Le(0x3E, 0)
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 64,
            nameOffset = 0u,
            type = 1u,
            fileOffset = Int.MAX_VALUE.toULong() + 1uL,
            size = 1uL,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!

        assertNull(ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header))
    }

    @Test
    fun `returns null when ELF section entry size is too small`() {
        val bytes = elf64LeHeader().copyOf(0x200)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 32)
        bytes.putU16Le(0x3C, 1)

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!

        assertNull(ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header))
    }

    @Test
    fun `parses ELF64 program headers`() {
        val bytes = elf64LeHeader().copyOf(0x300)
        bytes.putU64Le(0x20, 64uL)
        bytes.putU16Le(0x36, 56)
        bytes.putU16Le(0x38, 1)
        bytes.writeElf64ProgramHeader(
            offset = 64,
            type = 1u,
            flags = 0x5u,
            fileOffset = 0x200uL,
            virtualAddress = 0x401000uL,
            physicalAddress = 0x401000uL,
            fileSize = 0x40uL,
            memorySize = 0x50uL,
            alignment = 0x1000uL,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val segments = ElfParser.parseProgramHeaders(bytes.toCommonByteBuffer(), header)!!

        assertEquals(1, segments.size)
        assertEquals(1u, segments[0].type)
        assertEquals(0x5u, segments[0].flags)
        assertEquals(0x200uL, segments[0].fileOffset)
        assertEquals(0x401000uL, segments[0].virtualAddress)
        assertEquals(0x40uL, segments[0].fileSize)
        assertEquals(0x50uL, segments[0].memorySize)
        assertEquals(0x1000uL, segments[0].alignment)
        assertEquals(64, segments[0].headerRange.offset)
        assertEquals(56, segments[0].headerRange.size)
        assertEquals(0x200, segments[0].fileRange?.offset)
        assertEquals(0x40, segments[0].fileRange?.size)
    }

    @Test
    fun `returns null when ELF program header file range points outside file`() {
        val bytes = elf64LeHeader().copyOf(128)
        bytes.putU16Le(0x38, 1)
        bytes.writeElf64ProgramHeader(
            offset = 64,
            type = 1u,
            fileOffset = 0x100uL,
            fileSize = 0x40uL,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!

        assertNull(ElfParser.parseProgramHeaders(bytes.toCommonByteBuffer(), header))
    }

    @Test
    fun `parses ELF64 symbol table names`() {
        val bytes = elf64LeHeader().copyOf(0x400)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 4)
        bytes.putU16Le(0x3E, 3)

        val shstrtab = byteArrayOf(
            0,
            '.'.code.toByte(), 's'.code.toByte(), 'y'.code.toByte(), 'm'.code.toByte(),
            't'.code.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 0,
            '.'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(), 'r'.code.toByte(),
            't'.code.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 0,
            '.'.code.toByte(), 's'.code.toByte(), 'h'.code.toByte(), 's'.code.toByte(),
            't'.code.toByte(), 'r'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            'b'.code.toByte(), 0,
        )
        val strtab = byteArrayOf(0, 'm'.code.toByte(), 'a'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 0)
        shstrtab.copyInto(bytes, destinationOffset = 0x320)
        strtab.copyInto(bytes, destinationOffset = 0x300)

        bytes.writeElf64SectionHeader(
            offset = 0x100 + 64,
            nameOffset = 1u,
            type = 2u,
            fileOffset = 0x200uL,
            size = 48uL,
            link = 2u,
            info = 1u,
            addressAlignment = 8uL,
            entrySize = 24uL,
        )
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 128,
            nameOffset = 9u,
            type = 3u,
            fileOffset = 0x300uL,
            size = strtab.size.toULong(),
            addressAlignment = 1uL,
        )
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 192,
            nameOffset = 17u,
            type = 3u,
            fileOffset = 0x320uL,
            size = shstrtab.size.toULong(),
            addressAlignment = 1uL,
        )
        bytes.writeElf64Symbol(
            offset = 0x200 + 24,
            nameOffset = 1u,
            info = 0x12,
            sectionIndex = 1,
            value = 0x401000uL,
            size = 0x2AuL,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val sections = ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header)!!
        val symbols = ElfParser.parseSymbols(bytes.toCommonByteBuffer(), header, sections)!!

        assertEquals(2, symbols.size)
        assertEquals("", symbols[0].name)
        assertEquals("main", symbols[1].name)
        assertEquals(1, symbols[1].binding)
        assertEquals(2, symbols[1].type)
        assertEquals(1, symbols[1].sectionIndex)
        assertEquals(0x401000uL, symbols[1].value)
        assertEquals(0x2AuL, symbols[1].size)
        assertEquals(0x200 + 24, symbols[1].recordRange.offset)
        assertEquals(24, symbols[1].recordRange.size)
    }

    @Test
    fun `returns null when ELF symbol table size is not entry aligned`() {
        val bytes = elf64LeHeader().copyOf(0x320)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 2)
        bytes.putU16Le(0x3E, 0)
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 64,
            nameOffset = 0u,
            type = 2u,
            fileOffset = 0x300uL,
            size = 25uL,
            entrySize = 24uL,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val sections = ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header)!!

        assertNull(ElfParser.parseSymbols(bytes.toCommonByteBuffer(), header, sections))
    }

    @Test
    fun `parses ELF64 RELA relocations and resolves symbols`() {
        val bytes = elf64LeHeader().copyOf(0x500)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 6)
        bytes.putU16Le(0x3E, 5)

        val shstrtab = byteArrayOf(
            0,
            '.'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 't'.code.toByte(), 0,
            '.'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(),
            '.'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 't'.code.toByte(), 0,
            '.'.code.toByte(), 's'.code.toByte(), 'y'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(),
            'a'.code.toByte(), 'b'.code.toByte(), 0,
            '.'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(), 'r'.code.toByte(), 't'.code.toByte(),
            'a'.code.toByte(), 'b'.code.toByte(), 0,
            '.'.code.toByte(), 's'.code.toByte(), 'h'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
            'r'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 0,
        )
        val strtab = byteArrayOf(0, 'p'.code.toByte(), 'u'.code.toByte(), 't'.code.toByte(), 's'.code.toByte(), 0)
        shstrtab.copyInto(bytes, destinationOffset = 0x420)
        strtab.copyInto(bytes, destinationOffset = 0x400)

        bytes.writeElf64SectionHeader(
            offset = 0x100 + 64,
            nameOffset = 1u,
            type = 1u,
            flags = 0x6uL,
            address = 0x401000uL,
            fileOffset = 0x280uL,
            size = 0x20uL,
            addressAlignment = 16uL,
        )
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 128,
            nameOffset = 7u,
            type = 4u,
            fileOffset = 0x300uL,
            size = 24uL,
            link = 3u,
            info = 1u,
            addressAlignment = 8uL,
            entrySize = 24uL,
        )
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 192,
            nameOffset = 18u,
            type = 2u,
            fileOffset = 0x380uL,
            size = 48uL,
            link = 4u,
            info = 1u,
            addressAlignment = 8uL,
            entrySize = 24uL,
        )
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 256,
            nameOffset = 26u,
            type = 3u,
            fileOffset = 0x400uL,
            size = strtab.size.toULong(),
            addressAlignment = 1uL,
        )
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 320,
            nameOffset = 34u,
            type = 3u,
            fileOffset = 0x420uL,
            size = shstrtab.size.toULong(),
            addressAlignment = 1uL,
        )
        bytes.writeElf64Symbol(
            offset = 0x380 + 24,
            nameOffset = 1u,
            info = 0x12,
            sectionIndex = 0,
            value = 0uL,
            size = 0uL,
        )
        bytes.writeElf64Rela(
            offset = 0x300,
            relocationOffset = 0x401004uL,
            symbolIndex = 1u,
            type = 4u,
            addend = -4L,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val sections = ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header)!!
        val symbols = ElfParser.parseSymbols(bytes.toCommonByteBuffer(), header, sections)!!
        val relocations = ElfParser.parseRelocations(bytes.toCommonByteBuffer(), header, sections, symbols)!!

        assertEquals(1, relocations.size)
        assertEquals(2, relocations[0].relocationSectionIndex)
        assertEquals(1, relocations[0].targetSectionIndex)
        assertEquals(0x401004uL, relocations[0].offset)
        assertEquals(1u, relocations[0].symbolIndex)
        assertEquals(4u, relocations[0].type)
        assertEquals(-4L, relocations[0].addend)
        assertEquals("puts", relocations[0].symbol?.name)
        assertEquals(0x300, relocations[0].recordRange.offset)
        assertEquals(24, relocations[0].recordRange.size)
    }

    @Test
    fun `parses ELF64 REL relocations without addends`() {
        val bytes = elf64LeHeader().copyOf(0x380)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 2)
        bytes.putU16Le(0x3E, 0)
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 64,
            nameOffset = 0u,
            type = 9u,
            fileOffset = 0x300uL,
            size = 16uL,
            link = 0u,
            info = 0u,
            addressAlignment = 8uL,
            entrySize = 16uL,
        )
        bytes.writeElf64Rel(
            offset = 0x300,
            relocationOffset = 0x20uL,
            symbolIndex = 0u,
            type = 8u,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val sections = ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header)!!
        val relocations = ElfParser.parseRelocations(bytes.toCommonByteBuffer(), header, sections, emptyList())!!

        assertEquals(1, relocations.size)
        assertEquals(0x20uL, relocations[0].offset)
        assertEquals(0u, relocations[0].symbolIndex)
        assertEquals(8u, relocations[0].type)
        assertNull(relocations[0].addend)
    }

    @Test
    fun `returns null when ELF relocation entry size is too small`() {
        val bytes = elf64LeHeader().copyOf(0x320)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 2)
        bytes.putU16Le(0x3E, 0)
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 64,
            nameOffset = 0u,
            type = 9u,
            fileOffset = 0x300uL,
            size = 16uL,
            entrySize = 8uL,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val sections = ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header)!!

        assertNull(ElfParser.parseRelocations(bytes.toCommonByteBuffer(), header, sections, emptyList()))
    }

    @Test
    fun `parses ELF64 dynamic needed libraries`() {
        val bytes = elf64LeHeader().copyOf(0x480)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 3)
        bytes.putU16Le(0x3E, 0)

        val dynstr = byteArrayOf(
            0,
            'l'.code.toByte(), 'i'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(),
            '.'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), '.'.code.toByte(),
            '6'.code.toByte(), 0,
        )
        dynstr.copyInto(bytes, destinationOffset = 0x400)

        bytes.writeElf64SectionHeader(
            offset = 0x100 + 64,
            nameOffset = 0u,
            type = 6u,
            fileOffset = 0x300uL,
            size = 32uL,
            link = 2u,
            addressAlignment = 8uL,
            entrySize = 16uL,
        )
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 128,
            nameOffset = 0u,
            type = 3u,
            fileOffset = 0x400uL,
            size = dynstr.size.toULong(),
            addressAlignment = 1uL,
        )
        bytes.writeElf64DynamicEntry(offset = 0x300, tag = 1L, value = 1uL)
        bytes.writeElf64DynamicEntry(offset = 0x310, tag = 0L, value = 0uL)

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val sections = ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header)!!
        val dynamicEntries = ElfParser.parseDynamicEntries(bytes.toCommonByteBuffer(), header, sections)!!

        assertEquals(2, dynamicEntries.size)
        assertEquals(1L, dynamicEntries[0].tag)
        assertEquals(1uL, dynamicEntries[0].value)
        assertEquals("libc.so.6", dynamicEntries[0].neededLibrary)
        assertEquals(0x300, dynamicEntries[0].recordRange.offset)
        assertEquals(16, dynamicEntries[0].recordRange.size)
        assertEquals(0L, dynamicEntries[1].tag)
        assertNull(dynamicEntries[1].neededLibrary)
    }

    @Test
    fun `returns null when ELF dynamic entry size is too small`() {
        val bytes = elf64LeHeader().copyOf(0x320)
        bytes.putU64Le(0x28, 0x100uL)
        bytes.putU16Le(0x3A, 64)
        bytes.putU16Le(0x3C, 2)
        bytes.putU16Le(0x3E, 0)
        bytes.writeElf64SectionHeader(
            offset = 0x100 + 64,
            nameOffset = 0u,
            type = 6u,
            fileOffset = 0x300uL,
            size = 16uL,
            entrySize = 8uL,
        )

        val header = ElfParser.parseHeader(bytes.toCommonByteBuffer())!!
        val sections = ElfParser.parseSectionHeaders(bytes.toCommonByteBuffer(), header)!!

        assertNull(ElfParser.parseDynamicEntries(bytes.toCommonByteBuffer(), header, sections))
    }
}

private fun elf64LeHeader(): ByteArray {
    val bytes = ByteArray(64)
    bytes.writeElfIdent(wordSize = 2, endian = 1)
    bytes.putU16Le(0x10, 2)
    bytes.putU16Le(0x12, 0x3E)
    bytes.putU32Le(0x14, 1u)
    bytes.putU64Le(0x18, 0x401000uL)
    bytes.putU64Le(0x20, 64uL)
    bytes.putU64Le(0x28, 0x200uL)
    bytes.putU32Le(0x30, 0u)
    bytes.putU16Le(0x34, 64)
    bytes.putU16Le(0x36, 56)
    bytes.putU16Le(0x38, 3)
    bytes.putU16Le(0x3A, 64)
    bytes.putU16Le(0x3C, 5)
    bytes.putU16Le(0x3E, 4)
    return bytes
}

private fun ByteArray.writeElfIdent(wordSize: Int, endian: Int) {
    this[0] = 0x7F
    this[1] = 'E'.code.toByte()
    this[2] = 'L'.code.toByte()
    this[3] = 'F'.code.toByte()
    this[4] = wordSize.toByte()
    this[5] = endian.toByte()
    this[6] = 1
}

private fun ByteArray.putU16Le(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

private fun ByteArray.putU16Be(offset: Int, value: Int) {
    this[offset] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 1] = (value and 0xFF).toByte()
}

private fun ByteArray.putU32Le(offset: Int, value: UInt) {
    this[offset] = (value and 0xFFu).toByte()
    this[offset + 1] = ((value shr 8) and 0xFFu).toByte()
    this[offset + 2] = ((value shr 16) and 0xFFu).toByte()
    this[offset + 3] = ((value shr 24) and 0xFFu).toByte()
}

private fun ByteArray.putU32Be(offset: Int, value: UInt) {
    this[offset] = ((value shr 24) and 0xFFu).toByte()
    this[offset + 1] = ((value shr 16) and 0xFFu).toByte()
    this[offset + 2] = ((value shr 8) and 0xFFu).toByte()
    this[offset + 3] = (value and 0xFFu).toByte()
}

private fun ByteArray.putU64Le(offset: Int, value: ULong) {
    this[offset] = (value and 0xFFuL).toByte()
    this[offset + 1] = ((value shr 8) and 0xFFuL).toByte()
    this[offset + 2] = ((value shr 16) and 0xFFuL).toByte()
    this[offset + 3] = ((value shr 24) and 0xFFuL).toByte()
    this[offset + 4] = ((value shr 32) and 0xFFuL).toByte()
    this[offset + 5] = ((value shr 40) and 0xFFuL).toByte()
    this[offset + 6] = ((value shr 48) and 0xFFuL).toByte()
    this[offset + 7] = ((value shr 56) and 0xFFuL).toByte()
}

private fun ByteArray.writeElf64SectionHeader(
    offset: Int,
    nameOffset: UInt,
    type: UInt,
    flags: ULong = 0uL,
    address: ULong = 0uL,
    fileOffset: ULong = 0uL,
    size: ULong = 0uL,
    link: UInt = 0u,
    info: UInt = 0u,
    addressAlignment: ULong = 0uL,
    entrySize: ULong = 0uL,
) {
    putU32Le(offset, nameOffset)
    putU32Le(offset + 0x04, type)
    putU64Le(offset + 0x08, flags)
    putU64Le(offset + 0x10, address)
    putU64Le(offset + 0x18, fileOffset)
    putU64Le(offset + 0x20, size)
    putU32Le(offset + 0x28, link)
    putU32Le(offset + 0x2C, info)
    putU64Le(offset + 0x30, addressAlignment)
    putU64Le(offset + 0x38, entrySize)
}

private fun ByteArray.writeElf64ProgramHeader(
    offset: Int,
    type: UInt,
    flags: UInt = 0u,
    fileOffset: ULong = 0uL,
    virtualAddress: ULong = 0uL,
    physicalAddress: ULong = 0uL,
    fileSize: ULong = 0uL,
    memorySize: ULong = 0uL,
    alignment: ULong = 0uL,
) {
    putU32Le(offset, type)
    putU32Le(offset + 0x04, flags)
    putU64Le(offset + 0x08, fileOffset)
    putU64Le(offset + 0x10, virtualAddress)
    putU64Le(offset + 0x18, physicalAddress)
    putU64Le(offset + 0x20, fileSize)
    putU64Le(offset + 0x28, memorySize)
    putU64Le(offset + 0x30, alignment)
}

private fun ByteArray.writeElf64Symbol(
    offset: Int,
    nameOffset: UInt,
    info: Int,
    other: Int = 0,
    sectionIndex: Int,
    value: ULong,
    size: ULong,
) {
    putU32Le(offset, nameOffset)
    this[offset + 0x04] = info.toByte()
    this[offset + 0x05] = other.toByte()
    putU16Le(offset + 0x06, sectionIndex)
    putU64Le(offset + 0x08, value)
    putU64Le(offset + 0x10, size)
}

private fun ByteArray.writeElf64Rela(
    offset: Int,
    relocationOffset: ULong,
    symbolIndex: UInt,
    type: UInt,
    addend: Long,
) {
    putU64Le(offset, relocationOffset)
    putU64Le(offset + 0x08, (symbolIndex.toULong() shl 32) or type.toULong())
    putU64Le(offset + 0x10, addend.toULong())
}

private fun ByteArray.writeElf64Rel(
    offset: Int,
    relocationOffset: ULong,
    symbolIndex: UInt,
    type: UInt,
) {
    putU64Le(offset, relocationOffset)
    putU64Le(offset + 0x08, (symbolIndex.toULong() shl 32) or type.toULong())
}

private fun ByteArray.writeElf64DynamicEntry(
    offset: Int,
    tag: Long,
    value: ULong,
) {
    putU64Le(offset, tag.toULong())
    putU64Le(offset + 0x08, value)
}
