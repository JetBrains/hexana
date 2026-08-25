package org.jetbrains.hexana.providers.elf

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParseResult
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeParserSeverity
import org.jetbrains.hexana.parsing.common.NativeUnresolvedRecord
import org.jetbrains.hexana.parsing.common.nativeParseFailure
import org.jetbrains.hexana.parsing.common.nativeParseSuccess

object ElfParser {
    fun parse(bytes: CommonByteBuffer): NativeParseResult<ElfFile> {
        val header = parseHeader(bytes)
            ?: return nativeParseFailure("elf.invalid_header", "Input does not contain a valid ELF header.")

        val sections = parseSectionHeaders(bytes, header)
            ?: return nativeParseFailure(
                code = "elf.invalid_section_headers",
                message = "ELF section header table is malformed or points outside the file.",
                range = header.fieldRanges.sectionHeaderOffset,
            )

        val programHeaders = parseProgramHeaders(bytes, header)
            ?: return nativeParseFailure(
                code = "elf.invalid_program_headers",
                message = "ELF program header table is malformed or points outside the file.",
                range = header.fieldRanges.programHeaderOffset,
            )

        val diagnostics = mutableListOf<NativeParserDiagnostic>()
        val symbols = parseSymbols(bytes, header, sections) ?: run {
            diagnostics.add(optionalTableDiagnostic("elf.invalid_symbols", "ELF symbol table could not be parsed."))
            emptyList()
        }
        val relocations = parseRelocations(bytes, header, sections, symbols) ?: run {
            diagnostics.add(optionalTableDiagnostic("elf.invalid_relocations", "ELF relocation table could not be parsed."))
            emptyList()
        }
        val dynamicEntries = parseDynamicEntries(bytes, header, sections) ?: run {
            diagnostics.add(optionalTableDiagnostic("elf.invalid_dynamic_entries", "ELF dynamic section could not be parsed."))
            emptyList()
        }

        val unresolvedRecords = buildList {
            sections
                .filter { it.type !in ElfSectionType.knownTypes }
                .mapTo(this) { section ->
                    NativeUnresolvedRecord(
                        kind = "elf.section",
                        id = section.type.toULong(),
                        name = section.name.takeIf { it.isNotEmpty() },
                        recordRange = section.headerRange,
                        contentRange = section.contentRange,
                        reason = "Unknown ELF section type ${section.type}.",
                    )
                }
            programHeaders
                .filter { it.type !in ElfProgramHeaderType.knownTypes }
                .mapTo(this) { programHeader ->
                    NativeUnresolvedRecord(
                        kind = "elf.program_header",
                        id = programHeader.type.toULong(),
                        recordRange = programHeader.headerRange,
                        contentRange = programHeader.fileRange,
                        reason = "Unknown ELF program header type ${programHeader.type}.",
                    )
                }
        }

        val file = ElfFile(
            header = header,
            sections = sections,
            programHeaders = programHeaders,
            symbols = symbols,
            relocations = relocations,
            dynamicEntries = dynamicEntries,
            unresolvedRecords = unresolvedRecords,
            diagnostics = diagnostics,
        )
        return nativeParseSuccess(file, diagnostics)
    }

    fun parseHeader(bytes: CommonByteBuffer): ElfHeader? {
        if (!bytes.hasPrefix(0x7F, 0x45, 0x4C, 0x46)) return null

        val wordSize = when (bytes.byteOrNull(4)) {
            1 -> NativeBinaryWordSize.Bits32
            2 -> NativeBinaryWordSize.Bits64
            else -> return null
        }
        val endian = when (bytes.byteOrNull(5)) {
            1 -> NativeBinaryEndian.Little
            2 -> NativeBinaryEndian.Big
            else -> return null
        }

        val minimumHeaderSize = when (wordSize) {
            NativeBinaryWordSize.Bits32 -> ELF32_HEADER_SIZE
            NativeBinaryWordSize.Bits64 -> ELF64_HEADER_SIZE
            NativeBinaryWordSize.Unknown -> return null
        }
        if (bytes.size < minimumHeaderSize) return null

        val headerSizeOffset = when (wordSize) {
            NativeBinaryWordSize.Bits32 -> 0x28
            NativeBinaryWordSize.Bits64 -> 0x34
            NativeBinaryWordSize.Unknown -> return null
        }
        val headerSize = bytes.u16OrNull(headerSizeOffset, endian) ?: return null
        if (headerSize < minimumHeaderSize || headerSize > bytes.size) return null

        return when (wordSize) {
            NativeBinaryWordSize.Bits32 -> parseElf32Header(bytes, endian, headerSize)
            NativeBinaryWordSize.Bits64 -> parseElf64Header(bytes, endian, headerSize)
            NativeBinaryWordSize.Unknown -> null
        }
    }

