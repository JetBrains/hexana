package org.jetbrains.hexana.providers.macho

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

object MachOParser {
    fun parse(bytes: CommonByteBuffer): NativeParseResult<MachOFile> {
        val fatHeader = parseFatHeader(bytes)
        if (fatHeader != null) {
            val architectures = parseFatArchitectures(bytes, fatHeader)
                ?: return nativeParseFailure(
                    code = "macho.invalid_fat_architectures",
                    message = "Mach-O universal binary architecture table is malformed or points outside the file.",
                    range = fatHeader.archTableRange,
                )
            val file = MachOFatFile(
                header = fatHeader,
                architectures = architectures,
                unresolvedRecords = emptyList(),
                diagnostics = emptyList(),
            )
            return nativeParseSuccess(file)
        }

        val header = parseHeader(bytes)
            ?: return nativeParseFailure("macho.invalid_header", "Input does not contain a valid Mach-O header.")

        val loadCommands = parseLoadCommands(bytes, header)
            ?: return nativeParseFailure(
                code = "macho.invalid_load_commands",
                message = "Mach-O load command table is malformed or points outside the file.",
                range = header.loadCommandsRange,
            )

        val diagnostics = mutableListOf<NativeParserDiagnostic>()
        val segments = parseSegments(bytes, header, loadCommands) ?: run {
            diagnostics.add(optionalCommandDiagnostic("macho.invalid_segments", "Mach-O segment load commands could not be parsed."))
            emptyList()
        }
        val symtab = parseSymtab(bytes, header, loadCommands)
        val dysymtab = parseDysymtab(bytes, header, loadCommands)
        val symbols = symtab?.let { parseSymbols(bytes, header, it) } ?: emptyList()
        if (symtab != null && symbols.isEmpty() && symtab.symbolCount != 0u) {
            diagnostics.add(optionalCommandDiagnostic("macho.invalid_symbols", "Mach-O symbol table could not be parsed."))
        }
        val dylibs = parseDylibs(bytes, header, loadCommands) ?: run {
            diagnostics.add(optionalCommandDiagnostic("macho.invalid_dylibs", "Mach-O dylib load commands could not be parsed."))
            emptyList()
        }
        val relocations = parseRelocations(bytes, header, segments.flatMap { it.sections }, symbols) ?: run {
            diagnostics.add(optionalCommandDiagnostic("macho.invalid_relocations", "Mach-O relocations could not be parsed."))
            emptyList()
        }

        val unresolvedRecords = loadCommands
            .filter { it.type !in MachOLoadCommandType.knownTypes }
            .map { command ->
                NativeUnresolvedRecord(
                    kind = "macho.load_command",
                    id = command.type.toULong(),
                    recordRange = command.range,
                    contentRange = command.range,
                    reason = "Unknown Mach-O load command type ${command.type}.",
                )
            }

        val file = MachOThinFile(
            header = header,
            loadCommands = loadCommands,
            segments = segments,
            uuid = parseUuid(bytes, loadCommands),
            symtab = symtab,
            dysymtab = dysymtab,
            symbols = symbols,
            dylibs = dylibs,
            relocations = relocations,
            unresolvedRecords = unresolvedRecords,
            diagnostics = diagnostics,
        )
        return nativeParseSuccess(file, diagnostics)
    }

    fun parseFatHeader(bytes: CommonByteBuffer): MachOFatHeader? {
        if (bytes.size < FAT_HEADER_SIZE) return null

        val magicBe = bytes.u32OrNull(0, NativeBinaryEndian.Big) ?: return null
        val wordSize: NativeBinaryWordSize
        val endian: NativeBinaryEndian
        when (magicBe) {
            FAT_MAGIC -> {
                wordSize = NativeBinaryWordSize.Bits32
                endian = NativeBinaryEndian.Big
            }
            FAT_CIGAM -> {
                wordSize = NativeBinaryWordSize.Bits32
                endian = NativeBinaryEndian.Little
            }
            FAT_MAGIC_64 -> {
                wordSize = NativeBinaryWordSize.Bits64
                endian = NativeBinaryEndian.Big
            }
            FAT_CIGAM_64 -> {
                wordSize = NativeBinaryWordSize.Bits64
                endian = NativeBinaryEndian.Little
            }
            else -> return null
        }

        val archCount = bytes.u32OrNull(4, endian) ?: return null
        val archCountInt = archCount.toBoundedInt() ?: return null
        val archSize = if (wordSize == NativeBinaryWordSize.Bits64) FAT_ARCH_64_SIZE else FAT_ARCH_32_SIZE
        val archTableSize = checkedMultiply(archCountInt, archSize) ?: return null
        if (!bytes.containsRange(FAT_HEADER_SIZE, archTableSize)) return null

        return MachOFatHeader(
            wordSize = wordSize,
            endian = endian,
            archCount = archCount,
            headerRange = NativeByteRange(0, FAT_HEADER_SIZE),
            archTableRange = NativeByteRange(FAT_HEADER_SIZE, archTableSize),
        )
    }

