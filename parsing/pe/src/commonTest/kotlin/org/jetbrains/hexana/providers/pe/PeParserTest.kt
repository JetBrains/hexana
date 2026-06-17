package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeParseFailure
import org.jetbrains.hexana.parsing.common.NativeParseSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeParserTest {
    @Test
    fun `parses DOS COFF and PE32 plus optional headers`() {
        val bytes = pe32PlusBytes().toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(bytes)!!
        val coffHeader = PeParser.parseCoffHeader(bytes, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(bytes, dosHeader, coffHeader)!!

        assertEquals(0x80u, dosHeader.peHeaderOffset)
        assertEquals(0x3C, dosHeader.peHeaderOffsetRange.offset)
        assertEquals(0x80, dosHeader.peSignatureRange.offset)

        assertEquals(0x8664, coffHeader.machine)
        assertEquals(2, coffHeader.sectionCount)
        assertEquals(0x66778899u, coffHeader.timeDateStamp)
        assertEquals(0x300u, coffHeader.symbolTableOffset)
        assertEquals(4u, coffHeader.symbolCount)
        assertEquals(0xF0, coffHeader.optionalHeaderSize)
        assertEquals(0x22, coffHeader.characteristics)
        assertEquals(0x84, coffHeader.headerRange.offset)

        assertEquals(NativeBinaryWordSize.Bits64, optionalHeader.wordSize)
        assertEquals(0x020B, optionalHeader.magic)
        assertEquals(0x1010u, optionalHeader.addressOfEntryPoint)
        assertEquals(0x140000000uL, optionalHeader.imageBase)
        assertEquals(0x1000u, optionalHeader.sectionAlignment)
        assertEquals(0x200u, optionalHeader.fileAlignment)
        assertEquals(0x4000u, optionalHeader.sizeOfImage)
        assertEquals(0x400u, optionalHeader.sizeOfHeaders)
        assertEquals(3, optionalHeader.subsystem)
        assertEquals(0x8160, optionalHeader.dllCharacteristics)
        assertEquals(16u, optionalHeader.dataDirectoryCount)
        assertEquals(16, optionalHeader.dataDirectories.size)
        assertEquals("Export Table", optionalHeader.dataDirectories[0].name)
        assertEquals(0x2100u, optionalHeader.dataDirectories[0].rva)
        assertEquals(0x80u, optionalHeader.dataDirectories[0].size)
        assertEquals("Import Table", optionalHeader.dataDirectories[1].name)
        assertEquals(0x2050u, optionalHeader.dataDirectories[1].rva)
        assertEquals(0x28u, optionalHeader.dataDirectories[1].size)
        assertEquals("Base Relocation Table", optionalHeader.dataDirectories[5].name)
        assertEquals(0x20C0u, optionalHeader.dataDirectories[5].rva)
        assertEquals(0x0Cu, optionalHeader.dataDirectories[5].size)
        assertEquals("Debug", optionalHeader.dataDirectories[6].name)
        assertEquals(0x20D0u, optionalHeader.dataDirectories[6].rva)
        assertEquals(0x1Cu, optionalHeader.dataDirectories[6].size)
        assertEquals(0x98, optionalHeader.headerRange.offset)
        assertEquals(0xF0, optionalHeader.headerRange.size)
    }

    @Test
    fun `parses PE image file model`() {
        val result = assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(pe32PlusBytes().toCommonByteBuffer()))
        val file = assertIs<PeImageFile>(result.value)

        assertEquals(2, file.sections.size)
        assertEquals(1, file.imports.size)
        assertEquals(3, file.coffSymbols.size)
        assertEquals(1, file.debugEntries.size)
        assertEquals(emptyList(), file.unresolvedRecords)
    }

    @Test
    fun `complete PE parser reports invalid header failure`() {
        val result = assertIs<NativeParseFailure>(PeParser.parse(byteArrayOf(1, 2, 3).toCommonByteBuffer()))

        assertEquals("pe.invalid_header", result.diagnostics.single().code)
    }

    @Test
    fun `complete PE parser rejects implausible COFF object header`() {
        val result = assertIs<NativeParseFailure>(PeParser.parse(ByteArray(20).toCommonByteBuffer()))

        assertEquals("pe.invalid_header", result.diagnostics.single().code)
        assertNull(PeParser.parseCoffObjectHeader(ByteArray(20).toCommonByteBuffer()))
    }

    @Test
    fun `parses plausible COFF object header`() {
        val bytes = ByteArray(20 + 40)
        bytes.putU16Le(0, 0x8664)
        bytes.putU16Le(2, 1)
        bytes.putU32Le(8, 0u)
        bytes.putU32Le(12, 0u)
        bytes.putU16Le(16, 0)
        bytes.putFixedString(20, ".text", 8)

        val header = PeParser.parseCoffObjectHeader(bytes.toCommonByteBuffer())!!

        assertEquals(0x8664, header.machine)
        assertEquals(1, header.sectionCount)
    }

    @Test
    fun `parses PE section table`() {
        val bytes = pe32PlusBytes().toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(bytes)!!
        val coffHeader = PeParser.parseCoffHeader(bytes, dosHeader)!!
        val sections = PeParser.parseSections(bytes, dosHeader, coffHeader)!!

        assertEquals(2, sections.size)

        val text = sections[0]
        assertEquals(".text", text.name)
        assertEquals(0x123u, text.virtualSize)
        assertEquals(0x1000u, text.virtualAddress)
        assertEquals(0x200u, text.rawDataSize)
        assertEquals(0x400u, text.rawDataPointer)
        assertEquals(0, text.relocationCount)
        assertEquals(0x60000020u, text.characteristics)
        assertEquals(0x188, text.headerRange.offset)
        assertEquals(0x400, text.contentRange?.offset)
        assertEquals(0x200, text.contentRange?.size)

        val rdata = sections[1]
        assertEquals(".rdata", rdata.name)
        assertEquals(0x80u, rdata.virtualSize)
        assertEquals(0x2000u, rdata.virtualAddress)
        assertEquals(0x200u, rdata.rawDataSize)
        assertEquals(0x600u, rdata.rawDataPointer)
        assertEquals(0x40000040u, rdata.characteristics)
    }

    @Test
    fun `parses PE import directory`() {
        val bytes = pe32PlusBytes().toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(bytes)!!
        val coffHeader = PeParser.parseCoffHeader(bytes, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(bytes, dosHeader, coffHeader)!!
        val sections = PeParser.parseSections(bytes, dosHeader, coffHeader)!!
        val imports = PeParser.parseImports(bytes, optionalHeader, sections)!!

        assertEquals(1, imports.size)
        val library = imports.single()
        assertEquals(0, library.index)
        assertEquals("msvcrt.dll", library.name)
        assertEquals(0x2090u, library.importLookupTableRva)
        assertEquals(0x20A0u, library.importAddressTableRva)
        assertEquals(0x650, library.descriptorRange.offset)
        assertEquals(0x680, library.nameRange?.offset)

        assertEquals(1, library.entries.size)
        val entry = library.entries.single()
        assertEquals(0, entry.index)
        assertEquals("puts", entry.name)
        assertEquals(7, entry.hint)
        assertEquals(null, entry.ordinal)
        assertEquals(0x2090u, entry.thunkRva)
        assertEquals(0x690, entry.thunkRange.offset)
        assertEquals(8, entry.thunkRange.size)
        assertEquals(0x6A8, entry.hintNameRange?.offset)
    }

    @Test
    fun `parses PE import directory when data directory size is zero`() {
        val bytes = pe32PlusBytes()
        bytes.putU32Le(0x98 + 112 + 12, 0u)
        val buffer = bytes.toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(buffer)!!
        val coffHeader = PeParser.parseCoffHeader(buffer, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(buffer, dosHeader, coffHeader)!!
        val sections = PeParser.parseSections(buffer, dosHeader, coffHeader)!!
        val imports = PeParser.parseImports(buffer, optionalHeader, sections)!!

        assertEquals(1, imports.size)
        assertEquals("msvcrt.dll", imports.single().name)
        assertEquals("puts", imports.single().entries.single().name)
    }

    @Test
    fun `returns null when PE import entries cannot be mapped`() {
        val bytes = pe32PlusBytes()
        bytes.putU32Le(0x650, 0x900000u)
        val buffer = bytes.toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(buffer)!!
        val coffHeader = PeParser.parseCoffHeader(buffer, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(buffer, dosHeader, coffHeader)!!
        val sections = PeParser.parseSections(buffer, dosHeader, coffHeader)!!

        assertNull(PeParser.parseImports(buffer, optionalHeader, sections))
    }

    @Test
    fun `returns null when PE import library name cannot be mapped`() {
        val bytes = pe32PlusBytes()
        bytes.putU32Le(0x65C, 0x900000u)
        val buffer = bytes.toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(buffer)!!
        val coffHeader = PeParser.parseCoffHeader(buffer, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(buffer, dosHeader, coffHeader)!!
        val sections = PeParser.parseSections(buffer, dosHeader, coffHeader)!!

        assertNull(PeParser.parseImports(buffer, optionalHeader, sections))
    }

    @Test
    fun `returns null when PE import by name thunk cannot be mapped`() {
        val bytes = pe32PlusBytes()
        bytes.putU64Le(0x690, 0x900000uL)
        val buffer = bytes.toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(buffer)!!
        val coffHeader = PeParser.parseCoffHeader(buffer, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(buffer, dosHeader, coffHeader)!!
        val sections = PeParser.parseSections(buffer, dosHeader, coffHeader)!!

        assertNull(PeParser.parseImports(buffer, optionalHeader, sections))
    }

    @Test
    fun `parses PE export directory`() {
        val bytes = pe32PlusBytes().toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(bytes)!!
        val coffHeader = PeParser.parseCoffHeader(bytes, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(bytes, dosHeader, coffHeader)!!
        val sections = PeParser.parseSections(bytes, dosHeader, coffHeader)!!
        val exports = PeParser.parseExports(bytes, optionalHeader, sections)!!

        assertEquals("sample.dll", exports.name)
        assertEquals(1u, exports.ordinalBase)
        assertEquals(1u, exports.functionCount)
        assertEquals(1u, exports.nameCount)
        assertEquals(0x700, exports.directoryRange.offset)
        assertEquals(0x750, exports.nameRange?.offset)

        assertEquals(1, exports.entries.size)
        val entry = exports.entries.single()
        assertEquals(0, entry.index)
        assertEquals("exported_func", entry.name)
        assertEquals(1u, entry.ordinal)
        assertEquals(0x1010u, entry.rva)
        assertEquals(null, entry.forwarder)
        assertEquals(0x760, entry.addressTableRange.offset)
        assertEquals(0x790, entry.nameRange?.offset)
    }

    @Test
    fun `reports diagnostic when PE export directory is malformed`() {
        val bytes = pe32PlusBytes()
        bytes.putU32Le(0x700 + 28, 0x900000u)

        val result = assertIs<NativeParseSuccess<PeFile>>(PeParser.parse(bytes.toCommonByteBuffer()))
        val file = assertIs<PeImageFile>(result.value)

        assertNull(file.exports)
        assertTrue(result.diagnostics.any { it.code == "pe.invalid_exports" })
    }

    @Test
    fun `returns null when PE export directory RVA range overflows`() {
        val bytes = pe32PlusBytes()
        bytes.putU32Le(0x98 + 116, 0xFFFFDF00u)
        val buffer = bytes.toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(buffer)!!
        val coffHeader = PeParser.parseCoffHeader(buffer, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(buffer, dosHeader, coffHeader)!!
        val sections = PeParser.parseSections(buffer, dosHeader, coffHeader)!!

        assertNull(PeParser.parseExports(buffer, optionalHeader, sections))
    }

    @Test
    fun `returns null when PE export ordinal overflows`() {
        val bytes = pe32PlusBytes()
        bytes.putU32Le(0x700 + 16, UInt.MAX_VALUE)
        bytes.putU32Le(0x700 + 20, 2u)
        bytes.putU32Le(0x760 + 4, 0x1020u)
        val buffer = bytes.toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(buffer)!!
        val coffHeader = PeParser.parseCoffHeader(buffer, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(buffer, dosHeader, coffHeader)!!
        val sections = PeParser.parseSections(buffer, dosHeader, coffHeader)!!

        assertNull(PeParser.parseExports(buffer, optionalHeader, sections))
    }

    @Test
    fun `parses PE base relocations`() {
        val bytes = pe32PlusBytes().toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(bytes)!!
        val coffHeader = PeParser.parseCoffHeader(bytes, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(bytes, dosHeader, coffHeader)!!
        val sections = PeParser.parseSections(bytes, dosHeader, coffHeader)!!
        val relocations = PeParser.parseBaseRelocations(bytes, optionalHeader, sections)!!

        assertEquals(1, relocations.size)
        val relocation = relocations.single()
        assertEquals(0, relocation.blockIndex)
        assertEquals(0, relocation.entryIndex)
        assertEquals(10, relocation.type)
        assertEquals(0x1000u, relocation.pageRva)
        assertEquals(0x1018u, relocation.targetRva)
        assertEquals(0x6C8, relocation.recordRange.offset)
    }

    @Test
    fun `returns null when PE base relocation block exceeds directory`() {
        val bytes = pe32PlusBytes()
        bytes.putU32Le(0x6C4, 0x20u)
        val buffer = bytes.toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(buffer)!!
        val coffHeader = PeParser.parseCoffHeader(buffer, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(buffer, dosHeader, coffHeader)!!
        val sections = PeParser.parseSections(buffer, dosHeader, coffHeader)!!

        assertNull(PeParser.parseBaseRelocations(buffer, optionalHeader, sections))
    }

    @Test
    fun `parses PE debug directory CodeView record`() {
        val bytes = pe32PlusBytes().toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(bytes)!!
        val coffHeader = PeParser.parseCoffHeader(bytes, dosHeader)!!
        val optionalHeader = PeParser.parseOptionalHeader(bytes, dosHeader, coffHeader)!!
        val sections = PeParser.parseSections(bytes, dosHeader, coffHeader)!!
        val debugEntries = PeParser.parseDebugEntries(bytes, optionalHeader, sections)!!

        assertEquals(1, debugEntries.size)
        val entry = debugEntries.single()
        assertEquals(0, entry.index)
        assertEquals(2u, entry.type)
        assertEquals(0x66778899u, entry.timeDateStamp)
        assertEquals(0x30u, entry.sizeOfData)
        assertEquals(0x22B0u, entry.addressOfRawData)
        assertEquals(0x7B0u, entry.pointerToRawData)
        assertEquals(0x6D0, entry.recordRange.offset)
        assertEquals(0x7B0, entry.dataRange?.offset)

        assertEquals("RSDS", entry.codeView?.signature)
        assertEquals("11223344-5566-7788-99AA-BBCCDDEEFF00", entry.codeView?.guid)
        assertEquals(3u, entry.codeView?.age)
        assertEquals("C:\\symbols\\sample.pdb", entry.codeView?.pdbPath)
    }

    @Test
    fun `parses COFF symbol table`() {
        val bytes = pe32PlusBytes().toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(bytes)!!
        val coffHeader = PeParser.parseCoffHeader(bytes, dosHeader)!!
        val symbols = PeParser.parseCoffSymbols(bytes, coffHeader)!!

        assertEquals(3, symbols.size)

        assertEquals(0, symbols[0].index)
        assertEquals("main", symbols[0].name)
        assertEquals(0x10u, symbols[0].value)
        assertEquals(1, symbols[0].sectionNumber)
        assertEquals(0x20, symbols[0].type)
        assertEquals(2, symbols[0].storageClass)
        assertEquals(0, symbols[0].auxiliaryCount)

        assertEquals(1, symbols[1].index)
        assertEquals("very_long_symbol", symbols[1].name)
        assertEquals(1, symbols[1].auxiliaryCount)
        assertEquals(0x312, symbols[1].recordRange.offset)
        assertEquals(36, symbols[1].recordRange.size)

        assertEquals(3, symbols[2].index)
        assertEquals("puts", symbols[2].name)
    }

    @Test
    fun `returns null when COFF auxiliary symbol count exceeds declared symbol table`() {
        val bytes = pe32PlusBytes()
        bytes.putU32Le(0x90, 1u)
        bytes[0x300 + 17] = 1

        val buffer = bytes.toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(buffer)!!
        val coffHeader = PeParser.parseCoffHeader(buffer, dosHeader)!!

        assertNull(PeParser.parseCoffSymbols(buffer, coffHeader))
    }

    @Test
    fun `parses COFF relocation overflow count`() {
        val bytes = ByteArray(64)
        bytes.putU32Le(4, 3u)
        bytes.putU32Le(14, 0x10u)
        bytes.putU32Le(18, 1u)
        bytes.putU16Le(22, 0x20)
        bytes.putU32Le(24, 0x20u)
        bytes.putU32Le(28, 2u)
        bytes.putU16Le(32, 0x30)
        bytes.putU32Le(34, 0x30u)
        bytes.putU32Le(38, 3u)
        bytes.putU16Le(42, 0x40)
        val section = PeSection(
            index = 0,
            name = ".text",
            virtualSize = 0u,
            virtualAddress = 0u,
            rawDataSize = 0u,
            rawDataPointer = 0u,
            relocationPointer = 4u,
            lineNumberPointer = 0u,
            relocationCount = 0xFFFF,
            lineNumberCount = 0,
            characteristics = 0x01000000u,
            headerRange = org.jetbrains.hexana.parsing.common.NativeByteRange(0, 0),
            contentRange = null,
        )

        val relocations = PeParser.parseCoffRelocations(bytes.toCommonByteBuffer(), listOf(section), emptyList())!!

        assertEquals(3, relocations.size)
        assertEquals(0x10u, relocations[0].virtualAddress)
        assertEquals(4 + 10, relocations[0].recordRange.offset)
        assertEquals(0x20u, relocations[1].virtualAddress)
        assertEquals(0x30u, relocations[2].virtualAddress)
    }

    @Test
    fun `parses COFF archive special and long member names`() {
        val archive = coffArchiveBytes(
            "/" to byteArrayOf(1, 2, 3, 4),
            "//" to "long_member.obj/\n".encodeToByteArray(),
            "/0" to "OBJECT".encodeToByteArray(),
            "#1/10" to "inline.objPAYLOAD".encodeToByteArray(),
        )

        val file = PeParser.parseArchive(archive.toCommonByteBuffer())!!

        assertEquals(4, file.members.size)
        assertEquals("/", file.members[0].name)
        assertEquals("//", file.members[1].name)
        assertEquals("long_member.obj", file.members[2].name)
        assertEquals("inline.obj", file.members[3].name)
        assertEquals(6, file.members[2].contentRange.size)
        assertEquals(7, file.members[3].contentRange.size)
        assertEquals('P'.code.toByte(), archive[file.members[3].contentRange.offset])
        assertTrue(file.members[3].contentRange.offset > file.members[3].headerRange.offset + 60)
    }

    @Test
    fun `flags archive bookkeeping members`() {
        val archive = coffArchiveBytes(
            "/" to byteArrayOf(1, 2, 3, 4),
            "//" to "long_member.obj/\n".encodeToByteArray(),
            "/0" to "OBJECT".encodeToByteArray(),
            "#1/10" to "inline.objPAYLOAD".encodeToByteArray(),
        )

        val file = PeParser.parseArchive(archive.toCommonByteBuffer())!!

        // Symbol table "/" and long-names table "//" are bookkeeping; real members are not.
        assertTrue(file.members[0].isBookkeeping)  // "/"
        assertTrue(file.members[1].isBookkeeping)  // "//"
        assertEquals(false, file.members[2].isBookkeeping)  // long_member.obj
        assertEquals(false, file.members[3].isBookkeeping)  // inline.obj
        assertEquals(2, file.members.count { !it.isBookkeeping })
    }

    @Test
    fun `returns null for COFF archive with missing odd member padding`() {
        val archive = coffArchiveBytes("odd.obj" to byteArrayOf(1)).copyOf(ARCHIVE_MAGIC_AND_HEADER_SIZE + 1)

        assertNull(PeParser.parseArchive(archive.toCommonByteBuffer()))
    }

    @Test
    fun `returns null for truncated section table`() {
        val bytes = pe32PlusBytes().copyOf(0x190).toCommonByteBuffer()
        val dosHeader = PeParser.parseDosHeader(bytes)!!
        val coffHeader = PeParser.parseCoffHeader(bytes, dosHeader)!!

        assertNull(PeParser.parseSections(bytes, dosHeader, coffHeader))
    }

    @Test
    fun `returns null for MZ file without PE signature`() {
        val bytes = ByteArray(128)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes.putU32Le(0x3C, 0x40u)

        assertNull(PeParser.parseDosHeader(bytes.toCommonByteBuffer()))
    }

    private fun pe32PlusBytes(): ByteArray {
        val bytes = ByteArray(0x800)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes.putU32Le(0x3C, 0x80u)

        bytes[0x80] = 'P'.code.toByte()
        bytes[0x81] = 'E'.code.toByte()
        bytes[0x82] = 0
        bytes[0x83] = 0

        bytes.putU16Le(0x84, 0x8664)
        bytes.putU16Le(0x86, 2)
        bytes.putU32Le(0x88, 0x66778899u)
        bytes.putU32Le(0x8C, 0x300u)
        bytes.putU32Le(0x90, 4u)
        bytes.putU16Le(0x94, 0xF0)
        bytes.putU16Le(0x96, 0x22)

        bytes.putU16Le(0x98, 0x020B)
        bytes.putU32Le(0x98 + 16, 0x1010u)
        bytes.putU64Le(0x98 + 24, 0x140000000uL)
        bytes.putU32Le(0x98 + 32, 0x1000u)
        bytes.putU32Le(0x98 + 36, 0x200u)
        bytes.putU32Le(0x98 + 56, 0x4000u)
        bytes.putU32Le(0x98 + 60, 0x400u)
        bytes.putU16Le(0x98 + 68, 3)
        bytes.putU16Le(0x98 + 70, 0x8160)
        bytes.putU32Le(0x98 + 108, 16u)
        bytes.putU32Le(0x98 + 112, 0x2100u)
        bytes.putU32Le(0x98 + 116, 0x80u)
        bytes.putU32Le(0x98 + 112 + 8, 0x2050u)
        bytes.putU32Le(0x98 + 112 + 12, 0x28u)
        bytes.putU32Le(0x98 + 112 + 40, 0x20C0u)
        bytes.putU32Le(0x98 + 112 + 44, 0x0Cu)
        bytes.putU32Le(0x98 + 112 + 48, 0x20D0u)
        bytes.putU32Le(0x98 + 112 + 52, 0x1Cu)

        bytes.putFixedString(0x188, ".text", 8)
        bytes.putU32Le(0x188 + 8, 0x123u)
        bytes.putU32Le(0x188 + 12, 0x1000u)
        bytes.putU32Le(0x188 + 16, 0x200u)
        bytes.putU32Le(0x188 + 20, 0x400u)
        bytes.putU16Le(0x188 + 32, 0)
        bytes.putU32Le(0x188 + 36, 0x60000020u)

        bytes.putFixedString(0x1B0, ".rdata", 8)
        bytes.putU32Le(0x1B0 + 8, 0x80u)
        bytes.putU32Le(0x1B0 + 12, 0x2000u)
        bytes.putU32Le(0x1B0 + 16, 0x200u)
        bytes.putU32Le(0x1B0 + 20, 0x600u)
        bytes.putU16Le(0x1B0 + 32, 0)
        bytes.putU32Le(0x1B0 + 36, 0x40000040u)

        bytes.putFixedString(0x300, "main", 8)
        bytes.putU32Le(0x300 + 8, 0x10u)
        bytes.putU16Le(0x300 + 12, 1)
        bytes.putU16Le(0x300 + 14, 0x20)
        bytes[0x300 + 16] = 2.toByte()
        bytes[0x300 + 17] = 0.toByte()

        bytes.putU32Le(0x312, 0u)
        bytes.putU32Le(0x312 + 4, 4u)
        bytes.putU32Le(0x312 + 8, 0x20u)
        bytes.putU16Le(0x312 + 12, 1)
        bytes.putU16Le(0x312 + 14, 0x20)
        bytes[0x312 + 16] = 2.toByte()
        bytes[0x312 + 17] = 1.toByte()

        bytes.putFixedString(0x336, "puts", 8)
        bytes.putU16Le(0x336 + 12, 0)
        bytes[0x336 + 16] = 2.toByte()
        bytes.putU32Le(0x348, 21u)
        bytes.putFixedString(0x34C, "very_long_symbol", 17)

        bytes.putU32Le(0x650, 0x2090u)
        bytes.putU32Le(0x654, 0u)
        bytes.putU32Le(0x658, 0u)
        bytes.putU32Le(0x65C, 0x2080u)
        bytes.putU32Le(0x660, 0x20A0u)
        bytes.putFixedString(0x680, "msvcrt.dll", 11)
        bytes.putU64Le(0x690, 0x20A8uL)
        bytes.putU64Le(0x698, 0uL)
        bytes.putU16Le(0x6A8, 7)
        bytes.putFixedString(0x6AA, "puts", 5)

        bytes.putU32Le(0x6C0, 0x1000u)
        bytes.putU32Le(0x6C4, 0x0Cu)
        bytes.putU16Le(0x6C8, 0xA018)
        bytes.putU16Le(0x6CA, 0)

        bytes.putU32Le(0x6D0 + 4, 0x66778899u)
        bytes.putU32Le(0x6D0 + 12, 2u)
        bytes.putU32Le(0x6D0 + 16, 0x30u)
        bytes.putU32Le(0x6D0 + 20, 0x22B0u)
        bytes.putU32Le(0x6D0 + 24, 0x7B0u)
        bytes.putFixedString(0x7B0, "RSDS", 4)
        bytes.putU32Le(0x7B4, 0x11223344u)
        bytes.putU16Le(0x7B8, 0x5566)
        bytes.putU16Le(0x7BA, 0x7788)
        byteArrayOf(0x99.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(), 0)
            .copyInto(bytes, destinationOffset = 0x7BC)
        bytes.putU32Le(0x7C4, 3u)
        bytes.putFixedString(0x7C8, "C:\\symbols\\sample.pdb", 22)

        bytes.putU32Le(0x700 + 12, 0x2150u)
        bytes.putU32Le(0x700 + 16, 1u)
        bytes.putU32Le(0x700 + 20, 1u)
        bytes.putU32Le(0x700 + 24, 1u)
        bytes.putU32Le(0x700 + 28, 0x2160u)
        bytes.putU32Le(0x700 + 32, 0x2170u)
        bytes.putU32Le(0x700 + 36, 0x2180u)
        bytes.putFixedString(0x750, "sample.dll", 11)
        bytes.putU32Le(0x760, 0x1010u)
        bytes.putU32Le(0x770, 0x2190u)
        bytes.putU16Le(0x780, 0)
        bytes.putFixedString(0x790, "exported_func", 14)

        return bytes
    }
}

private fun ByteArray.putFixedString(offset: Int, value: String, size: Int) {
    val bytes = value.encodeToByteArray()
    bytes.copyInto(this, destinationOffset = offset, endIndex = bytes.size.coerceAtMost(size))
}

private fun ByteArray.putU16Le(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

private fun ByteArray.putU32Le(offset: Int, value: UInt) {
    this[offset] = (value and 0xFFu).toByte()
    this[offset + 1] = ((value shr 8) and 0xFFu).toByte()
    this[offset + 2] = ((value shr 16) and 0xFFu).toByte()
    this[offset + 3] = ((value shr 24) and 0xFFu).toByte()
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

private fun coffArchiveBytes(vararg members: Pair<String, ByteArray>): ByteArray {
    val output = mutableListOf<Byte>()
    output.addAll("!<arch>\n".encodeToByteArray().asList())
    for ((name, content) in members) {
        val header = buildString {
            append(name.padEnd(16).take(16))
            append("0".padEnd(12))
            append("0".padEnd(6))
            append("0".padEnd(6))
            append("644".padEnd(8))
            append(content.size.toString().padEnd(10))
            append("`\n")
        }
        output.addAll(header.encodeToByteArray().asList())
        output.addAll(content.asList())
        if (content.size % 2 != 0) {
            output.add('\n'.code.toByte())
        }
    }
    return output.toByteArray()
}

private const val ARCHIVE_MAGIC_AND_HEADER_SIZE = 68