    fun parseSectionHeaders(bytes: CommonByteBuffer, header: ElfHeader): List<ElfSectionHeader>? {
        val layout = sectionHeaderLayout(bytes, header) ?: return null
        if (layout.sectionHeaderCount == 0) return emptyList()

        val tableSize = checkedMultiply(layout.entrySize, layout.sectionHeaderCount) ?: return null
        if (!bytes.containsRange(layout.tableOffset, tableSize)) return null

        val rawSections = buildList {
            for (index in 0 until layout.sectionHeaderCount) {
                val offset = layout.tableOffset + index * layout.entrySize
                add(parseSectionHeader(bytes, header, index, offset, layout.entrySize) ?: return null)
            }
        }

        val stringTable = rawSections.getOrNull(layout.sectionNameStringTableIndex)
            ?.contentRange
            ?.let { readStringTable(bytes, it) }

        return rawSections.map { section ->
            section.copy(name = readNullTerminatedString(stringTable, section.nameOffset))
        }
    }

    fun parseProgramHeaders(bytes: CommonByteBuffer, header: ElfHeader): List<ElfProgramHeader>? {
        val programHeaderCount = programHeaderCount(bytes, header) ?: return null
        if (programHeaderCount == 0) return emptyList()

        val tableOffset = header.programHeaderOffset.toBoundedInt() ?: return null
        val entrySize = header.programHeaderEntrySize
        val minimumEntrySize = when (header.wordSize) {
            NativeBinaryWordSize.Bits32 -> ELF32_PROGRAM_HEADER_SIZE
            NativeBinaryWordSize.Bits64 -> ELF64_PROGRAM_HEADER_SIZE
            NativeBinaryWordSize.Unknown -> return null
        }
        if (entrySize < minimumEntrySize) return null

        val tableSize = checkedMultiply(entrySize, programHeaderCount) ?: return null
        if (!bytes.containsRange(tableOffset, tableSize)) return null

        return buildList {
            for (index in 0 until programHeaderCount) {
                val offset = tableOffset + index * entrySize
                add(parseProgramHeader(bytes, header, index, offset, entrySize) ?: return null)
            }
        }
    }

    fun parseSymbols(
        bytes: CommonByteBuffer,
        header: ElfHeader,
        sections: List<ElfSectionHeader>,
    ): List<ElfSymbol>? {
        val symbols = mutableListOf<ElfSymbol>()
        for (section in sections) {
            if (section.type != SHT_SYMTAB && section.type != SHT_DYNSYM) continue
            val sectionSymbols = parseSymbolTable(bytes, header, sections, section) ?: return null
            symbols.addAll(sectionSymbols)
        }
        return symbols
    }

    fun parseRelocations(
        bytes: CommonByteBuffer,
        header: ElfHeader,
        sections: List<ElfSectionHeader>,
        symbols: List<ElfSymbol>,
    ): List<ElfRelocation>? {
        val relocations = mutableListOf<ElfRelocation>()
        for (section in sections) {
            if (section.type != SHT_REL && section.type != SHT_RELA) continue
            val sectionRelocations = parseRelocationSection(bytes, header, section, symbols) ?: return null
            relocations.addAll(sectionRelocations)
        }
        return relocations
    }

    fun parseDynamicEntries(
        bytes: CommonByteBuffer,
        header: ElfHeader,
        sections: List<ElfSectionHeader>,
    ): List<ElfDynamicEntry>? {
        val entries = mutableListOf<ElfDynamicEntry>()
        for (section in sections) {
            if (section.type != SHT_DYNAMIC) continue
            val sectionEntries = parseDynamicSection(bytes, header, sections, section) ?: return null
            entries.addAll(sectionEntries)
        }
        return entries
    }

    /**
     * Extracts the NT_GNU_BUILD_ID from a note section and returns its bytes as a
     * lowercase hex string, or null when no build-id is present.
     *
     * Scans the section named ".note.gnu.build-id" first, then falls back to any
     * SHT_NOTE section. Within each section, iterates note entries (namesz:u32,
     * descsz:u32, type:u32 in file endianness, name bytes padded to 4-byte
     * alignment, desc bytes padded to 4) and returns the desc of the first entry
     * where name is "GNU\0" (namesz == 4) and type == 3 (NT_GNU_BUILD_ID).
     */
    fun parseGnuBuildId(
        bytes: CommonByteBuffer,
        sections: List<ElfSectionHeader>,
        header: ElfHeader,
    ): String? {
        val noteSections = sections.filter { it.type == ElfSectionType.Note && it.contentRange != null }
        val preferred = noteSections.filter { it.name == ".note.gnu.build-id" }
        val toScan = preferred.ifEmpty { noteSections }
        for (section in toScan) {
            val result = parseGnuBuildIdFromSection(bytes, section, header) ?: continue
            return result
        }
        return null
    }