    fun parseFatArchitectures(bytes: CommonByteBuffer, header: MachOFatHeader): List<MachOFatArch>? {
        val result = mutableListOf<MachOFatArch>()
        val count = header.archCount.toBoundedInt() ?: return null
        val archSize = if (header.wordSize == NativeBinaryWordSize.Bits64) FAT_ARCH_64_SIZE else FAT_ARCH_32_SIZE
        val sliceRanges = mutableListOf<NativeByteRange>()

        repeat(count) { index ->
            val offset = header.archTableRange.offset + index * archSize
            if (offset + archSize > header.archTableRange.offset + header.archTableRange.size) return null

            val sliceOffset: ULong
            val sliceSize: ULong
            val alignment: UInt
            val reserved: UInt?
            if (header.wordSize == NativeBinaryWordSize.Bits64) {
                sliceOffset = bytes.u64OrNull(offset + 8, header.endian) ?: return null
                sliceSize = bytes.u64OrNull(offset + 16, header.endian) ?: return null
                alignment = bytes.u32OrNull(offset + 24, header.endian) ?: return null
                reserved = bytes.u32OrNull(offset + 28, header.endian) ?: return null
            } else {
                sliceOffset = (bytes.u32OrNull(offset + 8, header.endian) ?: return null).toULong()
                sliceSize = (bytes.u32OrNull(offset + 12, header.endian) ?: return null).toULong()
                alignment = bytes.u32OrNull(offset + 16, header.endian) ?: return null
                reserved = null
            }

            val sliceRange = fileRange(bytes, sliceOffset, sliceSize) ?: return null
            if (sliceRanges.any { it.overlaps(sliceRange) }) return null
            sliceRanges.add(sliceRange)

            result.add(
                MachOFatArch(
                    index = index,
                    cpuType = bytes.i32OrNull(offset, header.endian) ?: return null,
                    cpuSubtype = bytes.i32OrNull(offset + 4, header.endian) ?: return null,
                    offset = sliceOffset,
                    size = sliceSize,
                    alignment = alignment,
                    reserved = reserved,
                    archRange = NativeByteRange(offset, archSize),
                    sliceRange = sliceRange,
                )
            )
        }

        return result
    }

    fun parseHeader(bytes: CommonByteBuffer): MachOHeader? {
        if (bytes.size < MACH_HEADER_32_SIZE) return null

        val magicBe = bytes.u32OrNull(0, NativeBinaryEndian.Big) ?: return null
        val wordSize: NativeBinaryWordSize
        val endian: NativeBinaryEndian
        val headerSize: Int
        val magic: UInt
        when (magicBe) {
            MH_MAGIC -> {
                wordSize = NativeBinaryWordSize.Bits32
                endian = NativeBinaryEndian.Big
                headerSize = MACH_HEADER_32_SIZE
                magic = magicBe
            }
            MH_CIGAM -> {
                wordSize = NativeBinaryWordSize.Bits32
                endian = NativeBinaryEndian.Little
                headerSize = MACH_HEADER_32_SIZE
                magic = MH_MAGIC
            }
            MH_MAGIC_64 -> {
                wordSize = NativeBinaryWordSize.Bits64
                endian = NativeBinaryEndian.Big
                headerSize = MACH_HEADER_64_SIZE
                magic = magicBe
            }
            MH_CIGAM_64 -> {
                wordSize = NativeBinaryWordSize.Bits64
                endian = NativeBinaryEndian.Little
                headerSize = MACH_HEADER_64_SIZE
                magic = MH_MAGIC_64
            }
            else -> return null
        }
        if (bytes.size < headerSize) return null

        val loadCommandCount = bytes.u32OrNull(16, endian) ?: return null
        val loadCommandsSize = bytes.u32OrNull(20, endian) ?: return null
        val loadCommandsSizeInt = loadCommandsSize.toBoundedInt() ?: return null
        if (!bytes.containsRange(headerSize, loadCommandsSizeInt)) return null

        return MachOHeader(
            wordSize = wordSize,
            endian = endian,
            magic = magic,
            cpuType = bytes.i32OrNull(4, endian) ?: return null,
            cpuSubtype = bytes.i32OrNull(8, endian) ?: return null,
            fileType = bytes.u32OrNull(12, endian) ?: return null,
            loadCommandCount = loadCommandCount,
            loadCommandsSize = loadCommandsSize,
            flags = bytes.u32OrNull(24, endian) ?: return null,
            reserved = if (wordSize == NativeBinaryWordSize.Bits64) bytes.u32OrNull(28, endian) else null,
            headerRange = NativeByteRange(0, headerSize),
            loadCommandsRange = NativeByteRange(headerSize, loadCommandsSizeInt),
        )
    }

