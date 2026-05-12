package org.jetbrains.hexana.providers.pe

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeBinaryWordSize
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParseResult
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeParserSeverity
import org.jetbrains.hexana.parsing.common.NativeUnresolvedRecord
import org.jetbrains.hexana.parsing.common.nativeParseFailure
import org.jetbrains.hexana.parsing.common.nativeParseSuccess

object PeParser {
    fun parse(bytes: CommonByteBuffer): NativeParseResult<PeFile> {
        val archive = parseArchive(bytes)
        if (archive != null) {
            return nativeParseSuccess(archive, archive.diagnostics)
        }

        val dosHeader = parseDosHeader(bytes)
        if (dosHeader == null) {
            val coffHeader = parseCoffObjectHeader(bytes)
                ?: return nativeParseFailure("pe.invalid_header", "Input does not contain a valid PE image or COFF object header.")
            val sections = parseCoffObjectSections(bytes, coffHeader)
                ?: return nativeParseFailure(
                    code = "coff.invalid_sections",
                    message = "COFF section table is malformed or points outside the file.",
                    range = coffHeader.headerRange,
                )
            val diagnostics = mutableListOf<NativeParserDiagnostic>()
            val symbols = parseCoffSymbols(bytes, coffHeader) ?: run {
                diagnostics.add(optionalPeDiagnostic("coff.invalid_symbols", "COFF symbol table could not be parsed."))
                emptyList()
            }
            val relocations = parseCoffRelocations(bytes, sections, symbols) ?: run {
                diagnostics.add(optionalPeDiagnostic("coff.invalid_relocations", "COFF relocation records could not be parsed."))
                emptyList()
            }
            val file = CoffObjectFile(
                coffHeader = coffHeader,
                sections = sections,
                relocations = relocations,
                coffSymbols = symbols,
                unresolvedRecords = emptyList(),
                diagnostics = diagnostics,
            )
            return nativeParseSuccess(file, diagnostics)
        }

        val coffHeader = parseCoffHeader(bytes, dosHeader)
            ?: return nativeParseFailure(
                code = "pe.invalid_coff_header",
                message = "PE COFF header is malformed or points outside the file.",
                range = dosHeader.peSignatureRange,
            )
        val optionalHeader = parseOptionalHeader(bytes, dosHeader, coffHeader)
            ?: return nativeParseFailure(
                code = "pe.invalid_optional_header",
                message = "PE optional header is malformed or unsupported.",
                range = coffHeader.headerRange,
            )
        val sections = parseSections(bytes, dosHeader, coffHeader)
            ?: return nativeParseFailure(
                code = "pe.invalid_sections",
                message = "PE section table is malformed or points outside the file.",
                range = coffHeader.headerRange,
            )

        val diagnostics = mutableListOf<NativeParserDiagnostic>()
        val imports = parseImports(bytes, optionalHeader, sections) ?: run {
            diagnostics.add(optionalPeDiagnostic("pe.invalid_imports", "PE import table could not be parsed."))
            emptyList()
        }
        val exports = parseExports(bytes, optionalHeader, sections) ?: run {
            if (hasDataDirectory(optionalHeader, EXPORT_DIRECTORY_INDEX)) {
                diagnostics.add(optionalPeDiagnostic("pe.invalid_exports", "PE export table could not be parsed."))
            }
            null
        }
        val baseRelocations = parseBaseRelocations(bytes, optionalHeader, sections) ?: run {
            diagnostics.add(optionalPeDiagnostic("pe.invalid_base_relocations", "PE base relocation table could not be parsed."))
            emptyList()
        }
        val debugEntries = parseDebugEntries(bytes, optionalHeader, sections) ?: run {
            diagnostics.add(optionalPeDiagnostic("pe.invalid_debug_entries", "PE debug directory could not be parsed."))
            emptyList()
        }
        val coffSymbols = parseCoffSymbols(bytes, coffHeader) ?: run {
            diagnostics.add(optionalPeDiagnostic("coff.invalid_symbols", "COFF symbol table could not be parsed."))
            emptyList()
        }

        val unresolvedRecords = optionalHeader.dataDirectories
            .filter { it.index !in PeDataDirectoryIndex.knownIndices && (it.rva != 0u || it.size != 0u) }
            .map { directory ->
                NativeUnresolvedRecord(
                    kind = "pe.data_directory",
                    id = directory.index.toULong(),
                    name = directory.name,
                    recordRange = directory.recordRange,
                    reason = "Unknown PE data directory index ${directory.index}.",
                )
            }

        val file = PeImageFile(
            dosHeader = dosHeader,
            coffHeader = coffHeader,
            optionalHeader = optionalHeader,
            sections = sections,
            imports = imports,
            exports = exports,
            baseRelocations = baseRelocations,
            debugEntries = debugEntries,
            coffSymbols = coffSymbols,
            unresolvedRecords = unresolvedRecords,
            diagnostics = diagnostics,
        )
        return nativeParseSuccess(file, diagnostics)
    }

    fun parseDosHeader(bytes: CommonByteBuffer): PeDosHeader? {
        if (!bytes.hasPrefix(0, 0x4D, 0x5A)) return null
        val peHeaderOffset = bytes.u32LeOrNull(PE_HEADER_OFFSET_FIELD) ?: return null
        val peHeaderOffsetInt = peHeaderOffset.toBoundedInt() ?: return null
        if (!bytes.hasPrefix(peHeaderOffsetInt, 0x50, 0x45, 0x00, 0x00)) return null

        return PeDosHeader(
            peHeaderOffset = peHeaderOffset,
            mzRange = NativeByteRange(0, 2),
            peHeaderOffsetRange = NativeByteRange(PE_HEADER_OFFSET_FIELD, 4),
            peSignatureRange = NativeByteRange(peHeaderOffsetInt, PE_SIGNATURE_SIZE),
        )
    }

    fun parseCoffHeader(bytes: CommonByteBuffer, dosHeader: PeDosHeader): PeCoffHeader? {
        val offset = dosHeader.peHeaderOffset.toBoundedInt()?.plus(PE_SIGNATURE_SIZE) ?: return null
        if (!bytes.containsRange(offset, COFF_HEADER_SIZE)) return null

        return parseCoffHeaderAt(bytes, offset)
    }

    fun parseCoffObjectHeader(bytes: CommonByteBuffer): PeCoffHeader? {
        if (!bytes.containsRange(0, COFF_HEADER_SIZE)) return null
        val header = parseCoffHeaderAt(bytes, 0) ?: return null
        return header.takeIf { isPlausibleCoffObjectHeader(bytes, it) }
    }