    private fun parseGnuBuildIdFromSection(
        bytes: CommonByteBuffer,
        section: ElfSectionHeader,
        header: ElfHeader,
    ): String? {
        val range = section.contentRange ?: return null
        var cursor = range.offset
        val end = range.offset + range.size
        while (cursor + 12 <= end) {
            val namesz = bytes.u32OrNull(cursor, header.endian)?.toInt() ?: return null
            val descsz = bytes.u32OrNull(cursor + 4, header.endian)?.toInt() ?: return null
            val type = bytes.u32OrNull(cursor + 8, header.endian)?.toInt() ?: return null
            cursor += 12
            val namePadded = (namesz + 3) and -4
            val descPadded = (descsz + 3) and -4
            if (cursor + namePadded + descPadded > end) break
            // NT_GNU_BUILD_ID: name "GNU\0" (namesz == 4), type == 3
            if (namesz == 4 && type == 3 &&
                bytes.byteOrNull(cursor) == 'G'.code &&
                bytes.byteOrNull(cursor + 1) == 'N'.code &&
                bytes.byteOrNull(cursor + 2) == 'U'.code &&
                bytes.byteOrNull(cursor + 3) == 0
            ) {
                val descOffset = cursor + namePadded
                val sb = StringBuilder(descsz * 2)
                for (i in 0 until descsz) {
                    val b = bytes.byteOrNull(descOffset + i) ?: return null
                    sb.append(b.toString(16).padStart(2, '0'))
                }
                return sb.toString()
            }
            cursor += namePadded + descPadded
        }
        return null
    }

    private fun parseElf32Header(
        bytes: CommonByteBuffer,
        endian: NativeBinaryEndian,
        headerSize: Int,
    ): ElfHeader? {
        return ElfHeader(
            wordSize = NativeBinaryWordSize.Bits32,
            endian = endian,
            osAbi = bytes.byteOrNull(7) ?: return null,
            abiVersion = bytes.byteOrNull(8) ?: return null,
            fileType = bytes.u16OrNull(0x10, endian) ?: return null,
            machine = bytes.u16OrNull(0x12, endian) ?: return null,
            version = bytes.u32OrNull(0x14, endian) ?: return null,
            entryPoint = bytes.u32OrNull(0x18, endian)?.toULong() ?: return null,
            programHeaderOffset = bytes.u32OrNull(0x1C, endian)?.toULong() ?: return null,
            sectionHeaderOffset = bytes.u32OrNull(0x20, endian)?.toULong() ?: return null,
            flags = bytes.u32OrNull(0x24, endian) ?: return null,
            headerSize = headerSize,
            programHeaderEntrySize = bytes.u16OrNull(0x2A, endian) ?: return null,
            programHeaderCount = bytes.u16OrNull(0x2C, endian) ?: return null,
            sectionHeaderEntrySize = bytes.u16OrNull(0x2E, endian) ?: return null,
            sectionHeaderCount = bytes.u16OrNull(0x30, endian) ?: return null,
            sectionNameStringTableIndex = bytes.u16OrNull(0x32, endian) ?: return null,
            identRange = NativeByteRange(0, ELF_IDENT_SIZE),
            headerRange = NativeByteRange(0, headerSize),
            fieldRanges = elf32HeaderFieldRanges(),
        )
    }

    private fun parseElf64Header(
        bytes: CommonByteBuffer,
        endian: NativeBinaryEndian,
        headerSize: Int,
    ): ElfHeader? {
        return ElfHeader(
            wordSize = NativeBinaryWordSize.Bits64,
            endian = endian,
            osAbi = bytes.byteOrNull(7) ?: return null,
            abiVersion = bytes.byteOrNull(8) ?: return null,
            fileType = bytes.u16OrNull(0x10, endian) ?: return null,
            machine = bytes.u16OrNull(0x12, endian) ?: return null,
            version = bytes.u32OrNull(0x14, endian) ?: return null,
            entryPoint = bytes.u64OrNull(0x18, endian) ?: return null,
            programHeaderOffset = bytes.u64OrNull(0x20, endian) ?: return null,
            sectionHeaderOffset = bytes.u64OrNull(0x28, endian) ?: return null,
            flags = bytes.u32OrNull(0x30, endian) ?: return null,
            headerSize = headerSize,
            programHeaderEntrySize = bytes.u16OrNull(0x36, endian) ?: return null,
            programHeaderCount = bytes.u16OrNull(0x38, endian) ?: return null,
            sectionHeaderEntrySize = bytes.u16OrNull(0x3A, endian) ?: return null,
            sectionHeaderCount = bytes.u16OrNull(0x3C, endian) ?: return null,
            sectionNameStringTableIndex = bytes.u16OrNull(0x3E, endian) ?: return null,
            identRange = NativeByteRange(0, ELF_IDENT_SIZE),
            headerRange = NativeByteRange(0, headerSize),
            fieldRanges = elf64HeaderFieldRanges(),
        )
    }

    private fun elf32HeaderFieldRanges(): ElfHeaderFieldRanges = ElfHeaderFieldRanges(
        ident = NativeByteRange(0, ELF_IDENT_SIZE),
        osAbi = NativeByteRange(0x07, 1),
        abiVersion = NativeByteRange(0x08, 1),
        fileType = NativeByteRange(0x10, 2),
        machine = NativeByteRange(0x12, 2),
        version = NativeByteRange(0x14, 4),
        entryPoint = NativeByteRange(0x18, 4),
        programHeaderOffset = NativeByteRange(0x1C, 4),
        sectionHeaderOffset = NativeByteRange(0x20, 4),
        flags = NativeByteRange(0x24, 4),
        headerSize = NativeByteRange(0x28, 2),
        programHeaderEntrySize = NativeByteRange(0x2A, 2),
        programHeaderCount = NativeByteRange(0x2C, 2),
        sectionHeaderEntrySize = NativeByteRange(0x2E, 2),
        sectionHeaderCount = NativeByteRange(0x30, 2),
        sectionNameStringTableIndex = NativeByteRange(0x32, 2),
    )