    fun parseLoadCommands(bytes: CommonByteBuffer, header: MachOHeader): List<MachOLoadCommand>? {
        val result = mutableListOf<MachOLoadCommand>()
        var offset = header.loadCommandsRange.offset
        val end = header.loadCommandsRange.offset + header.loadCommandsRange.size
        val count = header.loadCommandCount.toBoundedInt() ?: return null
        repeat(count) { index ->
            if (offset + LOAD_COMMAND_SIZE > end) return null
            val type = bytes.u32OrNull(offset, header.endian) ?: return null
            val size = bytes.u32OrNull(offset + 4, header.endian) ?: return null
            val sizeInt = size.toBoundedInt() ?: return null
            if (sizeInt < LOAD_COMMAND_SIZE || offset + sizeInt > end) return null
            result.add(
                MachOLoadCommand(
                    index = index,
                    type = type,
                    size = size,
                    range = NativeByteRange(offset, sizeInt),
                )
            )
            offset += sizeInt
        }
        if (offset != end) return null
        return result
    }

    fun parseSegments(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        loadCommands: List<MachOLoadCommand>,
    ): List<MachOSegment>? {
        val segments = mutableListOf<MachOSegment>()
        for (command in loadCommands) {
            when (command.type) {
                LC_SEGMENT -> segments.add(parseSegment32(bytes, header, command, segments.size) ?: return null)
                LC_SEGMENT_64 -> segments.add(parseSegment64(bytes, header, command, segments.size) ?: return null)
            }
        }
        return segments
    }

    fun parseUuid(bytes: CommonByteBuffer, loadCommands: List<MachOLoadCommand>): MachOUuid? {
        val command = loadCommands.firstOrNull { it.type == LC_UUID } ?: return null
        if (command.range.size < 24) return null
        val uuidOffset = command.range.offset + 8
        val raw = (0 until 16).map { bytes.byteOrNull(uuidOffset + it) ?: return null }
        val text = buildString {
            raw.forEachIndexed { index, byte ->
                if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
                append(byte.toString(16).padStart(2, '0'))
            }
        }.uppercase()
        return MachOUuid(
            value = text,
            commandRange = command.range,
            uuidRange = NativeByteRange(uuidOffset, 16),
        )
    }

    fun parseSymtab(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        loadCommands: List<MachOLoadCommand>,
    ): MachOSymtabCommand? {
        val command = loadCommands.firstOrNull { it.type == LC_SYMTAB } ?: return null
        if (command.range.size < SYMTAB_COMMAND_SIZE) return null
        val offset = command.range.offset
        val symbolTableOffset = bytes.u32OrNull(offset + 8, header.endian) ?: return null
        val symbolCount = bytes.u32OrNull(offset + 12, header.endian) ?: return null
        val stringTableOffset = bytes.u32OrNull(offset + 16, header.endian) ?: return null
        val stringTableSize = bytes.u32OrNull(offset + 20, header.endian) ?: return null
        val symbolEntrySize = when (header.wordSize) {
            NativeBinaryWordSize.Bits32 -> NLIST_32_SIZE
            NativeBinaryWordSize.Bits64 -> NLIST_64_SIZE
            NativeBinaryWordSize.Unknown -> return null
        }

        return MachOSymtabCommand(
            symbolTableOffset = symbolTableOffset,
            symbolCount = symbolCount,
            stringTableOffset = stringTableOffset,
            stringTableSize = stringTableSize,
            commandRange = command.range,
            symbolTableRange = fileRange(
                bytes,
                symbolTableOffset.toULong(),
                symbolCount.toULong() * symbolEntrySize.toULong(),
            ),
            stringTableRange = fileRange(bytes, stringTableOffset.toULong(), stringTableSize.toULong()),
        )
    }

