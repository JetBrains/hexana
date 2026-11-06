package org.jetbrains.hexana.providers.macho

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.LargeBytes
import org.jetbrains.hexana.io.asLargeBytes
import org.jetbrains.hexana.io.containsRange
import org.jetbrains.hexana.io.readBytes
import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParseResult
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeParserSeverity
import org.jetbrains.hexana.parsing.common.NativeUnresolvedRecord
import org.jetbrains.hexana.parsing.common.nativeParseFailure
import org.jetbrains.hexana.parsing.common.nativeParseSuccess

/**
 * Mach-O and universal (fat) Mach-O parser. Reads through [LargeBytes] with 64-bit offsets: segment
 * file offsets are u64, symbol, string and relocation table offsets u32 (so up to 4 GiB), and
 * `fat_arch_64` slices can sit anywhere — a large debug build or a universal binary with a big slice
 * puts them past 2 GiB. Ranges are 64-bit ([NativeByteRange.start]).
 * The [CommonByteBuffer] overloads serve callers holding an in-memory buffer.
 */
object MachOParser {
    fun parse(bytes: CommonByteBuffer): NativeParseResult<MachOFile> = parse(bytes.asLargeBytes())

    fun parseFatHeader(bytes: CommonByteBuffer): MachOFatHeader? = parseFatHeader(bytes.asLargeBytes())

    fun parseFatArchitectures(bytes: CommonByteBuffer, header: MachOFatHeader): List<MachOFatArch>? =
        parseFatArchitectures(bytes.asLargeBytes(), header)

    fun parseHeader(bytes: CommonByteBuffer): MachOHeader? = parseHeader(bytes.asLargeBytes())

    fun parseLoadCommands(bytes: CommonByteBuffer, header: MachOHeader): List<MachOLoadCommand>? =
        parseLoadCommands(bytes.asLargeBytes(), header)

    fun parseSegments(bytes: CommonByteBuffer, header: MachOHeader, loadCommands: List<MachOLoadCommand>): List<MachOSegment>? =
        parseSegments(bytes.asLargeBytes(), header, loadCommands)

    fun parseUuid(bytes: CommonByteBuffer, loadCommands: List<MachOLoadCommand>): MachOUuid? =
        parseUuid(bytes.asLargeBytes(), loadCommands)

    fun parseSymtab(bytes: CommonByteBuffer, header: MachOHeader, loadCommands: List<MachOLoadCommand>): MachOSymtabCommand? =
        parseSymtab(bytes.asLargeBytes(), header, loadCommands)

    fun parseDysymtab(bytes: CommonByteBuffer, header: MachOHeader, loadCommands: List<MachOLoadCommand>): MachODysymtabCommand? =
        parseDysymtab(bytes.asLargeBytes(), header, loadCommands)

    fun parseSymbols(bytes: CommonByteBuffer, header: MachOHeader, symtab: MachOSymtabCommand): List<MachOSymbol>? =
        parseSymbols(bytes.asLargeBytes(), header, symtab)

    fun parseDylibs(bytes: CommonByteBuffer, header: MachOHeader, loadCommands: List<MachOLoadCommand>): List<MachODylibCommand>? =
        parseDylibs(bytes.asLargeBytes(), header, loadCommands)

    fun parseRelocations(
        bytes: CommonByteBuffer,
        header: MachOHeader,
        sections: List<MachOSection>,
        symbols: List<MachOSymbol>,
    ): List<MachORelocation>? = parseRelocations(bytes.asLargeBytes(), header, sections, symbols)