    private fun elf64HeaderFieldRanges(): ElfHeaderFieldRanges = ElfHeaderFieldRanges(
        ident = NativeByteRange(0, ELF_IDENT_SIZE),
        osAbi = NativeByteRange(0x07, 1),
        abiVersion = NativeByteRange(0x08, 1),
        fileType = NativeByteRange(0x10, 2),
        machine = NativeByteRange(0x12, 2),
        version = NativeByteRange(0x14, 4),
        entryPoint = NativeByteRange(0x18, 8),
        programHeaderOffset = NativeByteRange(0x20, 8),
        sectionHeaderOffset = NativeByteRange(0x28, 8),
        flags = NativeByteRange(0x30, 4),
        headerSize = NativeByteRange(0x34, 2),
        programHeaderEntrySize = NativeByteRange(0x36, 2),
        programHeaderCount = NativeByteRange(0x38, 2),
        sectionHeaderEntrySize = NativeByteRange(0x3A, 2),
        sectionHeaderCount = NativeByteRange(0x3C, 2),
        sectionNameStringTableIndex = NativeByteRange(0x3E, 2),
    )

    private fun parseSectionHeader(
        bytes: CommonByteBuffer,
        header: ElfHeader,
        index: Int,
        offset: Int,
        entrySize: Int,
    ): ElfSectionHeader? {
        val section = when (header.wordSize) {
            NativeBinaryWordSize.Bits32 -> ElfSectionHeader(
                index = index,
                nameOffset = bytes.u32OrNull(offset, header.endian) ?: return null,
                name = "",
                type = bytes.u32OrNull(offset + 0x04, header.endian) ?: return null,
                flags = bytes.u32OrNull(offset + 0x08, header.endian)?.toULong() ?: return null,
                address = bytes.u32OrNull(offset + 0x0C, header.endian)?.toULong() ?: return null,
                fileOffset = bytes.u32OrNull(offset + 0x10, header.endian)?.toULong() ?: return null,
                size = bytes.u32OrNull(offset + 0x14, header.endian)?.toULong() ?: return null,
                link = bytes.u32OrNull(offset + 0x18, header.endian) ?: return null,
                info = bytes.u32OrNull(offset + 0x1C, header.endian) ?: return null,
                addressAlignment = bytes.u32OrNull(offset + 0x20, header.endian)?.toULong() ?: return null,
                entrySize = bytes.u32OrNull(offset + 0x24, header.endian)?.toULong() ?: return null,
                headerRange = NativeByteRange(offset, entrySize),
                contentRange = null,
            )
            NativeBinaryWordSize.Bits64 -> ElfSectionHeader(
                index = index,
                nameOffset = bytes.u32OrNull(offset, header.endian) ?: return null,
                name = "",
                type = bytes.u32OrNull(offset + 0x04, header.endian) ?: return null,
                flags = bytes.u64OrNull(offset + 0x08, header.endian) ?: return null,
                address = bytes.u64OrNull(offset + 0x10, header.endian) ?: return null,
                fileOffset = bytes.u64OrNull(offset + 0x18, header.endian) ?: return null,
                size = bytes.u64OrNull(offset + 0x20, header.endian) ?: return null,
                link = bytes.u32OrNull(offset + 0x28, header.endian) ?: return null,
                info = bytes.u32OrNull(offset + 0x2C, header.endian) ?: return null,
                addressAlignment = bytes.u64OrNull(offset + 0x30, header.endian) ?: return null,
                entrySize = bytes.u64OrNull(offset + 0x38, header.endian) ?: return null,
                headerRange = NativeByteRange(offset, entrySize),
                contentRange = null,
            )
            NativeBinaryWordSize.Unknown -> return null
        }

        if (section.type == ElfSectionType.Null || section.type == ElfSectionType.NoBits) {
            return section
        }

        val contentOffset = section.fileOffset.toBoundedInt() ?: return null
        val contentSize = section.size.toBoundedInt() ?: return null
        val contentRange = if (contentSize == 0) {
            NativeByteRange(contentOffset.coerceAtMost(bytes.size), 0)
        } else {
            if (!bytes.containsRange(contentOffset, contentSize)) return null
            NativeByteRange(contentOffset, contentSize)
        }

        return section.copy(contentRange = contentRange)
    }