    fun parseDysymtab(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        loadCommands: List<MachOLoadCommand>,
    ): MachODysymtabCommand? {
        val command = loadCommands.firstOrNull { it.type == LC_DYSYMTAB } ?: return null
        if (command.range.size < DYSYMTAB_COMMAND_SIZE) return null
        val offset = command.range.offset
        fun field(index: Int): UInt? = bytes.u32OrNull(offset + 8 + index * 4, header.endian)

        return MachODysymtabCommand(
            localSymbolIndex = field(0) ?: return null,
            localSymbolCount = field(1) ?: return null,
            externalDefinedSymbolIndex = field(2) ?: return null,
            externalDefinedSymbolCount = field(3) ?: return null,
            undefinedSymbolIndex = field(4) ?: return null,
            undefinedSymbolCount = field(5) ?: return null,
            tableOfContentsOffset = field(6) ?: return null,
            tableOfContentsCount = field(7) ?: return null,
            moduleTableOffset = field(8) ?: return null,
            moduleTableCount = field(9) ?: return null,
            externalReferenceSymbolTableOffset = field(10) ?: return null,
            externalReferenceSymbolTableCount = field(11) ?: return null,
            indirectSymbolTableOffset = field(12) ?: return null,
            indirectSymbolTableCount = field(13) ?: return null,
            externalRelocationOffset = field(14) ?: return null,
            externalRelocationCount = field(15) ?: return null,
            localRelocationOffset = field(16) ?: return null,
            localRelocationCount = field(17) ?: return null,
            commandRange = command.range,
        )
    }

    fun parseSymbols(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        symtab: MachOSymtabCommand,
    ): List<MachOSymbol>? {
        val symbolCount = symtab.symbolCount.toBoundedInt() ?: return null
        val symbolOffset = symtab.symbolTableOffset.toBoundedInt() ?: return null
        val stringTableOffset = symtab.stringTableOffset.toBoundedInt() ?: return null
        val stringTableSize = symtab.stringTableSize.toBoundedInt() ?: return null
        val symbolEntrySize = when (header.wordSize) {
            NativeBinaryWordSize.Bits32 -> NLIST_32_SIZE
            NativeBinaryWordSize.Bits64 -> NLIST_64_SIZE
            NativeBinaryWordSize.Unknown -> return null
        }
        val symbolTableSize = checkedMultiply(symbolCount, symbolEntrySize) ?: return null
        if (!bytes.containsRange(symbolOffset, symbolTableSize)) return null
        if (!bytes.containsRange(stringTableOffset, stringTableSize)) return null

        return buildList {
            repeat(symbolCount) { index ->
                val offset = symbolOffset + index * symbolEntrySize
                add(parseSymbol(bytes, header, index, offset, symbolEntrySize, stringTableOffset, stringTableSize) ?: return null)
            }
        }
    }

    fun parseDylibs(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        loadCommands: List<MachOLoadCommand>,
    ): List<MachODylibCommand>? {
        val dylibs = mutableListOf<MachODylibCommand>()
        for (command in loadCommands) {
            val kind = dylibKind(command.type) ?: continue
            if (command.range.size < DYLIB_COMMAND_SIZE) return null
            val offset = command.range.offset
            val nameOffset = bytes.u32OrNull(offset + 8, header.endian) ?: return null
            val nameOffsetInt = nameOffset.toBoundedInt() ?: return null
            if (nameOffsetInt !in DYLIB_COMMAND_SIZE..command.range.size) return null
            val nameStart = offset + nameOffsetInt
            val nameEndLimit = offset + command.range.size
            val nameEnd = findNullTerminator(bytes, nameStart, nameEndLimit) ?: nameEndLimit
            dylibs.add(
                MachODylibCommand(
                    index = command.index,
                    type = command.type,
                    kind = kind,
                    name = bytes.stringRange(nameStart, nameEnd),
                    timestamp = bytes.u32OrNull(offset + 12, header.endian) ?: return null,
                    currentVersion = bytes.u32OrNull(offset + 16, header.endian) ?: return null,
                    compatibilityVersion = bytes.u32OrNull(offset + 20, header.endian) ?: return null,
                    commandRange = command.range,
                    nameRange = if (nameEnd > nameStart) NativeByteRange(nameStart, nameEnd - nameStart) else null,
                )
            )
        }
        return dylibs
    }