    fun parse(bytes: LargeBytes): NativeParseResult<MachOFile> {
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

    fun parseFatHeader(bytes: LargeBytes): MachOFatHeader? {
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
        if (!bytes.containsRange(FAT_HEADER_SIZE.toLong(), archTableSize)) return null

        return MachOFatHeader(
            wordSize = wordSize,
            endian = endian,
            archCount = archCount,
            headerRange = NativeByteRange(0, FAT_HEADER_SIZE),
            archTableRange = NativeByteRange(FAT_HEADER_SIZE.toLong(), archTableSize),
        )
    }

    fun parseFatArchitectures(bytes: LargeBytes, header: MachOFatHeader): List<MachOFatArch>? {
        val result = mutableListOf<MachOFatArch>()
        val count = header.archCount.toBoundedInt() ?: return null
        val archSize = if (header.wordSize == NativeBinaryWordSize.Bits64) FAT_ARCH_64_SIZE else FAT_ARCH_32_SIZE
        val sliceRanges = mutableListOf<NativeByteRange>()

        repeat(count) { index ->
            val offset = header.archTableRange.start + index.toLong() * archSize
            if (offset + archSize > header.archTableRange.end) return null

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
                    archRange = NativeByteRange(offset, archSize.toLong()),
                    sliceRange = sliceRange,
                )
            )
        }