    private fun sectionHeaderLayout(bytes: CommonByteBuffer, header: ElfHeader): ElfSectionHeaderLayout? {
        val tableOffset = header.sectionHeaderOffset.toBoundedInt() ?: return null
        val entrySize = header.sectionHeaderEntrySize
        if (header.sectionHeaderOffset == 0uL && header.sectionHeaderCount == 0) {
            return ElfSectionHeaderLayout(
                tableOffset = tableOffset,
                entrySize = entrySize,
                sectionHeaderCount = 0,
                sectionNameStringTableIndex = 0,
            )
        }
        val minimumEntrySize = minimumSectionHeaderEntrySize(header.wordSize) ?: return null
        if (entrySize < minimumEntrySize) return null

        val needsSectionZero = header.sectionHeaderCount == 0 || header.sectionNameStringTableIndex == SHN_XINDEX
        val sectionZero = if (needsSectionZero && bytes.containsRange(tableOffset, entrySize)) {
            parseSectionHeader(bytes, header, 0, tableOffset, entrySize) ?: return null
        } else {
            null
        }

        val sectionHeaderCount = when {
            header.sectionHeaderCount != 0 -> header.sectionHeaderCount
            header.sectionHeaderOffset == 0uL || !bytes.containsRange(tableOffset, entrySize) -> 0
            else -> sectionZero?.size?.toBoundedInt() ?: return null
        }
        val sectionNameStringTableIndex = if (header.sectionNameStringTableIndex == SHN_XINDEX) {
            sectionZero?.link?.toBoundedInt() ?: return null
        } else {
            header.sectionNameStringTableIndex
        }

        return ElfSectionHeaderLayout(
            tableOffset = tableOffset,
            entrySize = entrySize,
            sectionHeaderCount = sectionHeaderCount,
            sectionNameStringTableIndex = sectionNameStringTableIndex,
        )
    }

    private fun programHeaderCount(bytes: CommonByteBuffer, header: ElfHeader): Int? {
        if (header.programHeaderCount != PN_XNUM) return header.programHeaderCount
        val tableOffset = header.sectionHeaderOffset.toBoundedInt() ?: return null
        val entrySize = header.sectionHeaderEntrySize
        val minimumEntrySize = minimumSectionHeaderEntrySize(header.wordSize) ?: return null
        if (entrySize < minimumEntrySize || !bytes.containsRange(tableOffset, entrySize)) return null
        val sectionZero = parseSectionHeader(bytes, header, 0, tableOffset, entrySize) ?: return null
        return sectionZero.info.toBoundedInt()
    }

    private fun minimumSectionHeaderEntrySize(wordSize: NativeBinaryWordSize): Int? =
        when (wordSize) {
            NativeBinaryWordSize.Bits32 -> ELF32_SECTION_HEADER_SIZE
            NativeBinaryWordSize.Bits64 -> ELF64_SECTION_HEADER_SIZE
            NativeBinaryWordSize.Unknown -> null
        }

    private fun readStringTable(bytes: CommonByteBuffer, range: NativeByteRange): ByteArray {
        val result = ByteArray(range.size)
        for (index in result.indices) {
            result[index] = bytes[range.offset + index]
        }
        return result
    }

    private fun readNullTerminatedString(stringTable: ByteArray?, offset: UInt): String {
        stringTable ?: return ""
        val start = offset.toInt()
        if (start !in stringTable.indices) return ""

        var end = start
        while (end < stringTable.size && stringTable[end] != 0.toByte()) {
            end++
        }
        return stringTable.decodeToString(start, end)
    }

    private fun parseProgramHeader(
        bytes: CommonByteBuffer,
        header: ElfHeader,
        index: Int,
        offset: Int,
        entrySize: Int,
    ): ElfProgramHeader? {
        val programHeader = when (header.wordSize) {
            NativeBinaryWordSize.Bits32 -> ElfProgramHeader(
                index = index,
                type = bytes.u32OrNull(offset, header.endian) ?: return null,
                fileOffset = bytes.u32OrNull(offset + 0x04, header.endian)?.toULong() ?: return null,
                virtualAddress = bytes.u32OrNull(offset + 0x08, header.endian)?.toULong() ?: return null,
                physicalAddress = bytes.u32OrNull(offset + 0x0C, header.endian)?.toULong() ?: return null,
                fileSize = bytes.u32OrNull(offset + 0x10, header.endian)?.toULong() ?: return null,
                memorySize = bytes.u32OrNull(offset + 0x14, header.endian)?.toULong() ?: return null,
                flags = bytes.u32OrNull(offset + 0x18, header.endian) ?: return null,
                alignment = bytes.u32OrNull(offset + 0x1C, header.endian)?.toULong() ?: return null,
                headerRange = NativeByteRange(offset, entrySize),
                fileRange = null,
            )
            NativeBinaryWordSize.Bits64 -> ElfProgramHeader(
                index = index,
                type = bytes.u32OrNull(offset, header.endian) ?: return null,
                flags = bytes.u32OrNull(offset + 0x04, header.endian) ?: return null,
                fileOffset = bytes.u64OrNull(offset + 0x08, header.endian) ?: return null,
                virtualAddress = bytes.u64OrNull(offset + 0x10, header.endian) ?: return null,
                physicalAddress = bytes.u64OrNull(offset + 0x18, header.endian) ?: return null,
                fileSize = bytes.u64OrNull(offset + 0x20, header.endian) ?: return null,
                memorySize = bytes.u64OrNull(offset + 0x28, header.endian) ?: return null,
                alignment = bytes.u64OrNull(offset + 0x30, header.endian) ?: return null,
                headerRange = NativeByteRange(offset, entrySize),
                fileRange = null,
            )
            NativeBinaryWordSize.Unknown -> return null
        }

        val fileOffset = programHeader.fileOffset.toBoundedInt() ?: return null
        val fileSize = programHeader.fileSize.toBoundedInt() ?: return null
        val fileRange = if (fileSize == 0) {
            NativeByteRange(fileOffset.coerceAtMost(bytes.size), 0)
        } else {
            if (!bytes.containsRange(fileOffset, fileSize)) return null
            NativeByteRange(fileOffset, fileSize)
        }

        return programHeader.copy(fileRange = fileRange)
    }