    fun parseRelocations(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        sections: List<MachOSection>,
        symbols: List<MachOSymbol>,
    ): List<MachORelocation>? {
        val symbolsByIndex = symbols.associateBy { it.index }
        val relocations = mutableListOf<MachORelocation>()
        for (section in sections) {
            val relocationCount = section.relocationCount.toBoundedInt() ?: return null
            if (relocationCount == 0) continue

            val relocationOffset = section.relocationOffset.toBoundedInt() ?: return null
            val tableSize = checkedMultiply(relocationCount, RELOCATION_INFO_SIZE) ?: return null
            if (!bytes.containsRange(relocationOffset, tableSize)) return null

            repeat(relocationCount) { indexInSection ->
                val offset = relocationOffset + indexInSection * RELOCATION_INFO_SIZE
                relocations.add(
                    parseRelocation(
                        bytes = bytes,
                        header = header,
                        section = section,
                        index = relocations.size,
                        offset = offset,
                        symbolsByIndex = symbolsByIndex,
                    ) ?: return null
                )
            }
        }
        return relocations
    }

    private fun parseSegment64(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        command: MachOLoadCommand,
        segmentIndex: Int,
    ): MachOSegment? {
        if (command.range.size < SEGMENT_COMMAND_64_SIZE) return null
        val offset = command.range.offset
        val sectionCount = bytes.u32OrNull(offset + 64, header.endian) ?: return null
        val sectionCountInt = sectionCount.toBoundedInt() ?: return null
        val sectionsOffset = offset + SEGMENT_COMMAND_64_SIZE
        val sectionsSize = checkedMultiply(sectionCountInt, SECTION_64_SIZE) ?: return null
        if (sectionsOffset + sectionsSize > offset + command.range.size) return null

        val segment = MachOSegment(
            index = segmentIndex,
            name = bytes.fixedString(offset + 8, 16),
            virtualAddress = bytes.u64OrNull(offset + 24, header.endian) ?: return null,
            virtualSize = bytes.u64OrNull(offset + 32, header.endian) ?: return null,
            fileOffset = bytes.u64OrNull(offset + 40, header.endian) ?: return null,
            fileSize = bytes.u64OrNull(offset + 48, header.endian) ?: return null,
            maxProtection = bytes.u32OrNull(offset + 56, header.endian) ?: return null,
            initialProtection = bytes.u32OrNull(offset + 60, header.endian) ?: return null,
            sectionCount = sectionCount,
            flags = bytes.u32OrNull(offset + 68, header.endian) ?: return null,
            commandRange = command.range,
            fileRange = fileRange(bytes, bytes.u64OrNull(offset + 40, header.endian) ?: return null, bytes.u64OrNull(offset + 48, header.endian) ?: return null),
            sections = emptyList(),
        )

        val sections = buildList {
            repeat(sectionCountInt) { sectionIndex ->
                add(parseSection64(bytes, header, segment, sectionIndex, sectionsOffset + sectionIndex * SECTION_64_SIZE) ?: return null)
            }
        }
        return segment.copy(sections = sections)
    }

    private fun parseSegment32(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        command: MachOLoadCommand,
        segmentIndex: Int,
    ): MachOSegment? {
        if (command.range.size < SEGMENT_COMMAND_32_SIZE) return null
        val offset = command.range.offset
        val sectionCount = bytes.u32OrNull(offset + 48, header.endian) ?: return null
        val sectionCountInt = sectionCount.toBoundedInt() ?: return null
        val sectionsOffset = offset + SEGMENT_COMMAND_32_SIZE
        val sectionsSize = checkedMultiply(sectionCountInt, SECTION_32_SIZE) ?: return null
        if (sectionsOffset + sectionsSize > offset + command.range.size) return null

        val segment = MachOSegment(
            index = segmentIndex,
            name = bytes.fixedString(offset + 8, 16),
            virtualAddress = (bytes.u32OrNull(offset + 24, header.endian) ?: return null).toULong(),
            virtualSize = (bytes.u32OrNull(offset + 28, header.endian) ?: return null).toULong(),
            fileOffset = (bytes.u32OrNull(offset + 32, header.endian) ?: return null).toULong(),
            fileSize = (bytes.u32OrNull(offset + 36, header.endian) ?: return null).toULong(),
            maxProtection = bytes.u32OrNull(offset + 40, header.endian) ?: return null,
            initialProtection = bytes.u32OrNull(offset + 44, header.endian) ?: return null,
            sectionCount = sectionCount,
            flags = bytes.u32OrNull(offset + 52, header.endian) ?: return null,
            commandRange = command.range,
            fileRange = fileRange(bytes, (bytes.u32OrNull(offset + 32, header.endian) ?: return null).toULong(), (bytes.u32OrNull(offset + 36, header.endian) ?: return null).toULong()),
            sections = emptyList(),
        )

        val sections = buildList {
            repeat(sectionCountInt) { sectionIndex ->
                add(parseSection32(bytes, header, segment, sectionIndex, sectionsOffset + sectionIndex * SECTION_32_SIZE) ?: return null)
            }
        }
        return segment.copy(sections = sections)
    }