    fun parseArchive(bytes: CommonByteBuffer): PeArchiveFile? {
        if (!bytes.hasPrefix(0, '!'.code, '<'.code, 'a'.code, 'r'.code, 'c'.code, 'h'.code, '>'.code, '\n'.code)) {
            return null
        }

        val rawMembers = mutableListOf<RawArchiveMember>()
        var offset = ARCHIVE_MAGIC_SIZE
        var index = 0
        while (offset < bytes.size) {
            if (!bytes.containsRange(offset, ARCHIVE_MEMBER_HEADER_SIZE)) return null
            if (!bytes.hasPrefix(offset + 58, '`'.code, '\n'.code)) return null

            val size = bytes.decimalString(offset + 48, 10).toUIntOrNull() ?: return null
            val sizeInt = size.toBoundedInt() ?: return null
            val contentOffset = offset + ARCHIVE_MEMBER_HEADER_SIZE
            if (!bytes.containsRange(contentOffset, sizeInt)) return null

            rawMembers.add(
                RawArchiveMember(
                    index = index,
                    rawName = bytes.fixedString(offset, 16).trim(),
                    timestamp = bytes.fixedString(offset + 16, 12).trim(),
                    ownerId = bytes.fixedString(offset + 28, 6).trim(),
                    groupId = bytes.fixedString(offset + 34, 6).trim(),
                    mode = bytes.fixedString(offset + 40, 8).trim(),
                    size = size,
                    headerRange = NativeByteRange(offset, ARCHIVE_MEMBER_HEADER_SIZE),
                    contentRange = NativeByteRange(contentOffset, sizeInt),
                )
            )

            offset = contentOffset + sizeInt
            if (offset % 2 != 0) {
                if (offset >= bytes.size || bytes.byteOrNull(offset) != '\n'.code) return null
                offset++
            }
            index++
        }

        val longNameTable = rawMembers.firstOrNull { it.rawName == "//" }?.contentRange
        val members = rawMembers.map { member ->
            val resolved = resolveArchiveMemberNameAndContent(bytes, member, longNameTable) ?: return null
            PeArchiveMember(
                index = member.index,
                name = resolved.name,
                timestamp = member.timestamp,
                ownerId = member.ownerId,
                groupId = member.groupId,
                mode = member.mode,
                size = member.size,
                headerRange = member.headerRange,
                contentRange = resolved.contentRange,
            )
        }

        return PeArchiveFile(
            magicRange = NativeByteRange(0, ARCHIVE_MAGIC_SIZE),
            members = members,
            unresolvedRecords = emptyList(),
            diagnostics = emptyList(),
        )
    }

    private fun parseCoffHeaderAt(bytes: CommonByteBuffer, offset: Int): PeCoffHeader? {
        return PeCoffHeader(
            machine = bytes.u16LeOrNull(offset) ?: return null,
            sectionCount = bytes.u16LeOrNull(offset + 2) ?: return null,
            timeDateStamp = bytes.u32LeOrNull(offset + 4) ?: return null,
            symbolTableOffset = bytes.u32LeOrNull(offset + 8) ?: return null,
            symbolCount = bytes.u32LeOrNull(offset + 12) ?: return null,
            optionalHeaderSize = bytes.u16LeOrNull(offset + 16) ?: return null,
            characteristics = bytes.u16LeOrNull(offset + 18) ?: return null,
            headerRange = NativeByteRange(offset, COFF_HEADER_SIZE),
        )
    }

    fun parseOptionalHeader(
        bytes: CommonByteBuffer,
        dosHeader: PeDosHeader,
        coffHeader: PeCoffHeader,
    ): PeOptionalHeader? {
        if (coffHeader.optionalHeaderSize == 0) return null

        val offset = optionalHeaderOffset(dosHeader) ?: return null
        if (!bytes.containsRange(offset, coffHeader.optionalHeaderSize)) return null

        val magic = bytes.u16LeOrNull(offset) ?: return null
        return when (magic) {
            PE32_MAGIC -> parseOptionalHeader32(bytes, offset, coffHeader.optionalHeaderSize, magic)
            PE32_PLUS_MAGIC -> parseOptionalHeader64(bytes, offset, coffHeader.optionalHeaderSize, magic)
            else -> null
        }
    }

    fun parseSections(
        bytes: CommonByteBuffer,
        dosHeader: PeDosHeader,
        coffHeader: PeCoffHeader,
    ): List<PeSection>? {
        val sectionCount = coffHeader.sectionCount
        if (sectionCount == 0) return emptyList()

        val optionalHeaderOffset = optionalHeaderOffset(dosHeader) ?: return null
        val sectionTableOffset = checkedAdd(optionalHeaderOffset, coffHeader.optionalHeaderSize) ?: return null
        val sectionTableSize = checkedMultiply(sectionCount, SECTION_HEADER_SIZE) ?: return null
        if (!bytes.containsRange(sectionTableOffset, sectionTableSize)) return null

        return buildList {
            repeat(sectionCount) { index ->
                val offset = sectionTableOffset + index * SECTION_HEADER_SIZE
                add(parseSection(bytes, index, offset, null) ?: return null)
            }
        }
    }

    fun parseCoffObjectSections(bytes: CommonByteBuffer, coffHeader: PeCoffHeader): List<PeSection>? {
        val sectionCount = coffHeader.sectionCount
        if (sectionCount == 0) return emptyList()

        val sectionTableOffset = checkedAdd(coffHeader.headerRange.offset, COFF_HEADER_SIZE + coffHeader.optionalHeaderSize) ?: return null
        val sectionTableSize = checkedMultiply(sectionCount, SECTION_HEADER_SIZE) ?: return null
        if (!bytes.containsRange(sectionTableOffset, sectionTableSize)) return null
        val stringTable = coffStringTableBounds(bytes, coffHeader)

        return buildList {
            repeat(sectionCount) { index ->
                val offset = sectionTableOffset + index * SECTION_HEADER_SIZE
                add(parseSection(bytes, index, offset, stringTable) ?: return null)
            }
        }
    }

    fun parseImports(
        bytes: CommonByteBuffer,
        optionalHeader: PeOptionalHeader,
        sections: List<PeSection>,
    ): List<PeImportLibrary>? {
        val importDirectory = optionalHeader.dataDirectories.getOrNull(IMPORT_DIRECTORY_INDEX) ?: return emptyList()
        if (importDirectory.rva == 0u) return emptyList()

        val directoryOffset = rvaToFileOffset(bytes, importDirectory.rva, optionalHeader, sections) ?: return null
        val descriptorLimit = if (importDirectory.size == 0u) {
            bytes.size
        } else {
            importDirectory.size.toBoundedInt()
                ?.let { checkedAdd(directoryOffset, it) }
                ?.coerceAtMost(bytes.size)
                ?: return null
        }
        if (descriptorLimit < directoryOffset) return null

        return buildList {
            var descriptorOffset = directoryOffset
            var descriptorIndex = 0
            while (descriptorOffset + IMPORT_DESCRIPTOR_SIZE <= descriptorLimit && descriptorIndex < MAX_IMPORT_DESCRIPTORS) {
                val importLookupTableRva = bytes.u32LeOrNull(descriptorOffset) ?: return null
                val timeDateStamp = bytes.u32LeOrNull(descriptorOffset + 4) ?: return null
                val forwarderChain = bytes.u32LeOrNull(descriptorOffset + 8) ?: return null
                val nameRva = bytes.u32LeOrNull(descriptorOffset + 12) ?: return null
                val importAddressTableRva = bytes.u32LeOrNull(descriptorOffset + 16) ?: return null
                if (
                    importLookupTableRva == 0u &&
                    timeDateStamp == 0u &&
                    forwarderChain == 0u &&
                    nameRva == 0u &&
                    importAddressTableRva == 0u
                ) {
                    break
                }

                val nameOffset = rvaToFileOffset(bytes, nameRva, optionalHeader, sections) ?: return null
                val name = bytes.nullTerminatedString(nameOffset, MAX_IMPORT_NAME_SIZE)
                val nameRange = NativeByteRange(nameOffset, name.length + 1).takeIf {
                    bytes.containsRange(it.offset, it.size)
                } ?: return null
                val thunkRva = if (importLookupTableRva != 0u) importLookupTableRva else importAddressTableRva
                val entries = parseImportEntries(bytes, optionalHeader, sections, thunkRva) ?: return null
                add(
                    PeImportLibrary(
                        index = descriptorIndex,
                        name = name,
                        importLookupTableRva = importLookupTableRva,
                        importAddressTableRva = importAddressTableRva,
                        entries = entries,
                        descriptorRange = NativeByteRange(descriptorOffset, IMPORT_DESCRIPTOR_SIZE),
                        nameRange = nameRange,
                    )
                )

                descriptorOffset += IMPORT_DESCRIPTOR_SIZE
                descriptorIndex++
            }
        }
    }