    private fun parseSymbolTable(
        bytes: CommonByteBuffer,
        header: ElfHeader,
        sections: List<ElfSectionHeader>,
        symbolSection: ElfSectionHeader,
    ): List<ElfSymbol>? {
        val symbolRange = symbolSection.contentRange ?: return emptyList()
        val entrySize = symbolSection.entrySize.toBoundedInt()
            ?: defaultSymbolEntrySize(header.wordSize)
            ?: return null
        val minimumEntrySize = defaultSymbolEntrySize(header.wordSize) ?: return null
        if (entrySize < minimumEntrySize) return null
        if (symbolRange.size % entrySize != 0) return null

        val stringTable = sections.getOrNull(symbolSection.link.toInt())
            ?.contentRange
            ?.let { readStringTable(bytes, it) }

        return buildList {
            val symbolCount = symbolRange.size / entrySize
            for (index in 0 until symbolCount) {
                val offset = symbolRange.offset + index * entrySize
                add(parseSymbol(bytes, header, symbolSection.index, index, offset, entrySize, stringTable) ?: return null)
            }
        }
    }

    private fun parseSymbol(
        bytes: CommonByteBuffer,
        header: ElfHeader,
        tableSectionIndex: Int,
        index: Int,
        offset: Int,
        entrySize: Int,
        stringTable: ByteArray?,
    ): ElfSymbol? {
        val nameOffset: UInt
        val value: ULong
        val size: ULong
        val info: Int
        val other: Int
        val sectionIndex: Int

        when (header.wordSize) {
            NativeBinaryWordSize.Bits32 -> {
                nameOffset = bytes.u32OrNull(offset, header.endian) ?: return null
                value = bytes.u32OrNull(offset + 0x04, header.endian)?.toULong() ?: return null
                size = bytes.u32OrNull(offset + 0x08, header.endian)?.toULong() ?: return null
                info = bytes.byteOrNull(offset + 0x0C) ?: return null
                other = bytes.byteOrNull(offset + 0x0D) ?: return null
                sectionIndex = bytes.u16OrNull(offset + 0x0E, header.endian) ?: return null
            }
            NativeBinaryWordSize.Bits64 -> {
                nameOffset = bytes.u32OrNull(offset, header.endian) ?: return null
                info = bytes.byteOrNull(offset + 0x04) ?: return null
                other = bytes.byteOrNull(offset + 0x05) ?: return null
                sectionIndex = bytes.u16OrNull(offset + 0x06, header.endian) ?: return null
                value = bytes.u64OrNull(offset + 0x08, header.endian) ?: return null
                size = bytes.u64OrNull(offset + 0x10, header.endian) ?: return null
            }
            NativeBinaryWordSize.Unknown -> return null
        }

        return ElfSymbol(
            index = index,
            tableSectionIndex = tableSectionIndex,
            nameOffset = nameOffset,
            name = readNullTerminatedString(stringTable, nameOffset),
            info = info,
            binding = info ushr 4,
            type = info and 0x0F,
            other = other,
            visibility = other and 0x03,
            sectionIndex = sectionIndex,
            value = value,
            size = size,
            recordRange = NativeByteRange(offset, entrySize),
        )
    }

    private fun defaultSymbolEntrySize(wordSize: NativeBinaryWordSize): Int? =
        when (wordSize) {
            NativeBinaryWordSize.Bits32 -> ELF32_SYMBOL_SIZE
            NativeBinaryWordSize.Bits64 -> ELF64_SYMBOL_SIZE
            NativeBinaryWordSize.Unknown -> null
        }

    private fun parseRelocationSection(
        bytes: CommonByteBuffer,
        header: ElfHeader,
        section: ElfSectionHeader,
        symbols: List<ElfSymbol>,
    ): List<ElfRelocation>? {
        val relocationRange = section.contentRange ?: return emptyList()
        val entrySize = section.entrySize.toBoundedInt()
            ?: defaultRelocationEntrySize(header.wordSize, section.type)
            ?: return null
        val minimumEntrySize = defaultRelocationEntrySize(header.wordSize, section.type) ?: return null
        if (entrySize < minimumEntrySize) return null
        if (relocationRange.size % entrySize != 0) return null

        val symbolsByIndex = symbols
            .filter { it.tableSectionIndex == section.link.toInt() }
            .associateBy { it.index }

        return buildList {
            val relocationCount = relocationRange.size / entrySize
            for (index in 0 until relocationCount) {
                val offset = relocationRange.offset + index * entrySize
                add(parseRelocation(bytes, header, section, index, offset, entrySize, symbolsByIndex) ?: return null)
            }
        }
    }