    private fun parseSection64(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        segment: MachOSegment,
        index: Int,
        offset: Int,
    ): MachOSection? {
        val fileOffset = bytes.u32OrNull(offset + 48, header.endian)?.toULong() ?: return null
        val size = bytes.u64OrNull(offset + 40, header.endian) ?: return null
        return MachOSection(
            index = index,
            segmentIndex = segment.index,
            name = bytes.fixedString(offset, 16),
            segmentName = bytes.fixedString(offset + 16, 16),
            address = bytes.u64OrNull(offset + 32, header.endian) ?: return null,
            size = size,
            fileOffset = fileOffset,
            alignment = bytes.u32OrNull(offset + 52, header.endian) ?: return null,
            relocationOffset = bytes.u32OrNull(offset + 56, header.endian) ?: return null,
            relocationCount = bytes.u32OrNull(offset + 60, header.endian) ?: return null,
            flags = bytes.u32OrNull(offset + 64, header.endian) ?: return null,
            headerRange = NativeByteRange(offset, SECTION_64_SIZE),
            contentRange = fileRange(bytes, fileOffset, size),
        )
    }

    private fun parseSection32(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        segment: MachOSegment,
        index: Int,
        offset: Int,
    ): MachOSection? {
        val fileOffset = bytes.u32OrNull(offset + 40, header.endian)?.toULong() ?: return null
        val size = bytes.u32OrNull(offset + 36, header.endian)?.toULong() ?: return null
        return MachOSection(
            index = index,
            segmentIndex = segment.index,
            name = bytes.fixedString(offset, 16),
            segmentName = bytes.fixedString(offset + 16, 16),
            address = bytes.u32OrNull(offset + 32, header.endian)?.toULong() ?: return null,
            size = size,
            fileOffset = fileOffset,
            alignment = bytes.u32OrNull(offset + 44, header.endian) ?: return null,
            relocationOffset = bytes.u32OrNull(offset + 48, header.endian) ?: return null,
            relocationCount = bytes.u32OrNull(offset + 52, header.endian) ?: return null,
            flags = bytes.u32OrNull(offset + 56, header.endian) ?: return null,
            headerRange = NativeByteRange(offset, SECTION_32_SIZE),
            contentRange = fileRange(bytes, fileOffset, size),
        )
    }

    private fun parseSymbol(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        index: Int,
        offset: Int,
        entrySize: Int,
        stringTableOffset: Int,
        stringTableSize: Int,
    ): MachOSymbol? {
        val nameOffset = bytes.u32OrNull(offset, header.endian) ?: return null
        val type = bytes.byteOrNull(offset + 4) ?: return null
        val sectionIndex = bytes.byteOrNull(offset + 5) ?: return null
        val description = bytes.u16OrNull(offset + 6, header.endian) ?: return null
        val value = when (header.wordSize) {
            NativeBinaryWordSize.Bits32 -> bytes.u32OrNull(offset + 8, header.endian)?.toULong() ?: return null
            NativeBinaryWordSize.Bits64 -> bytes.u64OrNull(offset + 8, header.endian) ?: return null
            NativeBinaryWordSize.Unknown -> return null
        }
        return MachOSymbol(
            index = index,
            nameOffset = nameOffset,
            name = readStringTableEntry(bytes, stringTableOffset, stringTableSize, nameOffset),
            type = type,
            sectionIndex = sectionIndex,
            description = description,
            value = value,
            recordRange = NativeByteRange(offset, entrySize),
        )
    }