    fun parseExports(
        bytes: CommonByteBuffer,
        optionalHeader: PeOptionalHeader,
        sections: List<PeSection>,
    ): PeExportDirectory? {
        val exportDirectory = optionalHeader.dataDirectories.getOrNull(EXPORT_DIRECTORY_INDEX) ?: return null
        if (exportDirectory.rva == 0u || exportDirectory.size == 0u) return null

        val directoryOffset = rvaToFileOffset(bytes, exportDirectory.rva, optionalHeader, sections) ?: return null
        if (!bytes.containsRange(directoryOffset, EXPORT_DIRECTORY_SIZE)) return null

        val nameRva = bytes.u32LeOrNull(directoryOffset + 12) ?: return null
        val ordinalBase = bytes.u32LeOrNull(directoryOffset + 16) ?: return null
        val functionCount = bytes.u32LeOrNull(directoryOffset + 20) ?: return null
        val nameCount = bytes.u32LeOrNull(directoryOffset + 24) ?: return null
        val addressOfFunctionsRva = bytes.u32LeOrNull(directoryOffset + 28) ?: return null
        val addressOfNamesRva = bytes.u32LeOrNull(directoryOffset + 32) ?: return null
        val addressOfNameOrdinalsRva = bytes.u32LeOrNull(directoryOffset + 36) ?: return null

        val moduleNameOffset = rvaToFileOffset(bytes, nameRva, optionalHeader, sections)
        val moduleName = moduleNameOffset?.let { bytes.nullTerminatedString(it, MAX_EXPORT_NAME_SIZE) }.orEmpty()
        val moduleNameRange = moduleNameOffset?.let { NativeByteRange(it, moduleName.length + 1) }?.takeIf {
            bytes.containsRange(it.offset, it.size)
        }

        val functionsOffset = rvaToFileOffset(bytes, addressOfFunctionsRva, optionalHeader, sections) ?: return null
        val namesOffset = rvaToFileOffset(bytes, addressOfNamesRva, optionalHeader, sections)
        val ordinalsOffset = rvaToFileOffset(bytes, addressOfNameOrdinalsRva, optionalHeader, sections)
        val functionCountInt = functionCount.toBoundedInt()?.coerceAtMost(MAX_EXPORT_FUNCTIONS) ?: return null
        val nameCountInt = nameCount.toBoundedInt()?.coerceAtMost(MAX_EXPORT_FUNCTIONS) ?: return null
        if (!bytes.containsRange(functionsOffset, functionCountInt * 4)) return null

        val namedExports = mutableMapOf<Int, Pair<String, NativeByteRange?>>()
        if (namesOffset != null && ordinalsOffset != null && bytes.containsRange(namesOffset, nameCountInt * 4) && bytes.containsRange(ordinalsOffset, nameCountInt * 2)) {
            repeat(nameCountInt) { index ->
                val exportNameRva = bytes.u32LeOrNull(namesOffset + index * 4) ?: return@repeat
                val ordinalIndex = bytes.u16LeOrNull(ordinalsOffset + index * 2) ?: return@repeat
                val exportNameOffset = rvaToFileOffset(bytes, exportNameRva, optionalHeader, sections) ?: return@repeat
                val exportName = bytes.nullTerminatedString(exportNameOffset, MAX_EXPORT_NAME_SIZE)
                val exportNameRange = NativeByteRange(exportNameOffset, exportName.length + 1).takeIf {
                    bytes.containsRange(it.offset, it.size)
                }
                namedExports[ordinalIndex] = exportName to exportNameRange
            }
        }

        val exportStartRva = exportDirectory.rva
        val exportEndRva = checkedAdd(exportDirectory.rva, exportDirectory.size) ?: return null
        val entries = buildList {
            repeat(functionCountInt) { index ->
                val functionRva = bytes.u32LeOrNull(functionsOffset + index * 4) ?: return@repeat
                if (functionRva == 0u) return@repeat
                val forwarder = if (functionRva >= exportStartRva && functionRva < exportEndRva) {
                    rvaToFileOffset(bytes, functionRva, optionalHeader, sections)
                        ?.let { bytes.nullTerminatedString(it, MAX_EXPORT_NAME_SIZE) }
                } else {
                    null
                }
                val (name, nameRange) = namedExports[index] ?: (null to null)
                val ordinal = checkedAdd(ordinalBase, index.toUInt()) ?: return null
                add(
                    PeExportEntry(
                        index = index,
                        name = name,
                        ordinal = ordinal,
                        rva = functionRva,
                        forwarder = forwarder,
                        addressTableRange = NativeByteRange(functionsOffset + index * 4, 4),
                        nameRange = nameRange,
                    )
                )
            }
        }

        return PeExportDirectory(
            name = moduleName,
            ordinalBase = ordinalBase,
            functionCount = functionCount,
            nameCount = nameCount,
            directoryRange = NativeByteRange(directoryOffset, EXPORT_DIRECTORY_SIZE),
            nameRange = moduleNameRange,
            entries = entries,
        )
    }