        return result
    }

    fun parseHeader(bytes: LargeBytes): MachOHeader? {
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
        if (!bytes.containsRange(headerSize.toLong(), loadCommandsSizeInt.toLong())) return null

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

    fun parseLoadCommands(bytes: LargeBytes, header: MachOHeader): List<MachOLoadCommand>? {
        val result = mutableListOf<MachOLoadCommand>()
        var offset = header.loadCommandsRange.start
        val end = header.loadCommandsRange.end
        val count = header.loadCommandCount.toBoundedInt() ?: return null
        repeat(count) { index ->
            if (offset + LOAD_COMMAND_SIZE > end) return null
            val type = bytes.u32OrNull(offset, header.endian) ?: return null
            val size = bytes.u32OrNull(offset + 4, header.endian) ?: return null
            val sizeLong = size.toLong()
            if (sizeLong < LOAD_COMMAND_SIZE || offset + sizeLong > end) return null
            result.add(
                MachOLoadCommand(
                    index = index,
                    type = type,
                    size = size,
                    range = NativeByteRange(offset, sizeLong),
                )
            )
            offset += sizeLong
        }
        if (offset != end) return null
        return result
    }

    fun parseSegments(
        bytes: LargeBytes,
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

    fun parseUuid(bytes: LargeBytes, loadCommands: List<MachOLoadCommand>): MachOUuid? {
        val command = loadCommands.firstOrNull { it.type == LC_UUID } ?: return null
        if (command.range.length < 24) return null
        val uuidOffset = command.range.start + 8
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
            uuidRange = NativeByteRange(uuidOffset, 16L),
        )
    }

    fun parseSymtab(
        bytes: LargeBytes,
        header: MachOHeader,
        loadCommands: List<MachOLoadCommand>,
    ): MachOSymtabCommand? {
        val command = loadCommands.firstOrNull { it.type == LC_SYMTAB } ?: return null
        if (command.range.length < SYMTAB_COMMAND_SIZE) return null
        val offset = command.range.start
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
        bytes: LargeBytes,
        header: MachOHeader,
        loadCommands: List<MachOLoadCommand>,
    ): MachODysymtabCommand? {
        val command = loadCommands.firstOrNull { it.type == LC_DYSYMTAB } ?: return null
        if (command.range.length < DYSYMTAB_COMMAND_SIZE) return null
        val offset = command.range.start
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
        bytes: LargeBytes,
        header: MachOHeader,
        symtab: MachOSymtabCommand,
    ): List<MachOSymbol>? {
        val symbolCount = symtab.symbolCount.toBoundedInt() ?: return null
        val symbolOffset = symtab.symbolTableOffset.toLong()
        val stringTableOffset = symtab.stringTableOffset.toLong()
        val stringTableSize = symtab.stringTableSize.toLong()
        val symbolEntrySize = when (header.wordSize) {
            NativeBinaryWordSize.Bits32 -> NLIST_32_SIZE
            NativeBinaryWordSize.Bits64 -> NLIST_64_SIZE
            NativeBinaryWordSize.Unknown -> return null
        }
        val symbolTableSize = checkedMultiply(symbolCount, symbolEntrySize) ?: return null
        if (!bytes.containsRange(symbolOffset, symbolTableSize)) return null
        if (!bytes.containsRange(stringTableOffset, stringTableSize)) return null
        val stringTable = MachOStringTable(bytes, stringTableOffset, stringTableSize)

        return buildList {
            repeat(symbolCount) { index ->
                val offset = symbolOffset + index.toLong() * symbolEntrySize
                add(parseSymbol(bytes, header, index, offset, symbolEntrySize, stringTable) ?: return null)
            }
        }
    }

    fun parseDylibs(
        bytes: LargeBytes,
        header: MachOHeader,
        loadCommands: List<MachOLoadCommand>,
    ): List<MachODylibCommand>? {
        val dylibs = mutableListOf<MachODylibCommand>()
        for (command in loadCommands) {
            val kind = dylibKind(command.type) ?: continue
            if (command.range.length < DYLIB_COMMAND_SIZE) return null
            val offset = command.range.start
            val nameOffset = bytes.u32OrNull(offset + 8, header.endian) ?: return null
            val nameOffsetLong = nameOffset.toLong()
            if (nameOffsetLong !in DYLIB_COMMAND_SIZE..command.range.length) return null
            val nameStart = offset + nameOffsetLong
            val nameEndLimit = command.range.end
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
        bytes: LargeBytes,
        header: MachOHeader,
        sections: List<MachOSection>,
        symbols: List<MachOSymbol>,
    ): List<MachORelocation>? {
        val symbolsByIndex = symbols.associateBy { it.index }
        val relocations = mutableListOf<MachORelocation>()
        for (section in sections) {
            val relocationCount = section.relocationCount.toBoundedInt() ?: return null
            if (relocationCount == 0) continue

            val relocationOffset = section.relocationOffset.toLong()
            val tableSize = checkedMultiply(relocationCount, RELOCATION_INFO_SIZE) ?: return null
            if (!bytes.containsRange(relocationOffset, tableSize)) return null

            repeat(relocationCount) { indexInSection ->
                val offset = relocationOffset + indexInSection.toLong() * RELOCATION_INFO_SIZE
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
        bytes: LargeBytes,
        header: MachOHeader,
        command: MachOLoadCommand,
        segmentIndex: Int,
    ): MachOSegment? {
        if (command.range.length < SEGMENT_COMMAND_64_SIZE) return null
        val offset = command.range.start
        val sectionCount = bytes.u32OrNull(offset + 64, header.endian) ?: return null
        val sectionCountInt = sectionCount.toBoundedInt() ?: return null
        val sectionsOffset = offset + SEGMENT_COMMAND_64_SIZE
        val sectionsSize = checkedMultiply(sectionCountInt, SECTION_64_SIZE) ?: return null
        if (sectionsOffset + sectionsSize > command.range.end) return null

        val fileOffset = bytes.u64OrNull(offset + 40, header.endian) ?: return null
        val fileSize = bytes.u64OrNull(offset + 48, header.endian) ?: return null
        val segment = MachOSegment(
            index = segmentIndex,
            name = bytes.fixedString(offset + 8, 16),
            virtualAddress = bytes.u64OrNull(offset + 24, header.endian) ?: return null,
            virtualSize = bytes.u64OrNull(offset + 32, header.endian) ?: return null,
            fileOffset = fileOffset,
            fileSize = fileSize,
            maxProtection = bytes.u32OrNull(offset + 56, header.endian) ?: return null,
            initialProtection = bytes.u32OrNull(offset + 60, header.endian) ?: return null,
            sectionCount = sectionCount,
            flags = bytes.u32OrNull(offset + 68, header.endian) ?: return null,
            commandRange = command.range,
            fileRange = fileRange(bytes, fileOffset, fileSize),
            sections = emptyList(),
        )

        val sections = buildList {
            repeat(sectionCountInt) { sectionIndex ->
                add(parseSection64(bytes, header, segment, sectionIndex, sectionsOffset + sectionIndex.toLong() * SECTION_64_SIZE) ?: return null)
            }
        }
        return segment.copy(sections = sections)
    }

    private fun parseSegment32(
        bytes: LargeBytes,
        header: MachOHeader,
        command: MachOLoadCommand,
        segmentIndex: Int,
    ): MachOSegment? {
        if (command.range.length < SEGMENT_COMMAND_32_SIZE) return null
        val offset = command.range.start
        val sectionCount = bytes.u32OrNull(offset + 48, header.endian) ?: return null
        val sectionCountInt = sectionCount.toBoundedInt() ?: return null
        val sectionsOffset = offset + SEGMENT_COMMAND_32_SIZE
        val sectionsSize = checkedMultiply(sectionCountInt, SECTION_32_SIZE) ?: return null
        if (sectionsOffset + sectionsSize > command.range.end) return null

        val fileOffset = (bytes.u32OrNull(offset + 32, header.endian) ?: return null).toULong()
        val fileSize = (bytes.u32OrNull(offset + 36, header.endian) ?: return null).toULong()
        val segment = MachOSegment(
            index = segmentIndex,
            name = bytes.fixedString(offset + 8, 16),
            virtualAddress = (bytes.u32OrNull(offset + 24, header.endian) ?: return null).toULong(),
            virtualSize = (bytes.u32OrNull(offset + 28, header.endian) ?: return null).toULong(),
            fileOffset = fileOffset,
            fileSize = fileSize,
            maxProtection = bytes.u32OrNull(offset + 40, header.endian) ?: return null,
            initialProtection = bytes.u32OrNull(offset + 44, header.endian) ?: return null,
            sectionCount = sectionCount,
            flags = bytes.u32OrNull(offset + 52, header.endian) ?: return null,
            commandRange = command.range,
            fileRange = fileRange(bytes, fileOffset, fileSize),
            sections = emptyList(),
        )

        val sections = buildList {
            repeat(sectionCountInt) { sectionIndex ->
                add(parseSection32(bytes, header, segment, sectionIndex, sectionsOffset + sectionIndex.toLong() * SECTION_32_SIZE) ?: return null)
            }
        }
        return segment.copy(sections = sections)
    }

    private fun parseSection64(
        bytes: LargeBytes,
        header: MachOHeader,
        segment: MachOSegment,
        index: Int,
        offset: Long,
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
            headerRange = NativeByteRange(offset, SECTION_64_SIZE.toLong()),
            contentRange = fileRange(bytes, fileOffset, size),
        )
    }

    private fun parseSection32(
        bytes: LargeBytes,
        header: MachOHeader,
        segment: MachOSegment,
        index: Int,
        offset: Long,
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
            headerRange = NativeByteRange(offset, SECTION_32_SIZE.toLong()),
            contentRange = fileRange(bytes, fileOffset, size),
        )
    }

    private fun parseSymbol(
        bytes: LargeBytes,
        header: MachOHeader,
        index: Int,
        offset: Long,
        entrySize: Int,
        stringTable: MachOStringTable,
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
            name = stringTable.entry(nameOffset),
            type = type,
            sectionIndex = sectionIndex,
            description = description,
            value = value,
            recordRange = NativeByteRange(offset, entrySize.toLong()),
        )
    }

    private fun parseRelocation(
        bytes: LargeBytes,
        header: MachOHeader,
        section: MachOSection,
        index: Int,
        offset: Long,
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
                recordRange = NativeByteRange(offset, RELOCATION_INFO_SIZE.toLong()),
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
                recordRange = NativeByteRange(offset, RELOCATION_INFO_SIZE.toLong()),
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
private const val DYLIB_COMMAND_SIZE = 24L
private const val SEGMENT_COMMAND_32_SIZE = 56
private const val SEGMENT_COMMAND_64_SIZE = 72
private const val SECTION_32_SIZE = 68
private const val SECTION_64_SIZE = 80
private const val NLIST_32_SIZE = 12
private const val NLIST_64_SIZE = 16
private const val RELOCATION_INFO_SIZE = 8

/**
 * Largest string table copied into an array. Above it — the string table of a large debug build
 * can run to gigabytes — names are read from the file in place, which costs a bounds-checked read
 * per byte but no heap.
 */
private const val MAX_COPIED_STRING_TABLE = 64L * 1024 * 1024

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

/** The 64-bit range `[offset, offset + size)` when it lies inside [bytes]; an empty one is clamped to the end. */
private fun fileRange(bytes: LargeBytes, offset: ULong, size: ULong): NativeByteRange? {
    val boundedOffset = offset.toBoundedLong() ?: return null
    val boundedSize = size.toBoundedLong() ?: return null
    if (boundedSize == 0L) return NativeByteRange(boundedOffset.coerceAtMost(bytes.size), 0L)
    if (!bytes.containsRange(boundedOffset, boundedSize)) return null
    return NativeByteRange(boundedOffset, boundedSize)
}

private fun NativeByteRange.overlaps(other: NativeByteRange): Boolean =
    start < other.end && other.start < end

private fun LargeBytes.fixedString(offset: Long, size: Int): String {
    if (!containsRange(offset, size.toLong())) return ""
    val bytes = readBytes(offset, size)
    val end = bytes.indexOf(0).takeIf { it >= 0 } ?: size
    return bytes.decodeToString(0, end)
}

private fun LargeBytes.byteOrNull(offset: Long): Int? =
    if (offset in 0 until size) get(offset).toInt() and 0xFF else null

private fun LargeBytes.u16OrNull(offset: Long, endian: NativeBinaryEndian): Int? {
    val b0 = byteOrNull(offset) ?: return null
    val b1 = byteOrNull(offset + 1) ?: return null
    return when (endian) {
        NativeBinaryEndian.Little -> b0 or (b1 shl 8)
        NativeBinaryEndian.Big -> (b0 shl 8) or b1
        NativeBinaryEndian.Unknown -> null
    }
}

private fun LargeBytes.u32OrNull(offset: Long, endian: NativeBinaryEndian): UInt? {
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

private fun LargeBytes.i32OrNull(offset: Long, endian: NativeBinaryEndian): Int? =
    u32OrNull(offset, endian)?.toInt()

private fun LargeBytes.u64OrNull(offset: Long, endian: NativeBinaryEndian): ULong? {
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

/** A 64-bit file offset or size, or `null` past what a [Long] (and so any file) can hold. */
private fun ULong.toBoundedLong(): Long? =
    if (this <= Long.MAX_VALUE.toULong()) toLong() else null

/** The byte size of [left] records of [right] bytes; a table can exceed 2 GiB. */
private fun checkedMultiply(left: Int, right: Int): Long? {
    if (left < 0 || right < 0) return null
    return left.toLong() * right
}

/**
 * A symbol string table. Copied into one array when that is cheap ([MAX_COPIED_STRING_TABLE]),
 * so names decode without a bounds-checked read per byte; otherwise read from the file in place.
 * The caller has checked that the table lies inside [bytes].
 */
private class MachOStringTable(private val bytes: LargeBytes, private val start: Long, private val size: Long) {
    private val copy: ByteArray? = if (size <= MAX_COPIED_STRING_TABLE) bytes.readBytes(start, size.toInt()) else null

    fun entry(nameOffset: UInt): String {
        val relative = nameOffset.toLong()
        if (relative !in 0 until size) return ""
        val table = copy
        if (table != null) {
            val from = relative.toInt()
            var end = from
            while (end < table.size && table[end] != 0.toByte()) end++
            return table.decodeToString(from, end)
        }
        val from = start + relative
        val limit = start + size
        return bytes.stringRange(from, findNullTerminator(bytes, from, limit) ?: limit)
    }
}

private fun findNullTerminator(bytes: LargeBytes, start: Long, endLimit: Long): Long? {
    if (start !in 0..endLimit || endLimit > bytes.size) return null
    var index = start
    while (index < endLimit) {
        if (bytes[index] == 0.toByte()) return index
        index++
    }
    return null
}

private fun LargeBytes.stringRange(start: Long, end: Long): String {
    if (start < 0 || end < start || end > size || end - start > Int.MAX_VALUE) return ""
    return readBytes(start, (end - start).toInt()).decodeToString()
}