    private fun parseRelocation(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        section: MachOSection,
        index: Int,
        offset: Int,
        symbolsByIndex: Map<Int, MachOSymbol>,
    ): MachORelocation? {
        val addressWord = bytes.u32OrNull(offset, header.endian) ?: return null
        val info = bytes.u32OrNull(offset + 4, header.endian) ?: return null
        val scattered = (addressWord and 0x80000000u) != 0u
        return if (scattered) {
            MachORelocation(
                index = index,
                sectionIndex = section.index,
                sectionName = section.name,
                address = addressWord and 0x00FFFFFFu,
                symbolOrSectionIndex = 0u,
                pcrel = (addressWord and 0x40000000u) != 0u,
                length = ((addressWord shr 28) and 0x3u).toInt(),
                external = false,
                type = ((addressWord shr 24) and 0xFu).toInt(),
                scattered = true,
                value = info,
                symbol = null,
                recordRange = NativeByteRange(offset, RELOCATION_INFO_SIZE),
            )
        } else {
            val symbolOrSectionIndex = info and 0x00FFFFFFu
            val external = (info and 0x08000000u) != 0u
            MachORelocation(
                index = index,
                sectionIndex = section.index,
                sectionName = section.name,
                address = addressWord,
                symbolOrSectionIndex = symbolOrSectionIndex,
                pcrel = (info and 0x01000000u) != 0u,
                length = ((info shr 25) and 0x3u).toInt(),
                external = external,
                type = ((info shr 28) and 0xFu).toInt(),
                scattered = false,
                value = null,
                symbol = if (external) symbolsByIndex[symbolOrSectionIndex.toInt()] else null,
                recordRange = NativeByteRange(offset, RELOCATION_INFO_SIZE),
            )
        }
    }
}

const val LC_SEGMENT: UInt = 0x1u
const val LC_SYMTAB: UInt = 0x2u
const val LC_DYSYMTAB: UInt = 0xBu
const val LC_LOAD_DYLIB: UInt = 0xCu
const val LC_ID_DYLIB: UInt = 0xDu
const val LC_LOAD_WEAK_DYLIB: UInt = 0x80000018u
const val LC_UUID: UInt = 0x1Bu
const val LC_SEGMENT_64: UInt = 0x19u
const val LC_REEXPORT_DYLIB: UInt = 0x8000001Fu
const val LC_LAZY_LOAD_DYLIB: UInt = 0x20u
const val LC_LOAD_UPWARD_DYLIB: UInt = 0x80000023u

private const val MACH_HEADER_32_SIZE = 28
private const val MACH_HEADER_64_SIZE = 32
private const val FAT_HEADER_SIZE = 8
private const val FAT_ARCH_32_SIZE = 20
private const val FAT_ARCH_64_SIZE = 32
private const val LOAD_COMMAND_SIZE = 8
private const val SYMTAB_COMMAND_SIZE = 24
private const val DYSYMTAB_COMMAND_SIZE = 80
private const val DYLIB_COMMAND_SIZE = 24
private const val SEGMENT_COMMAND_32_SIZE = 56
private const val SEGMENT_COMMAND_64_SIZE = 72
private const val SECTION_32_SIZE = 68
private const val SECTION_64_SIZE = 80
private const val NLIST_32_SIZE = 12
private const val NLIST_64_SIZE = 16
private const val RELOCATION_INFO_SIZE = 8

private const val MH_MAGIC: UInt = 0xFEEDFACEu
private const val MH_CIGAM: UInt = 0xCEFAEDFEu
private const val MH_MAGIC_64: UInt = 0xFEEDFACFu
private const val MH_CIGAM_64: UInt = 0xCFFAEDFEu
private const val FAT_MAGIC: UInt = 0xCAFEBABEu
private const val FAT_CIGAM: UInt = 0xBEBAFECAu
private const val FAT_MAGIC_64: UInt = 0xCAFEBABFu
private const val FAT_CIGAM_64: UInt = 0xBFBAFECAu

private fun dylibKind(type: UInt): MachODylibKind? = when (type) {
    LC_LOAD_DYLIB -> MachODylibKind.Load
    LC_LOAD_WEAK_DYLIB -> MachODylibKind.WeakLoad
    LC_REEXPORT_DYLIB -> MachODylibKind.Reexport
    LC_LOAD_UPWARD_DYLIB -> MachODylibKind.UpwardLoad
    LC_LAZY_LOAD_DYLIB -> MachODylibKind.LazyLoad
    LC_ID_DYLIB -> MachODylibKind.Id
    else -> null
}