    fun parseBaseRelocations(
        bytes: CommonByteBuffer,
        optionalHeader: PeOptionalHeader,
        sections: List<PeSection>,
    ): List<PeBaseRelocation>? {
        val relocationDirectory = optionalHeader.dataDirectories.getOrNull(BASE_RELOCATION_DIRECTORY_INDEX) ?: return emptyList()
        if (relocationDirectory.rva == 0u || relocationDirectory.size == 0u) return emptyList()

        var blockOffset = rvaToFileOffset(bytes, relocationDirectory.rva, optionalHeader, sections) ?: return null
        val directorySize = relocationDirectory.size.toBoundedInt() ?: return null
        if (!bytes.containsRange(blockOffset, directorySize)) return null
        val directoryEnd = checkedAdd(blockOffset, directorySize) ?: return null
        val relocations = mutableListOf<PeBaseRelocation>()
        var blockIndex = 0

        while (blockOffset + BASE_RELOCATION_BLOCK_HEADER_SIZE <= directoryEnd && blockIndex < MAX_RELOCATION_BLOCKS) {
            val pageRva = bytes.u32LeOrNull(blockOffset) ?: return null
            val blockSize = bytes.u32LeOrNull(blockOffset + 4)?.toBoundedInt() ?: return null
            if (pageRva == 0u && blockSize == 0) break
            if (blockSize < BASE_RELOCATION_BLOCK_HEADER_SIZE) return null
            val blockEnd = checkedAdd(blockOffset, blockSize) ?: return null
            if (blockEnd > directoryEnd) return null
            val entryCount = (blockEnd - blockOffset - BASE_RELOCATION_BLOCK_HEADER_SIZE) / 2
            var entryOffset = blockOffset + BASE_RELOCATION_BLOCK_HEADER_SIZE
            repeat(entryCount.coerceAtMost(MAX_RELOCATION_ENTRIES_PER_BLOCK)) { entryIndex ->
                val raw = bytes.u16LeOrNull(entryOffset) ?: return null
                val type = raw ushr 12
                val offsetWithinPage = raw and 0x0FFF
                if (type != 0) {
                    relocations.add(
                        PeBaseRelocation(
                            blockIndex = blockIndex,
                            entryIndex = entryIndex,
                            type = type,
                            pageRva = pageRva,
                            targetRva = pageRva + offsetWithinPage.toUInt(),
                            recordRange = NativeByteRange(entryOffset, 2),
                        )
                    )
                }
                entryOffset += 2
            }

            blockOffset += blockSize
            blockIndex++
        }

        return relocations
    }

    fun parseDebugEntries(
        bytes: CommonByteBuffer,
        optionalHeader: PeOptionalHeader,
        sections: List<PeSection>,
    ): List<PeDebugEntry>? {
        val debugDirectory = optionalHeader.dataDirectories.getOrNull(DEBUG_DIRECTORY_INDEX) ?: return emptyList()
        if (debugDirectory.rva == 0u || debugDirectory.size == 0u) return emptyList()

        val directoryOffset = rvaToFileOffset(bytes, debugDirectory.rva, optionalHeader, sections) ?: return null
        val directorySize = debugDirectory.size.toBoundedInt() ?: return null
        if (!bytes.containsRange(directoryOffset, directorySize)) return null
        val entryCount = (directorySize / DEBUG_DIRECTORY_ENTRY_SIZE).coerceAtMost(MAX_DEBUG_ENTRIES)

        return buildList {
            repeat(entryCount) { index ->
                val offset = directoryOffset + index * DEBUG_DIRECTORY_ENTRY_SIZE
                val sizeOfData = bytes.u32LeOrNull(offset + 16) ?: return null
                val pointerToRawData = bytes.u32LeOrNull(offset + 24) ?: return null
                val dataRange = fileRange(bytes, pointerToRawData, sizeOfData)
                val type = bytes.u32LeOrNull(offset + 12) ?: return null
                add(
                    PeDebugEntry(
                        index = index,
                        type = type,
                        timeDateStamp = bytes.u32LeOrNull(offset + 4) ?: return null,
                        sizeOfData = sizeOfData,
                        addressOfRawData = bytes.u32LeOrNull(offset + 20) ?: return null,
                        pointerToRawData = pointerToRawData,
                        codeView = if (type == CODEVIEW_DEBUG_TYPE) {
                            dataRange?.let { parseCodeViewDebugInfo(bytes, it.offset, it.size) }
                        } else {
                            null
                        },
                        recordRange = NativeByteRange(offset, DEBUG_DIRECTORY_ENTRY_SIZE),
                        dataRange = dataRange,
                    )
                )
            }
        }
    }

    fun parseCoffSymbols(bytes: CommonByteBuffer, coffHeader: PeCoffHeader): List<PeCoffSymbol>? {
        if (coffHeader.symbolTableOffset == 0u || coffHeader.symbolCount == 0u) return emptyList()
        val symbolTableOffset = coffHeader.symbolTableOffset.toBoundedInt() ?: return null
        val symbolCount = coffHeader.symbolCount.toBoundedInt() ?: return null
        if (symbolCount > MAX_COFF_SYMBOLS) return null
        val symbolTableSize = checkedMultiply(symbolCount, COFF_SYMBOL_SIZE) ?: return null
        if (!bytes.containsRange(symbolTableOffset, symbolTableSize)) return null

        val stringTableOffset = checkedAdd(symbolTableOffset, symbolTableSize) ?: return null
        val stringTableSize = bytes.u32LeOrNull(stringTableOffset)?.toBoundedInt()
        val stringTableEnd = stringTableSize
            ?.takeIf { it >= 4 }
            ?.let { checkedAdd(stringTableOffset, it) }
            ?.coerceAtMost(bytes.size)

        val symbols = mutableListOf<PeCoffSymbol>()
        var index = 0
        while (index < symbolCount) {
            val offset = symbolTableOffset + index * COFF_SYMBOL_SIZE
            val auxiliaryCount = bytes.byteOrNull(offset + 17) ?: return null
            val recordCount = 1 + auxiliaryCount
            if (recordCount <= 0 || index > symbolCount - recordCount) return null
            val recordSize = checkedMultiply(recordCount, COFF_SYMBOL_SIZE) ?: return null
            symbols.add(
                PeCoffSymbol(
                    index = index,
                    name = coffSymbolName(bytes, offset, stringTableOffset, stringTableEnd),
                    value = bytes.u32LeOrNull(offset + 8) ?: return null,
                    sectionNumber = bytes.i16LeOrNull(offset + 12) ?: return null,
                    type = bytes.u16LeOrNull(offset + 14) ?: return null,
                    storageClass = bytes.byteOrNull(offset + 16) ?: return null,
                    auxiliaryCount = auxiliaryCount,
                    recordRange = NativeByteRange(offset, recordSize),
                )
            )
            index += recordCount
        }

        return symbols
    }