    private fun parseRelocation(
        bytes: CommonByteBuffer,
        header: ElfHeader,
        section: ElfSectionHeader,
        index: Int,
        offset: Int,
        entrySize: Int,
        symbolsByIndex: Map<Int, ElfSymbol>,
    ): ElfRelocation? {
        val relocationOffset: ULong
        val info: ULong
        val addend: Long?

        when (header.wordSize) {
            NativeBinaryWordSize.Bits32 -> {
                relocationOffset = bytes.u32OrNull(offset, header.endian)?.toULong() ?: return null
                val info32 = bytes.u32OrNull(offset + 0x04, header.endian) ?: return null
                info = info32.toULong()
                addend = if (section.type == SHT_RELA) {
                    bytes.u32OrNull(offset + 0x08, header.endian)?.toInt()?.toLong() ?: return null
                } else {
                    null
                }
            }
            NativeBinaryWordSize.Bits64 -> {
                relocationOffset = bytes.u64OrNull(offset, header.endian) ?: return null
                info = bytes.u64OrNull(offset + 0x08, header.endian) ?: return null
                addend = if (section.type == SHT_RELA) {
                    bytes.u64OrNull(offset + 0x10, header.endian)?.toLong() ?: return null
                } else {
                    null
                }
            }
            NativeBinaryWordSize.Unknown -> return null
        }

        val symbolIndex = relocationSymbolIndex(header.wordSize, info)
        val type = relocationType(header.wordSize, info)

        return ElfRelocation(
            index = index,
            relocationSectionIndex = section.index,
            targetSectionIndex = section.info.toInt(),
            offset = relocationOffset,
            info = info,
            symbolIndex = symbolIndex,
            type = type,
            addend = addend,
            symbol = symbolsByIndex[symbolIndex.toInt()],
            recordRange = NativeByteRange(offset, entrySize),
        )
    }

    private fun relocationSymbolIndex(wordSize: NativeBinaryWordSize, info: ULong): UInt =
        when (wordSize) {
            NativeBinaryWordSize.Bits32 -> (info.toUInt() shr 8)
            NativeBinaryWordSize.Bits64 -> (info shr 32).toUInt()
            NativeBinaryWordSize.Unknown -> 0u
        }

    private fun relocationType(wordSize: NativeBinaryWordSize, info: ULong): UInt =
        when (wordSize) {
            NativeBinaryWordSize.Bits32 -> info.toUInt() and 0xFFu
            NativeBinaryWordSize.Bits64 -> info.toUInt()
            NativeBinaryWordSize.Unknown -> 0u
        }

    private fun defaultRelocationEntrySize(wordSize: NativeBinaryWordSize, sectionType: UInt): Int? =
        when (wordSize) {
            NativeBinaryWordSize.Bits32 -> if (sectionType == SHT_RELA) ELF32_RELA_SIZE else ELF32_REL_SIZE
            NativeBinaryWordSize.Bits64 -> if (sectionType == SHT_RELA) ELF64_RELA_SIZE else ELF64_REL_SIZE
            NativeBinaryWordSize.Unknown -> null
        }

    private fun parseDynamicSection(
        bytes: CommonByteBuffer,
        header: ElfHeader,
        sections: List<ElfSectionHeader>,
        section: ElfSectionHeader,
    ): List<ElfDynamicEntry>? {
        val dynamicRange = section.contentRange ?: return emptyList()
        val entrySize = section.entrySize.toBoundedInt()
            ?: defaultDynamicEntrySize(header.wordSize)
            ?: return null
        val minimumEntrySize = defaultDynamicEntrySize(header.wordSize) ?: return null
        if (entrySize < minimumEntrySize) return null
        if (dynamicRange.size % entrySize != 0) return null

        val stringTable = sections.getOrNull(section.link.toInt())
            ?.contentRange
            ?.let { readStringTable(bytes, it) }

        return buildList {
            val entryCount = dynamicRange.size / entrySize
            for (index in 0 until entryCount) {
                val offset = dynamicRange.offset + index * entrySize
                add(parseDynamicEntry(bytes, header, section, index, offset, entrySize, stringTable) ?: return null)
            }
        }
    }

    private fun parseDynamicEntry(
        bytes: CommonByteBuffer,
        header: ElfHeader,
        section: ElfSectionHeader,
        index: Int,
        offset: Int,
        entrySize: Int,
        stringTable: ByteArray?,
    ): ElfDynamicEntry? {
        val tag: Long
        val value: ULong

        when (header.wordSize) {
            NativeBinaryWordSize.Bits32 -> {
                tag = bytes.u32OrNull(offset, header.endian)?.toInt()?.toLong() ?: return null
                value = bytes.u32OrNull(offset + 0x04, header.endian)?.toULong() ?: return null
            }
            NativeBinaryWordSize.Bits64 -> {
                tag = bytes.u64OrNull(offset, header.endian)?.toLong() ?: return null
                value = bytes.u64OrNull(offset + 0x08, header.endian) ?: return null
            }
            NativeBinaryWordSize.Unknown -> return null
        }

        val neededLibrary = if (tag == DT_NEEDED) {
            if (stringTable == null || value > Int.MAX_VALUE.toULong()) return null
            val stringOffset = value.toInt()
            if (stringOffset !in stringTable.indices) return null
            readNullTerminatedString(stringTable, value.toUInt())
        } else {
            null
        }

        return ElfDynamicEntry(
            index = index,
            dynamicSectionIndex = section.index,
            tag = tag,
            value = value,
            neededLibrary = neededLibrary,
            recordRange = NativeByteRange(offset, entrySize),
        )
    }