private fun optionalCommandDiagnostic(code: String, message: String): NativeParserDiagnostic =
    NativeParserDiagnostic(
        code = code,
        message = message,
        severity = NativeParserSeverity.Warning,
    )

private fun fileRange(bytes: CommonByteBuffer, offset: ULong, size: ULong): NativeByteRange? {
    val boundedOffset = offset.toBoundedInt() ?: return null
    val boundedSize = size.toBoundedInt() ?: return null
    if (boundedSize == 0) return NativeByteRange(boundedOffset.coerceAtMost(bytes.size), 0)
    if (!bytes.containsRange(boundedOffset, boundedSize)) return null
    return NativeByteRange(boundedOffset, boundedSize)
}

private fun NativeByteRange.overlaps(other: NativeByteRange): Boolean =
    offset < other.endExclusive && other.offset < endExclusive

private fun CommonByteBuffer.fixedString(offset: Int, size: Int): String {
    if (!containsRange(offset, size)) return ""
    val bytes = ByteArray(size)
    for (index in 0 until size) {
        bytes[index] = get(offset + index)
    }
    val end = bytes.indexOf(0).takeIf { it >= 0 } ?: size
    return bytes.decodeToString(0, end)
}

private fun CommonByteBuffer.containsRange(offset: Int, size: Int): Boolean =
    offset >= 0 && size >= 0 && offset <= this.size && size <= this.size - offset

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
        NativeBinaryEndian.Little -> b0.toUInt() or (b1.toUInt() shl 8) or (b2.toUInt() shl 16) or (b3.toUInt() shl 24)
        NativeBinaryEndian.Big -> (b0.toUInt() shl 24) or (b1.toUInt() shl 16) or (b2.toUInt() shl 8) or b3.toUInt()
        NativeBinaryEndian.Unknown -> null
    }
}

private fun CommonByteBuffer.i32OrNull(offset: Int, endian: NativeBinaryEndian): Int? =
    u32OrNull(offset, endian)?.toInt()

private fun CommonByteBuffer.u64OrNull(offset: Int, endian: NativeBinaryEndian): ULong? {
    val low: UInt
    val high: UInt
    when (endian) {
        NativeBinaryEndian.Little -> {
            low = u32OrNull(offset, endian) ?: return null
            high = u32OrNull(offset + 4, endian) ?: return null
        }
        NativeBinaryEndian.Big -> {
            high = u32OrNull(offset, endian) ?: return null
            low = u32OrNull(offset + 4, endian) ?: return null
        }
        NativeBinaryEndian.Unknown -> return null
    }
    return (high.toULong() shl 32) or low.toULong()
}

private fun UInt.toBoundedInt(): Int? =
    if (this <= Int.MAX_VALUE.toUInt()) toInt() else null

private fun ULong.toBoundedInt(): Int? =
    if (this <= Int.MAX_VALUE.toULong()) toInt() else null

private fun checkedMultiply(left: Int, right: Int): Int? {
    if (left < 0 || right < 0) return null
    if (left != 0 && right > Int.MAX_VALUE / left) return null
    return left * right
}

private fun readStringTableEntry(
    bytes: CommonByteBuffer,
    stringTableOffset: Int,
    stringTableSize: Int,
    nameOffset: UInt,
): String {
    val relativeOffset = nameOffset.toBoundedInt() ?: return ""
    if (relativeOffset !in 0 until stringTableSize) return ""
    val start = stringTableOffset + relativeOffset
    val endLimit = stringTableOffset + stringTableSize
    val end = findNullTerminator(bytes, start, endLimit) ?: endLimit
    return bytes.stringRange(start, end)
}

private fun findNullTerminator(bytes: CommonByteBuffer, start: Int, endLimit: Int): Int? {
    if (start !in 0..endLimit || endLimit > bytes.size) return null
    var index = start
    while (index < endLimit) {
        if (bytes[index] == 0.toByte()) return index
        index++
    }
    return null
}

private fun CommonByteBuffer.stringRange(start: Int, end: Int): String {
    if (start < 0 || end < start || end > size) return ""
    val bytes = ByteArray(end - start)
    for (index in bytes.indices) {
        bytes[index] = get(start + index)
    }
    return bytes.decodeToString()
}