    fun parseCoffRelocations(
        bytes: CommonByteBuffer,
        sections: List<PeSection>,
        symbols: List<PeCoffSymbol>,
    ): List<PeCoffRelocation>? {
        val symbolsByIndex = symbols.associateBy { it.index.toUInt() }
        return buildList {
            for (section in sections) {
                if (section.relocationPointer == 0u || section.relocationCount == 0) continue
                val relocationOffset = section.relocationPointer.toBoundedInt() ?: return null
                val hasOverflowCount = section.relocationCount == COFF_RELOCATION_OVERFLOW_COUNT &&
                    (section.characteristics and IMAGE_SCN_LNK_NRELOC_OVFL) != 0u
                if (!hasOverflowCount && (section.characteristics and IMAGE_SCN_LNK_NRELOC_OVFL) != 0u) return null

                val firstRelocationIndex: Int
                val relocationCount: Int
                if (hasOverflowCount) {
                    if (!bytes.containsRange(relocationOffset, COFF_RELOCATION_SIZE)) return null
                    val storedCount = bytes.u32LeOrNull(relocationOffset)?.toBoundedInt() ?: return null
                    if (storedCount <= 0 || storedCount > MAX_COFF_RELOCATIONS_PER_SECTION) return null
                    firstRelocationIndex = 1
                    relocationCount = storedCount
                } else {
                    firstRelocationIndex = 0
                    relocationCount = section.relocationCount
                }

                val relocationTableEntryCount = checkedAdd(firstRelocationIndex, relocationCount) ?: return null
                val relocationTableSize = checkedMultiply(relocationTableEntryCount, COFF_RELOCATION_SIZE) ?: return null
                if (!bytes.containsRange(relocationOffset, relocationTableSize)) return null

                repeat(relocationCount) { index ->
                    val tableIndex = firstRelocationIndex + index
                    val offset = relocationOffset + tableIndex * COFF_RELOCATION_SIZE
                    val symbolTableIndex = bytes.u32LeOrNull(offset + 4) ?: return null
                    add(
                        PeCoffRelocation(
                            index = index,
                            sectionIndex = section.index,
                            sectionName = section.name,
                            virtualAddress = bytes.u32LeOrNull(offset) ?: return null,
                            symbolTableIndex = symbolTableIndex,
                            type = bytes.u16LeOrNull(offset + 8) ?: return null,
                            symbol = symbolsByIndex[symbolTableIndex],
                            recordRange = NativeByteRange(offset, COFF_RELOCATION_SIZE),
                        )
                    )
                }
            }
        }
    }

    private fun parseOptionalHeader32(
        bytes: CommonByteBuffer,
        offset: Int,
        size: Int,
        magic: Int,
    ): PeOptionalHeader? {
        if (size < PE32_OPTIONAL_HEADER_MIN_SIZE) return null
        return PeOptionalHeader(
            wordSize = NativeBinaryWordSize.Bits32,
            magic = magic,
            addressOfEntryPoint = bytes.u32LeOrNull(offset + 16) ?: return null,
            imageBase = bytes.u32LeOrNull(offset + 28)?.toULong() ?: return null,
            sectionAlignment = bytes.u32LeOrNull(offset + 32) ?: return null,
            fileAlignment = bytes.u32LeOrNull(offset + 36) ?: return null,
            sizeOfImage = bytes.u32LeOrNull(offset + 56) ?: return null,
            sizeOfHeaders = bytes.u32LeOrNull(offset + 60) ?: return null,
            subsystem = bytes.u16LeOrNull(offset + 68) ?: return null,
            dllCharacteristics = bytes.u16LeOrNull(offset + 70) ?: return null,
            dataDirectoryCount = bytes.u32LeOrNull(offset + 92) ?: return null,
            dataDirectories = parseDataDirectories(bytes, offset, size, PE32_DATA_DIRECTORIES_OFFSET),
            headerRange = NativeByteRange(offset, size),
        )
    }

    private fun parseOptionalHeader64(
        bytes: CommonByteBuffer,
        offset: Int,
        size: Int,
        magic: Int,
    ): PeOptionalHeader? {
        if (size < PE32_PLUS_OPTIONAL_HEADER_MIN_SIZE) return null
        return PeOptionalHeader(
            wordSize = NativeBinaryWordSize.Bits64,
            magic = magic,
            addressOfEntryPoint = bytes.u32LeOrNull(offset + 16) ?: return null,
            imageBase = bytes.u64LeOrNull(offset + 24) ?: return null,
            sectionAlignment = bytes.u32LeOrNull(offset + 32) ?: return null,
            fileAlignment = bytes.u32LeOrNull(offset + 36) ?: return null,
            sizeOfImage = bytes.u32LeOrNull(offset + 56) ?: return null,
            sizeOfHeaders = bytes.u32LeOrNull(offset + 60) ?: return null,
            subsystem = bytes.u16LeOrNull(offset + 68) ?: return null,
            dllCharacteristics = bytes.u16LeOrNull(offset + 70) ?: return null,
            dataDirectoryCount = bytes.u32LeOrNull(offset + 108) ?: return null,
            dataDirectories = parseDataDirectories(bytes, offset, size, PE32_PLUS_DATA_DIRECTORIES_OFFSET),
            headerRange = NativeByteRange(offset, size),
        )
    }

    private fun parseSection(
        bytes: CommonByteBuffer,
        index: Int,
        offset: Int,
        stringTable: Pair<Int, Int>?,
    ): PeSection? {
        val rawDataPointer = bytes.u32LeOrNull(offset + 20) ?: return null
        val rawDataSize = bytes.u32LeOrNull(offset + 16) ?: return null
        return PeSection(
            index = index,
            name = coffSectionName(bytes, offset, stringTable),
            virtualSize = bytes.u32LeOrNull(offset + 8) ?: return null,
            virtualAddress = bytes.u32LeOrNull(offset + 12) ?: return null,
            rawDataSize = rawDataSize,
            rawDataPointer = rawDataPointer,
            relocationPointer = bytes.u32LeOrNull(offset + 24) ?: return null,
            lineNumberPointer = bytes.u32LeOrNull(offset + 28) ?: return null,
            relocationCount = bytes.u16LeOrNull(offset + 32) ?: return null,
            lineNumberCount = bytes.u16LeOrNull(offset + 34) ?: return null,
            characteristics = bytes.u32LeOrNull(offset + 36) ?: return null,
            headerRange = NativeByteRange(offset, SECTION_HEADER_SIZE),
            contentRange = fileRange(bytes, rawDataPointer, rawDataSize),
        )
    }

    private fun optionalHeaderOffset(dosHeader: PeDosHeader): Int? {
        val peHeaderOffset = dosHeader.peHeaderOffset.toBoundedInt() ?: return null
        return checkedAdd(peHeaderOffset, PE_SIGNATURE_SIZE + COFF_HEADER_SIZE)
    }

    private fun parseDataDirectories(
        bytes: CommonByteBuffer,
        optionalHeaderOffset: Int,
        optionalHeaderSize: Int,
        dataDirectoriesOffset: Int,
    ): List<PeDataDirectory> {
        val declaredCount = bytes.u32LeOrNull(optionalHeaderOffset + dataDirectoriesOffset - 4)?.toBoundedInt() ?: return emptyList()
        val availableBytes = optionalHeaderSize - dataDirectoriesOffset
        if (availableBytes < DATA_DIRECTORY_SIZE) return emptyList()
        val count = declaredCount.coerceAtMost(availableBytes / DATA_DIRECTORY_SIZE)
        return buildList {
            repeat(count) { index ->
                val offset = optionalHeaderOffset + dataDirectoriesOffset + index * DATA_DIRECTORY_SIZE
                add(
                    PeDataDirectory(
                        index = index,
                        name = dataDirectoryName(index),
                        rva = bytes.u32LeOrNull(offset) ?: return@buildList,
                        size = bytes.u32LeOrNull(offset + 4) ?: return@buildList,
                        recordRange = NativeByteRange(offset, DATA_DIRECTORY_SIZE),
                    )
                )
            }
        }
    }