    private fun defaultDynamicEntrySize(wordSize: NativeBinaryWordSize): Int? =
        when (wordSize) {
            NativeBinaryWordSize.Bits32 -> ELF32_DYNAMIC_SIZE
            NativeBinaryWordSize.Bits64 -> ELF64_DYNAMIC_SIZE
            NativeBinaryWordSize.Unknown -> null
        }

    private const val ELF_IDENT_SIZE = 16
    private const val ELF32_HEADER_SIZE = 52
    private const val ELF64_HEADER_SIZE = 64
    private const val ELF32_PROGRAM_HEADER_SIZE = 32
    private const val ELF64_PROGRAM_HEADER_SIZE = 56
    private const val ELF32_SECTION_HEADER_SIZE = 40
    private const val ELF64_SECTION_HEADER_SIZE = 64
    private const val ELF32_SYMBOL_SIZE = 16
    private const val ELF64_SYMBOL_SIZE = 24
    private const val SHT_SYMTAB = 2u
    private const val SHT_RELA = 4u
    private const val SHT_DYNAMIC = 6u
    private const val SHT_REL = 9u
    private const val SHT_DYNSYM = 11u
    private const val ELF32_REL_SIZE = 8
    private const val ELF32_RELA_SIZE = 12
    private const val ELF64_REL_SIZE = 16
    private const val ELF64_RELA_SIZE = 24
    private const val ELF32_DYNAMIC_SIZE = 8
    private const val ELF64_DYNAMIC_SIZE = 16
    private const val DT_NEEDED = 1L
    private const val PN_XNUM = 0xFFFF
    private const val SHN_XINDEX = 0xFFFF

    private data class ElfSectionHeaderLayout(
        val tableOffset: Int,
        val entrySize: Int,
        val sectionHeaderCount: Int,
        val sectionNameStringTableIndex: Int,
    )
}

private fun optionalTableDiagnostic(code: String, message: String): NativeParserDiagnostic =
    NativeParserDiagnostic(
        code = code,
        message = message,
        severity = NativeParserSeverity.Warning,
    )

private fun CommonByteBuffer.hasPrefix(vararg expected: Int): Boolean {
    if (expected.size > size) return false
    for (index in expected.indices) {
        if (byteOrNull(index) != expected[index]) return false
    }
    return true
}

private fun CommonByteBuffer.byteOrNull(offset: Int): Int? =
    if (offset in 0 until size) get(offset).toInt() and 0xFF else null

private fun CommonByteBuffer.u16OrNull(offset: Int, endian: NativeBinaryEndian): Int? {
    val b0 = byteOrNull(offset) ?: return null
    val b1 = byteOrNull(offset + 1) ?: return null
    return when (endian) {
        NativeBinaryEndian.Little -> b0 or (b1 shl 8)
        NativeBinaryEndian.Big -> (b0 shl 8) or b1
        NativeBinaryEndian.Unknown -> null
    }
}

private fun CommonByteBuffer.u32OrNull(offset: Int, endian: NativeBinaryEndian): UInt? {
    val b0 = byteOrNull(offset) ?: return null
    val b1 = byteOrNull(offset + 1) ?: return null
    val b2 = byteOrNull(offset + 2) ?: return null
    val b3 = byteOrNull(offset + 3) ?: return null
    return when (endian) {
        NativeBinaryEndian.Little -> (
            b0.toUInt() or
                (b1.toUInt() shl 8) or
                (b2.toUInt() shl 16) or
                (b3.toUInt() shl 24)
            )
        NativeBinaryEndian.Big -> (
            (b0.toUInt() shl 24) or
                (b1.toUInt() shl 16) or
                (b2.toUInt() shl 8) or
                b3.toUInt()
            )
        NativeBinaryEndian.Unknown -> null
    }
}

private fun CommonByteBuffer.u64OrNull(offset: Int, endian: NativeBinaryEndian): ULong? {
    val low = u32OrNull(offset, endian) ?: return null
    val high = u32OrNull(offset + 4, endian) ?: return null
    return when (endian) {
        NativeBinaryEndian.Little -> low.toULong() or (high.toULong() shl 32)
        NativeBinaryEndian.Big -> (low.toULong() shl 32) or high.toULong()
        NativeBinaryEndian.Unknown -> null
    }
}

private fun ULong.toBoundedInt(): Int? =
    if (this <= Int.MAX_VALUE.toULong()) toInt() else null

private fun UInt.toBoundedInt(): Int? =
    if (this <= Int.MAX_VALUE.toUInt()) toInt() else null

private fun checkedMultiply(left: Int, right: Int): Int? {
    if (left < 0 || right < 0) return null
    if (left != 0 && right > Int.MAX_VALUE / left) return null
    return left * right
}

private fun CommonByteBuffer.containsRange(offset: Int, size: Int): Boolean {
    if (offset < 0 || size < 0) return false
    if (offset > this.size) return false
    if (size > this.size - offset) return false
    return true
}