    private fun parseImportEntries(
        bytes: CommonByteBuffer,
        optionalHeader: PeOptionalHeader,
        sections: List<PeSection>,
        thunkRva: UInt,
    ): List<PeImportEntry>? {
        if (thunkRva == 0u) return emptyList()
        val thunkOffset = rvaToFileOffset(bytes, thunkRva, optionalHeader, sections) ?: return null
        val thunkSize = if (optionalHeader.wordSize == NativeBinaryWordSize.Bits64) 8 else 4
        val ordinalFlag = if (optionalHeader.wordSize == NativeBinaryWordSize.Bits64) {
            0x8000000000000000uL
        } else {
            0x80000000uL
        }
        val ordinalMask = 0xFFFFuL

        val entries = mutableListOf<PeImportEntry>()
        var entryOffset = thunkOffset
        var entryIndex = 0
        while (entryIndex < MAX_IMPORT_ENTRIES_PER_LIBRARY) {
            if (!bytes.containsRange(entryOffset, thunkSize)) return null
            val thunkValue = if (thunkSize == 8) {
                bytes.u64LeOrNull(entryOffset) ?: return null
            } else {
                bytes.u32LeOrNull(entryOffset)?.toULong() ?: return null
            }
            if (thunkValue == 0uL) return entries

            val thunkEntryRva = thunkRva + (entryIndex * thunkSize).toUInt()
            if ((thunkValue and ordinalFlag) != 0uL) {
                entries.add(
                    PeImportEntry(
                        index = entryIndex,
                        name = null,
                        hint = null,
                        ordinal = thunkValue and ordinalMask,
                        thunkRva = thunkEntryRva,
                        thunkRange = NativeByteRange(entryOffset, thunkSize),
                        hintNameRange = null,
                    )
                )
            } else {
                val hintNameRva = thunkValue.toUIntOrNull() ?: return null
                val hintNameOffset = rvaToFileOffset(bytes, hintNameRva, optionalHeader, sections) ?: return null
                val hint = bytes.u16LeOrNull(hintNameOffset) ?: return null
                val nameOffset = checkedAdd(hintNameOffset, 2) ?: return null
                val name = bytes.nullTerminatedString(nameOffset, MAX_IMPORT_NAME_SIZE)
                val hintNameRange = NativeByteRange(hintNameOffset, 2 + name.length + 1)
                    .takeIf { bytes.containsRange(it.offset, it.size) } ?: return null
                entries.add(
                    PeImportEntry(
                        index = entryIndex,
                        name = name,
                        hint = hint,
                        ordinal = null,
                        thunkRva = thunkEntryRva,
                        thunkRange = NativeByteRange(entryOffset, thunkSize),
                        hintNameRange = hintNameRange,
                    )
                )
            }

            entryOffset += thunkSize
            entryIndex++
        }
        return null
    }

    private fun rvaToFileOffset(
        bytes: CommonByteBuffer,
        rva: UInt,
        optionalHeader: PeOptionalHeader,
        sections: List<PeSection>,
    ): Int? {
        val rvaInt = rva.toBoundedInt() ?: return null
        val headersSize = optionalHeader.sizeOfHeaders.toBoundedInt()
        if (headersSize != null && rvaInt in 0 until headersSize && rvaInt < bytes.size) return rvaInt

        for (section in sections) {
            val sectionRva = section.virtualAddress.toBoundedInt() ?: continue
            val rawDataPointer = section.rawDataPointer.toBoundedInt() ?: continue
            val rawDataSize = section.rawDataSize.toBoundedInt() ?: continue
            val virtualSize = section.virtualSize.toBoundedInt() ?: continue
            val mappedSize = maxOf(virtualSize, rawDataSize)
            if (mappedSize <= 0) continue
            val delta = rvaInt - sectionRva
            if (delta in 0 until mappedSize) {
                if (delta >= rawDataSize) return null
                val offset = checkedAdd(rawDataPointer, delta) ?: return null
                return offset.takeIf { it in 0 until bytes.size }
            }
        }
        return null
    }

    private fun parseCodeViewDebugInfo(bytes: CommonByteBuffer, offset: Int, size: Int): PeCodeViewDebugInfo? {
        if (!bytes.containsRange(offset, 4)) return null
        val signature = bytes.fixedString(offset, 4)
        return when (signature) {
            "RSDS" -> {
                if (size < 24 || !bytes.containsRange(offset, 24)) return null
                val pdbPathOffset = offset + 24
                PeCodeViewDebugInfo(
                    signature = signature,
                    guid = bytes.guidLeString(offset + 4),
                    age = bytes.u32LeOrNull(offset + 20),
                    pdbPath = bytes.nullTerminatedString(pdbPathOffset, (offset + size - pdbPathOffset).coerceAtLeast(0)),
                )
            }
            "NB10" -> {
                if (size < 16 || !bytes.containsRange(offset, 16)) return null
                val pdbPathOffset = offset + 16
                PeCodeViewDebugInfo(
                    signature = signature,
                    guid = null,
                    age = bytes.u32LeOrNull(offset + 12),
                    pdbPath = bytes.nullTerminatedString(pdbPathOffset, (offset + size - pdbPathOffset).coerceAtLeast(0)),
                )
            }
            else -> PeCodeViewDebugInfo(signature = signature, guid = null, age = null, pdbPath = null)
        }
    }
}

private const val PE_HEADER_OFFSET_FIELD = 0x3C
private const val PE_SIGNATURE_SIZE = 4
private const val ARCHIVE_MAGIC_SIZE = 8
private const val ARCHIVE_MEMBER_HEADER_SIZE = 60
private const val COFF_HEADER_SIZE = 20
private const val COFF_RELOCATION_OVERFLOW_COUNT = 0xFFFF
private const val COFF_RELOCATION_SIZE = 10
private const val SECTION_HEADER_SIZE = 40
private const val PE32_MAGIC = 0x010B
private const val PE32_PLUS_MAGIC = 0x020B
private const val PE32_OPTIONAL_HEADER_MIN_SIZE = 96
private const val PE32_PLUS_OPTIONAL_HEADER_MIN_SIZE = 112
private const val PE32_DATA_DIRECTORIES_OFFSET = 96
private const val PE32_PLUS_DATA_DIRECTORIES_OFFSET = 112
private const val DATA_DIRECTORY_SIZE = 8
private const val EXPORT_DIRECTORY_INDEX = 0
private const val IMPORT_DIRECTORY_INDEX = 1
private const val BASE_RELOCATION_DIRECTORY_INDEX = 5
private const val DEBUG_DIRECTORY_INDEX = 6
private const val EXPORT_DIRECTORY_SIZE = 40
private const val BASE_RELOCATION_BLOCK_HEADER_SIZE = 8
private const val DEBUG_DIRECTORY_ENTRY_SIZE = 28
private const val COFF_SYMBOL_SIZE = 18
private const val CODEVIEW_DEBUG_TYPE = 2u
private const val IMAGE_SCN_LNK_NRELOC_OVFL = 0x01000000u
private const val IMPORT_DESCRIPTOR_SIZE = 20
private const val MAX_IMPORT_DESCRIPTORS = 4096
private const val MAX_IMPORT_ENTRIES_PER_LIBRARY = 16384
private const val MAX_IMPORT_NAME_SIZE = 4096
private const val MAX_EXPORT_FUNCTIONS = 16384
private const val MAX_EXPORT_NAME_SIZE = 4096
private const val MAX_RELOCATION_BLOCKS = 16384
private const val MAX_RELOCATION_ENTRIES_PER_BLOCK = 65536
private const val MAX_DEBUG_ENTRIES = 4096
private const val MAX_COFF_OBJECT_SECTIONS = 96
private const val MAX_COFF_SYMBOLS = 262144
private const val MAX_COFF_RELOCATIONS_PER_SECTION = 262144

private val COFF_MACHINE_TYPES = setOf(
    0x014C, // IMAGE_FILE_MACHINE_I386
    0x01C0, // IMAGE_FILE_MACHINE_ARM
    0x01C4, // IMAGE_FILE_MACHINE_ARMNT
    0x0200, // IMAGE_FILE_MACHINE_IA64
    0x8664, // IMAGE_FILE_MACHINE_AMD64
    0xAA64, // IMAGE_FILE_MACHINE_ARM64
)

private fun optionalPeDiagnostic(code: String, message: String): NativeParserDiagnostic =
    NativeParserDiagnostic(
        code = code,
        message = message,
        severity = NativeParserSeverity.Warning,
    )

private fun isPlausibleCoffObjectHeader(bytes: CommonByteBuffer, header: PeCoffHeader): Boolean {
    if (header.machine !in COFF_MACHINE_TYPES) return false
    if (header.sectionCount !in 1..MAX_COFF_OBJECT_SECTIONS) return false
    if (header.optionalHeaderSize != 0) return false

    val sectionTableOffset = checkedAdd(header.headerRange.offset, COFF_HEADER_SIZE) ?: return false
    val sectionTableSize = checkedMultiply(header.sectionCount, SECTION_HEADER_SIZE) ?: return false
    if (!bytes.containsRange(sectionTableOffset, sectionTableSize)) return false

    val symbolTableOffset = header.symbolTableOffset.toBoundedInt() ?: return false
    if (header.symbolCount == 0u) {
        if (symbolTableOffset != 0) return false
    } else {
        if (symbolTableOffset < COFF_HEADER_SIZE || symbolTableOffset >= bytes.size) return false
        val symbolCount = header.symbolCount.toBoundedInt() ?: return false
        if (symbolCount > MAX_COFF_SYMBOLS) return false
        val symbolTableSize = checkedMultiply(symbolCount, COFF_SYMBOL_SIZE) ?: return false
        if (!bytes.containsRange(symbolTableOffset, symbolTableSize)) return false
    }

    return true
}

private fun hasDataDirectory(optionalHeader: PeOptionalHeader, index: Int): Boolean =
    optionalHeader.dataDirectories.getOrNull(index)?.let { it.rva != 0u || it.size != 0u } == true

private fun fileRange(bytes: CommonByteBuffer, offset: UInt, size: UInt): NativeByteRange? {
    val boundedOffset = offset.toBoundedInt() ?: return null
    val boundedSize = size.toBoundedInt() ?: return null
    if (boundedSize == 0) return NativeByteRange(boundedOffset.coerceAtMost(bytes.size), 0)
    if (!bytes.containsRange(boundedOffset, boundedSize)) return null
    return NativeByteRange(boundedOffset, boundedSize)
}

private fun CommonByteBuffer.hasPrefix(offset: Int, vararg expected: Int): Boolean {
    if (offset < 0 || offset + expected.size > size) return false
    for (index in expected.indices) {
        if (byteOrNull(offset + index) != expected[index]) return false
    }
    return true
}

private fun CommonByteBuffer.fixedString(offset: Int, size: Int): String {
    if (!containsRange(offset, size)) return ""
    val bytes = ByteArray(size)
    for (index in 0 until size) {
        bytes[index] = get(offset + index)
    }
    val end = bytes.indexOf(0).takeIf { it >= 0 } ?: size
    return bytes.decodeToString(0, end)
}

private fun CommonByteBuffer.decimalString(offset: Int, size: Int): String =
    fixedString(offset, size).trim()

private fun CommonByteBuffer.nullTerminatedString(offset: Int, maxSize: Int): String {
    if (offset !in 0 until size || maxSize <= 0) return ""
    val endLimit = (offset + maxSize).coerceAtMost(size)
    var end = offset
    while (end < endLimit && get(end).toInt() != 0) {
        end++
    }
    return ByteArray(end - offset) { index -> get(offset + index) }.decodeToString()
}

private fun dataDirectoryName(index: Int): String = when (index) {
    0 -> "Export Table"
    1 -> "Import Table"
    2 -> "Resource Table"
    3 -> "Exception Table"
    4 -> "Certificate Table"
    5 -> "Base Relocation Table"
    6 -> "Debug"
    7 -> "Architecture"
    8 -> "Global Ptr"
    9 -> "TLS Table"
    10 -> "Load Config Table"
    11 -> "Bound Import"
    12 -> "Import Address Table"
    13 -> "Delay Import Descriptor"
    14 -> "CLR Runtime Header"
    else -> "Directory $index"
}

private fun CommonByteBuffer.guidLeString(offset: Int): String? {
    if (!containsRange(offset, 16)) return null
    val data1 = u32LeOrNull(offset) ?: return null
    val data2 = u16LeOrNull(offset + 4) ?: return null
    val data3 = u16LeOrNull(offset + 6) ?: return null
    val tail = buildString {
        for (index in 0 until 8) {
            val byte = byteOrNull(offset + 8 + index) ?: return null
            append(byte.toString(16).uppercase().padStart(2, '0'))
        }
    }
    return "${data1.toString(16).uppercase().padStart(8, '0')}-" +
        "${data2.toString(16).uppercase().padStart(4, '0')}-" +
        "${data3.toString(16).uppercase().padStart(4, '0')}-" +
        "${tail.substring(0, 4)}-${tail.substring(4)}"
}

private fun coffSymbolName(
    bytes: CommonByteBuffer,
    symbolOffset: Int,
    stringTableOffset: Int,
    stringTableEnd: Int?,
): String {
    val zeroes = bytes.u32LeOrNull(symbolOffset)
    val stringOffset = bytes.u32LeOrNull(symbolOffset + 4)?.toBoundedInt()
    if (zeroes == 0u && stringOffset != null && stringTableEnd != null) {
        val absoluteOffset = checkedAdd(stringTableOffset, stringOffset)
        if (absoluteOffset != null && absoluteOffset in stringTableOffset + 4 until stringTableEnd) {
            return bytes.nullTerminatedString(absoluteOffset, stringTableEnd - absoluteOffset)
        }
    }
    return bytes.fixedString(symbolOffset, 8)
}

private fun coffSectionName(
    bytes: CommonByteBuffer,
    sectionOffset: Int,
    stringTable: Pair<Int, Int>?,
): String {
    val inlineName = bytes.fixedString(sectionOffset, 8)
    if (!inlineName.startsWith("/") || stringTable == null) return inlineName
    val stringOffset = inlineName.drop(1).toIntOrNull() ?: return inlineName
    val absoluteOffset = checkedAdd(stringTable.first, stringOffset) ?: return inlineName
    if (absoluteOffset !in stringTable.first + 4 until stringTable.second) return inlineName
    return bytes.nullTerminatedString(absoluteOffset, stringTable.second - absoluteOffset)
}

private fun coffStringTableBounds(bytes: CommonByteBuffer, coffHeader: PeCoffHeader): Pair<Int, Int>? {
    if (coffHeader.symbolTableOffset == 0u || coffHeader.symbolCount == 0u) return null
    val symbolTableOffset = coffHeader.symbolTableOffset.toBoundedInt() ?: return null
    val symbolCount = coffHeader.symbolCount.toBoundedInt()?.coerceAtMost(MAX_COFF_SYMBOLS) ?: return null
    val symbolTableSize = checkedMultiply(symbolCount, COFF_SYMBOL_SIZE) ?: return null
    val stringTableOffset = checkedAdd(symbolTableOffset, symbolTableSize) ?: return null
    val stringTableSize = bytes.u32LeOrNull(stringTableOffset)?.toBoundedInt()?.takeIf { it >= 4 } ?: return null
    val stringTableEnd = checkedAdd(stringTableOffset, stringTableSize)?.coerceAtMost(bytes.size) ?: return null
    return stringTableOffset to stringTableEnd
}

private fun archiveMemberName(rawName: String): String {
    val trimmed = rawName.trim()
    return if (!trimmed.startsWith("/") && trimmed.length > 1 && trimmed.endsWith("/")) trimmed.dropLast(1) else trimmed
}

private fun resolveArchiveMemberNameAndContent(
    bytes: CommonByteBuffer,
    member: RawArchiveMember,
    longNameTable: NativeByteRange?,
): ArchiveMemberNameAndContent? {
    val rawName = member.rawName
    if (rawName.startsWith("#1/")) {
        val inlineNameSize = rawName.drop(3).toIntOrNull() ?: return null
        if (inlineNameSize < 0 || inlineNameSize > member.contentRange.size) return null
        val name = bytes.archiveNameString(member.contentRange.offset, inlineNameSize)
        return ArchiveMemberNameAndContent(
            name = name,
            contentRange = NativeByteRange(
                offset = member.contentRange.offset + inlineNameSize,
                size = member.contentRange.size - inlineNameSize,
            ),
        )
    }

    if (rawName.startsWith("/") && rawName.length > 1 && rawName != "//") {
        val longNameOffset = rawName.drop(1).toIntOrNull()
        if (longNameOffset != null && longNameTable != null) {
            val name = bytes.archiveLongName(longNameTable, longNameOffset) ?: return null
            return ArchiveMemberNameAndContent(name = name, contentRange = member.contentRange)
        }
    }

    return ArchiveMemberNameAndContent(name = archiveMemberName(rawName), contentRange = member.contentRange)
}

private fun CommonByteBuffer.archiveNameString(offset: Int, size: Int): String =
    ByteArray(size) { index -> get(offset + index) }
        .decodeToString()
        .trimEnd('\u0000', '/', '\n', '\r')

private fun CommonByteBuffer.archiveLongName(tableRange: NativeByteRange, nameOffset: Int): String? {
    if (nameOffset < 0 || nameOffset >= tableRange.size) return null
    val absoluteOffset = tableRange.offset + nameOffset
    val tableEnd = tableRange.offset + tableRange.size
    var end = absoluteOffset
    while (end < tableEnd) {
        val byte = byteOrNull(end) ?: return null
        if (byte == '\n'.code || byte == 0) break
        end++
    }
    if (end == absoluteOffset) return ""
    return ByteArray(end - absoluteOffset) { index -> get(absoluteOffset + index) }
        .decodeToString()
        .trimEnd('/', '\r')
}

private data class RawArchiveMember(
    val index: Int,
    val rawName: String,
    val timestamp: String,
    val ownerId: String,
    val groupId: String,
    val mode: String,
    val size: UInt,
    val headerRange: NativeByteRange,
    val contentRange: NativeByteRange,
)

private data class ArchiveMemberNameAndContent(
    val name: String,
    val contentRange: NativeByteRange,
)

private fun CommonByteBuffer.containsRange(offset: Int, size: Int): Boolean =
    offset >= 0 && size >= 0 && offset <= this.size && size <= this.size - offset

private fun CommonByteBuffer.byteOrNull(offset: Int): Int? =
    if (offset in 0 until size) get(offset).toInt() and 0xFF else null

private fun CommonByteBuffer.u16LeOrNull(offset: Int): Int? {
    val b0 = byteOrNull(offset) ?: return null
    val b1 = byteOrNull(offset + 1) ?: return null
    return b0 or (b1 shl 8)
}

private fun CommonByteBuffer.i16LeOrNull(offset: Int): Int? {
    val unsigned = u16LeOrNull(offset) ?: return null
    return if (unsigned >= 0x8000) unsigned - 0x10000 else unsigned
}

private fun CommonByteBuffer.u32LeOrNull(offset: Int): UInt? {
    val b0 = byteOrNull(offset) ?: return null
    val b1 = byteOrNull(offset + 1) ?: return null
    val b2 = byteOrNull(offset + 2) ?: return null
    val b3 = byteOrNull(offset + 3) ?: return null
    return b0.toUInt() or (b1.toUInt() shl 8) or (b2.toUInt() shl 16) or (b3.toUInt() shl 24)
}

private fun CommonByteBuffer.u64LeOrNull(offset: Int): ULong? {
    val low = u32LeOrNull(offset) ?: return null
    val high = u32LeOrNull(offset + 4) ?: return null
    return (high.toULong() shl 32) or low.toULong()
}

private fun UInt.toBoundedInt(): Int? =
    if (this <= Int.MAX_VALUE.toUInt()) toInt() else null

private fun ULong.toUIntOrNull(): UInt? =
    if (this <= UInt.MAX_VALUE.toULong()) toUInt() else null

private fun checkedMultiply(left: Int, right: Int): Int? {
    if (left < 0 || right < 0) return null
    if (left != 0 && right > Int.MAX_VALUE / left) return null
    return left * right
}

private fun checkedAdd(left: UInt, right: UInt): UInt? {
    val result = left.toULong() + right.toULong()
    return if (result <= UInt.MAX_VALUE.toULong()) result.toUInt() else null
}

private fun checkedAdd(left: Int, right: Int): Int? {
    if (right > 0 && left > Int.MAX_VALUE - right) return null
    if (right < 0 && left < Int.MIN_VALUE - right) return null
    return left + right
}
