package org.jetbrains.hexana.providers.pe.clr

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeParserSeverity
import org.jetbrains.hexana.providers.pe.PeDataDirectoryIndex
import org.jetbrains.hexana.providers.pe.PeOptionalHeader
import org.jetbrains.hexana.providers.pe.PeParser
import org.jetbrains.hexana.providers.pe.PeSection
import org.jetbrains.hexana.providers.pe.byteOrNull
import org.jetbrains.hexana.providers.pe.checkedAdd
import org.jetbrains.hexana.providers.pe.containsRange
import org.jetbrains.hexana.providers.pe.guidLeString
import org.jetbrains.hexana.providers.pe.nullTerminatedString
import org.jetbrains.hexana.providers.pe.toBoundedInt
import org.jetbrains.hexana.providers.pe.u16LeOrNull
import org.jetbrains.hexana.providers.pe.u32LeOrNull
import org.jetbrains.hexana.providers.pe.u64LeOrNull
import org.jetbrains.hexana.providers.pe.clr.ClrTableId as T

/**
 * Decodes ECMA-335 managed metadata from a PE image: the CLI header (II.25.3.3), the metadata
 * root and stream headers (II.24.2), the `#~` / `#-` tables stream (II.24.2.6, II.22) and
 * method body headers (II.25.4).
 */
object ClrMetadataParser {
    /**
     * Returns null when the image has no CLR runtime header directory or the CLI header itself
     * cannot be read; failures further down are reported in [ClrMetadata.diagnostics].
     */
    fun parse(bytes: CommonByteBuffer, optionalHeader: PeOptionalHeader, sections: List<PeSection>): ClrMetadata? {
        val header = parseCliHeader(bytes, optionalHeader, sections) ?: return null
        val readyToRun = isReadyToRun(bytes, header, optionalHeader, sections)
        return Decoder(bytes, header, readyToRun) { rva -> PeParser.rvaToFileOffset(bytes, rva, optionalHeader, sections) }.decode()
    }

    /**
     * Decodes a metadata blob that is not the image's own metadata — the ReadyToRun manifest
     * metadata, for example. It has no CLI header, so method bodies and resources do not resolve.
     */
    fun parseStandalone(bytes: CommonByteBuffer, range: NativeByteRange): ClrMetadata {
        val header = ClrCliHeader(
            size = CLI_HEADER_SIZE.toUInt(),
            majorRuntimeVersion = 0,
            minorRuntimeVersion = 0,
            metadataRva = 0u,
            metadataSize = range.size.toUInt(),
            flags = 0u,
            entryPointToken = 0u,
            resourcesRva = 0u,
            resourcesSize = 0u,
            strongNameSignatureRva = 0u,
            strongNameSignatureSize = 0u,
            vTableFixupsRva = 0u,
            vTableFixupsSize = 0u,
            managedNativeHeaderRva = 0u,
            managedNativeHeaderSize = 0u,
            headerRange = NativeByteRange(range.offset, 0),
        )
        // The only "RVA" the decoder asks for here is the metadata itself (RVA 0); method RVAs stay unresolved.
        return Decoder(bytes, header, readyToRun = false) { rva -> if (rva == 0u) range.offset else null }.decode()
    }

    /**
     * True when [bytes] is a standalone Portable PDB: a metadata root at offset 0 with a `#Pdb`
     * stream. Reads only the root and stream headers.
     */
    fun isPortablePdb(bytes: CommonByteBuffer): Boolean {
        if (bytes.u32LeOrNull(0) != METADATA_SIGNATURE) return false
        val versionLength = bytes.u32LeOrNull(12)?.toBoundedInt() ?: return false
        if (versionLength > MAX_VERSION_LENGTH) return false
        val flagsOffset = 16 + align4(versionLength)
        val streamCount = bytes.u16LeOrNull(flagsOffset + 2) ?: return false
        var position = flagsOffset + 4
        repeat(streamCount.coerceAtMost(MAX_STREAMS)) {
            if (!bytes.containsRange(position, 8)) return false
            val name = bytes.nullTerminatedString(position + 8, MAX_STREAM_NAME_LENGTH)
            if (name == "#Pdb") return true
            position += 8 + align4(name.length + 1)
        }
        return false
    }

    /** Decodes a standalone Portable PDB; [ClrMetadata.portablePdb] is null when it has no `#Pdb` stream. */
    fun parsePortablePdb(bytes: CommonByteBuffer): ClrMetadata = parseStandalone(bytes, NativeByteRange(0, bytes.size))

    /** Reads only the CLI header, without touching the metadata; null for native images. */
    fun parseCliHeader(bytes: CommonByteBuffer, optionalHeader: PeOptionalHeader, sections: List<PeSection>): ClrCliHeader? {
        val directory = optionalHeader.dataDirectories.getOrNull(PeDataDirectoryIndex.ClrRuntimeHeader) ?: return null
        if (directory.rva == 0u || directory.size == 0u) return null
        val offset = PeParser.rvaToFileOffset(bytes, directory.rva, optionalHeader, sections) ?: return null
        return parseCliHeader(bytes, offset)
    }

    /**
     * True when the image carries precompiled native code next to its IL: a ReadyToRun
     * (crossgen) image, recognized by the `RTR` signature of its managed native header.
     */
    fun isReadyToRun(bytes: CommonByteBuffer, header: ClrCliHeader, optionalHeader: PeOptionalHeader, sections: List<PeSection>): Boolean {
        if (header.managedNativeHeaderRva == 0u || header.managedNativeHeaderSize < 4u) return false
        val offset = PeParser.rvaToFileOffset(bytes, header.managedNativeHeaderRva, optionalHeader, sections) ?: return false
        return bytes.u32LeOrNull(offset) == READY_TO_RUN_SIGNATURE
    }

    /** True when [optionalHeader] declares a CLR runtime header, i.e. the image is a .NET assembly. */
    fun hasClrHeader(optionalHeader: PeOptionalHeader): Boolean =
        optionalHeader.dataDirectories.getOrNull(PeDataDirectoryIndex.ClrRuntimeHeader)
            ?.let { it.rva != 0u && it.size != 0u } == true

    private fun parseCliHeader(bytes: CommonByteBuffer, offset: Int): ClrCliHeader? {
        if (!bytes.containsRange(offset, CLI_HEADER_SIZE)) return null
        val size = bytes.u32LeOrNull(offset) ?: return null
        if (size < CLI_HEADER_SIZE.toUInt()) return null
        return ClrCliHeader(
            size = size,
            majorRuntimeVersion = bytes.u16LeOrNull(offset + 4) ?: return null,
            minorRuntimeVersion = bytes.u16LeOrNull(offset + 6) ?: return null,
            metadataRva = bytes.u32LeOrNull(offset + 8) ?: return null,
            metadataSize = bytes.u32LeOrNull(offset + 12) ?: return null,
            flags = bytes.u32LeOrNull(offset + 16) ?: return null,
            entryPointToken = bytes.u32LeOrNull(offset + 20) ?: return null,
            resourcesRva = bytes.u32LeOrNull(offset + 24) ?: return null,
            resourcesSize = bytes.u32LeOrNull(offset + 28) ?: return null,
            strongNameSignatureRva = bytes.u32LeOrNull(offset + 32) ?: return null,
            strongNameSignatureSize = bytes.u32LeOrNull(offset + 36) ?: return null,
            vTableFixupsRva = bytes.u32LeOrNull(offset + 48) ?: return null,
            vTableFixupsSize = bytes.u32LeOrNull(offset + 52) ?: return null,
            managedNativeHeaderRva = bytes.u32LeOrNull(offset + 64) ?: return null,
            managedNativeHeaderSize = bytes.u32LeOrNull(offset + 68) ?: return null,
            headerRange = NativeByteRange(offset, CLI_HEADER_SIZE),
        )
    }

    private class Decoder(
        private val bytes: CommonByteBuffer,
        private val header: ClrCliHeader,
        private val readyToRun: Boolean,
        private val resolve: (UInt) -> Int?,
    ) {
        private val diagnostics = mutableListOf<NativeParserDiagnostic>()
        private var heaps = Heaps(bytes, null, null, null, null)
        private var tables: Tables? = null
        private var pdbHeader: ClrPdbStreamHeader? = null
        private val signatures = ClrSignatureDecoder(bytes, ::encodedTypeName)

        private val typeDefNames = HashMap<Int, String>()
        private var enclosingTypes: Map<Int, Int> = emptyMap()
        private var typeGenerics: Map<Int, List<String>> = emptyMap()
        private var methodGenerics: Map<Int, List<String>> = emptyMap()
        private var interfaces: Map<Int, List<String>> = emptyMap()
        private var fieldOwners = IntArray(0)

        fun decode(): ClrMetadata {
            val metadataOffset = resolve(header.metadataRva)
            val metadataSize = header.metadataSize.toBoundedInt()
            val root = if (metadataOffset != null && metadataSize != null && bytes.containsRange(metadataOffset, metadataSize)) {
                parseRoot(metadataOffset, metadataSize)
            } else {
                null
            }
            if (root == null) {
                warn("clr.invalid_metadata_root", "CLR metadata root is missing or malformed.", header.headerRange)
                return result(root = null, streams = emptyList(), tablesHeader = null)
            }
            val streams = parseStreams(metadataOffset!!, metadataSize!!, root)
            fun stream(name: String) = streams.firstOrNull { it.name == name }?.contentRange
            heaps = Heaps(bytes, stream("#Strings"), stream("#Blob"), stream("#GUID"), stream("#US"))
            val pdbStream = streams.firstOrNull { it.name == "#Pdb" }
            pdbHeader = pdbStream?.contentRange?.let { parsePdbStream(it) }
            if (pdbStream != null && pdbHeader == null) {
                warn("clr.invalid_pdb_stream", "Portable PDB #Pdb stream is malformed.", pdbStream.headerRange)
            }

            val tablesStream = streams.firstOrNull { it.name == "#~" || it.name == "#-" }
            val tablesHeader = tablesStream?.contentRange?.let { range -> parseTables(tablesStream.name, range) }
            if (tablesStream != null && tablesHeader == null) {
                warn("clr.invalid_tables", "CLR metadata tables stream is malformed.", tablesStream.headerRange)
            }
            return result(root, streams, tablesHeader)
        }

        private fun result(root: ClrMetadataRoot?, streams: List<ClrStream>, tablesHeader: ClrTablesHeader?): ClrMetadata {
            val t = tables
            if (t != null) prepareTypeResolution(t)
            val typeDefs = t?.let { decodeTypeDefs(it) }.orEmpty()
            val (fields, methods) = t?.let { decodeMembers(it) } ?: (emptyList<ClrField>() to emptyList())
            return ClrMetadata(
                header = header,
                root = root,
                streams = streams,
                tablesHeader = tablesHeader,
                tables = t?.infos.orEmpty(),
                module = t?.let { decodeModule(it) },
                assembly = t?.let { decodeAssembly(it) },
                targetFramework = t?.let { decodeTargetFramework(it) },
                entryPoint = describeEntryPoint(t, typeDefs, methods),
                readyToRun = readyToRun,
                typeRefs = t?.let { decodeTypeRefs(it) }.orEmpty(),
                typeDefs = typeDefs,
                fields = fields,
                methods = methods,
                memberRefs = t?.let { decodeMemberRefs(it) }.orEmpty(),
                moduleRefs = t?.let { decodeModuleRefs(it) }.orEmpty(),
                assemblyRefs = t?.let { decodeAssemblyRefs(it) }.orEmpty(),
                manifestResources = t?.let { decodeManifestResources(it) }.orEmpty(),
                userStrings = heaps.userStrings(),
                diagnostics = diagnostics,
                tokens = Resolver(methods),
                portablePdb = pdbHeader?.let { header -> t?.let { decodePortablePdb(it, header) } },
            )
        }

        // region Metadata root, streams and tables stream

        private fun parseRoot(offset: Int, size: Int): ClrMetadataRoot? {
            if (size < METADATA_ROOT_MIN_SIZE || bytes.u32LeOrNull(offset) != METADATA_SIGNATURE) return null
            val versionLength = bytes.u32LeOrNull(offset + 12)?.toBoundedInt() ?: return null
            if (versionLength > MAX_VERSION_LENGTH) return null
            val flagsOffset = checkedAdd(offset + 16, align4(versionLength)) ?: return null
            if (flagsOffset + 4 > offset + size) return null
            return ClrMetadataRoot(
                majorVersion = bytes.u16LeOrNull(offset + 4) ?: return null,
                minorVersion = bytes.u16LeOrNull(offset + 6) ?: return null,
                version = bytes.nullTerminatedString(offset + 16, versionLength),
                flags = bytes.u16LeOrNull(flagsOffset) ?: return null,
                streamCount = bytes.u16LeOrNull(flagsOffset + 2) ?: return null,
                range = NativeByteRange(offset, flagsOffset + 4 - offset),
            )
        }

        private fun parseStreams(metadataOffset: Int, metadataSize: Int, root: ClrMetadataRoot): List<ClrStream> = buildList {
            val metadataEnd = metadataOffset + metadataSize
            var position = root.range.offset + root.range.size
            repeat(root.streamCount.coerceAtMost(MAX_STREAMS)) { index ->
                val offset = bytes.u32LeOrNull(position)
                val size = bytes.u32LeOrNull(position + 4)
                if (offset == null || size == null || position + 8 >= metadataEnd) {
                    warn("clr.invalid_stream_header", "CLR stream header #$index is truncated.", NativeByteRange(position, 0))
                    return@buildList
                }
                val name = bytes.nullTerminatedString(position + 8, MAX_STREAM_NAME_LENGTH.coerceAtMost(metadataEnd - position - 8))
                val headerSize = 8 + align4(name.length + 1)
                val start = offset.toBoundedInt()?.let { checkedAdd(metadataOffset, it) }
                val length = size.toBoundedInt()
                val contentRange = if (start != null && length != null && start <= metadataEnd && length <= metadataEnd - start) {
                    NativeByteRange(start, length)
                } else {
                    warn("clr.invalid_stream_range", "CLR stream '$name' points outside the metadata.", NativeByteRange(position, headerSize))
                    null
                }
                add(ClrStream(index, name, offset, size, NativeByteRange(position, headerSize), contentRange))
                position += headerSize
            }
        }

        private fun parseTables(streamName: String, range: NativeByteRange): ClrTablesHeader? {
            val offset = range.offset
            val end = range.offset + range.size
            if (range.size < TABLES_HEADER_SIZE) return null
            val heapSizes = bytes.byteOrNull(offset + 6) ?: return null
            val valid = bytes.u64LeOrNull(offset + 8) ?: return null
            val sorted = bytes.u64LeOrNull(offset + 16) ?: return null

            val rowCounts = IntArray(64)
            var position = offset + TABLES_HEADER_SIZE
            for (id in 0 until 64) {
                if (valid and (1uL shl id) == 0uL) continue
                if (position + 4 > end) return null
                rowCounts[id] = bytes.u32LeOrNull(position)?.toBoundedInt() ?: return null
                position += 4
            }
            if (heapSizes and EXTRA_DATA_FLAG != 0) position += 4
            val tablesHeader = ClrTablesHeader(
                streamName = streamName,
                majorVersion = bytes.byteOrNull(offset + 4) ?: return null,
                minorVersion = bytes.byteOrNull(offset + 5) ?: return null,
                heapSizes = heapSizes,
                validMask = valid,
                sortedMask = sorted,
                range = NativeByteRange(offset, position - offset),
            )
            tables = layoutTables(rowCounts, heapSizes, valid, sorted, position, end)
            return tablesHeader
        }

        private fun layoutTables(rowCounts: IntArray, heapSizes: Int, valid: ULong, sorted: ULong, start: Int, end: Int): Tables {
            // Portable PDB tables index the described assembly's tables, whose row counts only #Pdb knows.
            val referenced = pdbHeader?.referencedTableRows.orEmpty()
            val sizing = ColumnSizing(IntArray(64) { maxOf(rowCounts[it], referenced[it] ?: 0) }, heapSizes)
            val infos = mutableListOf<ClrTableInfo>()
            val layouts = arrayOfNulls<TableLayout>(64)
            var position: Int? = start
            for (id in 0 until 64) {
                if (valid and (1uL shl id) == 0uL) continue
                val schema = CLR_TABLE_SCHEMAS.getOrNull(id)
                val rowCount = rowCounts[id]
                val isSorted = sorted and (1uL shl id) != 0uL
                if (schema == null) {
                    // Rows of an unknown table cannot be skipped, so every later table is unreachable.
                    if (position != null) {
                        warn("clr.unknown_table", "CLR metadata table ${clrTableName(id)} is not supported; later tables are not decoded.", null)
                    }
                    infos.add(ClrTableInfo(id, clrTableName(id), rowCount, 0, isSorted, null))
                    position = null
                    continue
                }
                val columnSizes = IntArray(schema.columns.size) { sizing.size(schema.columns[it]) }
                val rowSize = columnSizes.sum()
                val tableSize = rowCount.toLong() * rowSize
                val tableStart = position
                val range = if (tableStart != null && tableSize <= end - tableStart) {
                    NativeByteRange(tableStart, tableSize.toInt())
                } else {
                    if (tableStart != null) {
                        warn("clr.truncated_table", "CLR metadata table ${schema.name} extends past the tables stream.", null)
                    }
                    null
                }
                if (range != null) layouts[id] = TableLayout(range.offset, rowCount, rowSize, columnSizes)
                infos.add(ClrTableInfo(id, schema.name, rowCount, rowSize, isSorted, range))
                position = range?.let { it.offset + it.size }
            }
            return Tables(bytes, layouts, infos)
        }

        /** `#Pdb`: 20-byte PDB id, entry point token, referenced-tables mask and their row counts. */
        private fun parsePdbStream(range: NativeByteRange): ClrPdbStreamHeader? {
            if (range.size < PDB_STREAM_FIXED_SIZE) return null
            val guid = bytes.guidLeString(range.offset) ?: return null
            val mask = bytes.u64LeOrNull(range.offset + 24) ?: return null
            val rows = LinkedHashMap<Int, Int>()
            var position = range.offset + PDB_STREAM_FIXED_SIZE
            for (id in 0 until 64) {
                if (mask and (1uL shl id) == 0uL) continue
                if (position + 4 > range.offset + range.size) return null
                rows[id] = bytes.u32LeOrNull(position)?.toBoundedInt() ?: return null
                position += 4
            }
            return ClrPdbStreamHeader(
                guid = guid,
                stamp = bytes.u32LeOrNull(range.offset + 16) ?: return null,
                entryPointToken = bytes.u32LeOrNull(range.offset + 20) ?: return null,
                referencedTableRows = rows,
                range = NativeByteRange(range.offset, position - range.offset),
            )
        }

        // endregion

        // region Portable PDB

        private fun decodePortablePdb(t: Tables, header: ClrPdbStreamHeader): ClrPortablePdb {
            val documents = (1..t.rowCount(T.DOCUMENT).coerceAtMost(MAX_PDB_DOCUMENTS)).map { row ->
                val hash = heaps.blobBytes(t.cell(T.DOCUMENT, row, 2))
                ClrPdbDocument(
                    row = row,
                    name = documentName(t.cell(T.DOCUMENT, row, 0)),
                    hashAlgorithm = heaps.guid(t.cell(T.DOCUMENT, row, 1))?.let(ClrPdbGuids::hashAlgorithmName),
                    hash = hash.joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') },
                    language = heaps.guid(t.cell(T.DOCUMENT, row, 3))?.let(ClrPdbGuids::languageName),
                    recordRange = t.rowRange(T.DOCUMENT, row),
                )
            }
            val methodRows = t.rowCount(T.METHOD_DEBUG_INFORMATION)
            val withPoints = (1..methodRows).count { t.cell(T.METHOD_DEBUG_INFORMATION, it, 1) != 0 }
            return ClrPortablePdb(header, documents, methodRows, withPoints) { row -> decodeSequencePoints(t, row) }
        }

        /**
         * MethodDebugInformation.SequencePoints: a header (local signature, plus the initial document
         * when the row names none), then records — document changes (`δIL = 0` after the first record)
         * and sequence points, whose lines and columns are deltas from the previous non-hidden point.
         */
        private fun decodeSequencePoints(t: Tables, row: Int): List<ClrSequencePoint> {
            val range = heaps.blob(t.cell(T.METHOD_DEBUG_INFORMATION, row, 1)) ?: return emptyList()
            if (range.size == 0) return emptyList()
            val end = range.offset + range.size
            var position = range.offset
            fun unsigned(): Int? = readCompressed(bytes, position, end)?.let { (value, size) -> position += size; value }
            fun signed(): Int? = readCompressedSigned(bytes, position, end)?.let { (value, size) -> position += size; value }

            unsigned() ?: return emptyList() // local signature
            var document = t.cell(T.METHOD_DEBUG_INFORMATION, row, 0)
            if (document == 0) document = unsigned() ?: return emptyList()
            val points = mutableListOf<ClrSequencePoint>()
            var ilOffset = 0
            var startLine = 0
            var startColumn = 0
            var first = true
            var firstVisible = true
            while (position < end && points.size < MAX_SEQUENCE_POINTS) {
                val deltaIl = unsigned() ?: break
                if (deltaIl == 0 && !first) {
                    document = unsigned() ?: break
                    continue
                }
                ilOffset = if (first) deltaIl else ilOffset + deltaIl
                first = false
                val deltaLines = unsigned() ?: break
                val deltaColumns = (if (deltaLines == 0) unsigned() else signed()) ?: break
                if (deltaLines == 0 && deltaColumns == 0) {
                    points.add(ClrSequencePoint(ilOffset, document, 0, 0, 0, 0))
                    continue
                }
                if (firstVisible) {
                    startLine = unsigned() ?: break
                    startColumn = unsigned() ?: break
                    firstVisible = false
                } else {
                    startLine += signed() ?: break
                    startColumn += signed() ?: break
                }
                points.add(ClrSequencePoint(ilOffset, document, startLine, startColumn, startLine + deltaLines, startColumn + deltaColumns))
            }
            return points
        }

        /**
         * Document name blob: a separator character (0 for none), then compressed `#Blob` indices
         * of the UTF-8 parts, joined with the separator.
         */
        private fun documentName(blobIndex: Int): String {
            val range = heaps.blob(blobIndex) ?: return ""
            if (range.size == 0) return ""
            val end = range.offset + range.size
            val separator = bytes.byteOrNull(range.offset) ?: return ""
            var position = range.offset + 1
            val parts = mutableListOf<String>()
            var length = 0
            while (position < end && parts.size < MAX_DOCUMENT_NAME_PARTS) {
                val (part, prefix) = readCompressed(bytes, position, end) ?: break
                position += prefix
                val text = heaps.blobBytes(part).decodeToString()
                length += text.length
                if (length > MAX_DOCUMENT_NAME_LENGTH) break
                parts.add(text)
            }
            return if (separator == 0) parts.joinToString("") else parts.joinToString(separator.toChar().toString())
        }

        // endregion

        // region Name resolution

        private fun prepareTypeResolution(t: Tables) {
            enclosingTypes = buildMap {
                for (row in 1..t.rowCount(T.NESTED_CLASS)) {
                    put(t.cell(T.NESTED_CLASS, row, 0), t.cell(T.NESTED_CLASS, row, 1))
                }
            }
            val typeParameters = HashMap<Int, MutableList<Pair<Int, String>>>()
            val methodParameters = HashMap<Int, MutableList<Pair<Int, String>>>()
            for (row in 1..t.rowCount(T.GENERIC_PARAM)) {
                val number = t.cell(T.GENERIC_PARAM, row, 0)
                val owner = t.cell(T.GENERIC_PARAM, row, 2)
                val name = heaps.string(t.cell(T.GENERIC_PARAM, row, 3))
                val target = if (owner and 1 == 0) typeParameters else methodParameters
                target.getOrPut(owner ushr 1) { mutableListOf() }.add(number to name)
            }
            typeGenerics = typeParameters.mapValues { (_, list) -> list.sortedBy { it.first }.map { it.second } }
            methodGenerics = methodParameters.mapValues { (_, list) -> list.sortedBy { it.first }.map { it.second } }
            interfaces = buildMap<Int, MutableList<String>> {
                for (row in 1..t.rowCount(T.INTERFACE_IMPL)) {
                    val name = typeDefOrRefName(t.cell(T.INTERFACE_IMPL, row, 1)) ?: continue
                    getOrPut(t.cell(T.INTERFACE_IMPL, row, 0)) { mutableListOf() }.add(name)
                }
            }
        }

        private fun typeDefFullName(row: Int, depth: Int = 0): String = typeDefNames.getOrPut(row) {
            val t = tables ?: return@getOrPut "<type #$row>"
            if (row !in 1..t.rowCount(T.TYPE_DEF)) return@getOrPut "<type #$row>"
            val name = heaps.string(t.cell(T.TYPE_DEF, row, 1))
            val namespace = heaps.string(t.cell(T.TYPE_DEF, row, 2))
            val enclosing = enclosingTypes[row]
            when {
                enclosing != null && enclosing != row && depth < MAX_NESTING -> typeDefFullName(enclosing, depth + 1) + "+" + name
                namespace.isEmpty() -> name
                else -> "$namespace.$name"
            }
        }

        private fun typeRefFullName(row: Int, depth: Int = 0): String {
            val t = tables ?: return "<typeref #$row>"
            if (row !in 1..t.rowCount(T.TYPE_REF)) return "<typeref #$row>"
            val name = heaps.string(t.cell(T.TYPE_REF, row, 1))
            val namespace = heaps.string(t.cell(T.TYPE_REF, row, 2))
            val scope = decodeCoded(ClrCodedIndex.ResolutionScope, t.cell(T.TYPE_REF, row, 0))
            if (scope?.first == T.TYPE_REF && scope.second != row && depth < MAX_NESTING) {
                return typeRefFullName(scope.second, depth + 1) + "+" + name
            }
            return if (namespace.isEmpty()) name else "$namespace.$name"
        }

        private fun typeSpecName(row: Int, context: ClrGenericContext, depth: Int): String {
            val t = tables ?: return "<typespec #$row>"
            if (row !in 1..t.rowCount(T.TYPE_SPEC)) return "<typespec #$row>"
            return signatures.typeSpec(heaps.blob(t.cell(T.TYPE_SPEC, row, 0)), context, depth)
        }

        /** Short display name used inside signatures: no namespace, no generic arity suffix. */
        private fun encodedTypeName(encoded: Int, context: ClrGenericContext, depth: Int): String {
            val row = encoded ushr 2
            return when (encoded and 3) {
                0 -> shortTypeName(typeDefFullName(row))
                1 -> shortTypeName(typeRefFullName(row))
                2 -> typeSpecName(row, context, depth)
                else -> "<invalid type>"
            }
        }

        private fun typeDefOrRefName(coded: Int): String? {
            val (table, row) = decodeCoded(ClrCodedIndex.TypeDefOrRef, coded) ?: return null
            return when (table) {
                T.TYPE_DEF -> typeDefFullName(row)
                T.TYPE_REF -> typeRefFullName(row)
                else -> typeSpecName(row, ClrGenericContext.Empty, 0)
            }
        }

        private fun decodeCoded(kind: ClrCodedIndex, value: Int): Pair<Int, Int>? {
            val tag = value and ((1 shl kind.tagBits) - 1)
            val row = value ushr kind.tagBits
            val table = kind.tables.getOrNull(tag)?.takeIf { it >= 0 } ?: return null
            return if (row == 0) null else table to row
        }

        // endregion

        // region Tables

        private fun decodeModule(t: Tables): ClrModule? {
            if (t.rowCount(T.MODULE) == 0) return null
            return ClrModule(
                generation = t.cell(T.MODULE, 1, 0),
                name = heaps.string(t.cell(T.MODULE, 1, 1)),
                mvid = heaps.guid(t.cell(T.MODULE, 1, 2)),
                recordRange = t.rowRange(T.MODULE, 1),
            )
        }

        private fun decodeAssembly(t: Tables): ClrAssembly? {
            if (t.rowCount(T.ASSEMBLY) == 0) return null
            return ClrAssembly(
                name = heaps.string(t.cell(T.ASSEMBLY, 1, 7)),
                version = ClrVersion(t.cell(T.ASSEMBLY, 1, 1), t.cell(T.ASSEMBLY, 1, 2), t.cell(T.ASSEMBLY, 1, 3), t.cell(T.ASSEMBLY, 1, 4)),
                culture = heaps.string(t.cell(T.ASSEMBLY, 1, 8)),
                flags = t.cell(T.ASSEMBLY, 1, 5).toUInt(),
                hashAlgorithm = t.cell(T.ASSEMBLY, 1, 0).toUInt(),
                publicKey = heaps.blobBytes(t.cell(T.ASSEMBLY, 1, 6)),
                recordRange = t.rowRange(T.ASSEMBLY, 1),
            )
        }

        private fun decodeAssemblyRefs(t: Tables): List<ClrAssemblyRef> = List(t.rowCount(T.ASSEMBLY_REF)) { index ->
            val row = index + 1
            ClrAssemblyRef(
                row = row,
                name = heaps.string(t.cell(T.ASSEMBLY_REF, row, 6)),
                version = ClrVersion(t.cell(T.ASSEMBLY_REF, row, 0), t.cell(T.ASSEMBLY_REF, row, 1), t.cell(T.ASSEMBLY_REF, row, 2), t.cell(T.ASSEMBLY_REF, row, 3)),
                culture = heaps.string(t.cell(T.ASSEMBLY_REF, row, 7)),
                flags = t.cell(T.ASSEMBLY_REF, row, 4).toUInt(),
                publicKeyOrToken = heaps.blobBytes(t.cell(T.ASSEMBLY_REF, row, 5)),
                recordRange = t.rowRange(T.ASSEMBLY_REF, row),
            )
        }

        private fun decodeModuleRefs(t: Tables): List<ClrModuleRef> = List(t.rowCount(T.MODULE_REF)) { index ->
            val row = index + 1
            ClrModuleRef(row, heaps.string(t.cell(T.MODULE_REF, row, 0)), t.rowRange(T.MODULE_REF, row))
        }

        private fun decodeTypeRefs(t: Tables): List<ClrTypeRef> = List(t.rowCount(T.TYPE_REF)) { index ->
            val row = index + 1
            ClrTypeRef(
                row = row,
                namespace = heaps.string(t.cell(T.TYPE_REF, row, 2)),
                name = heaps.string(t.cell(T.TYPE_REF, row, 1)),
                fullName = typeRefFullName(row),
                scope = typeRefScope(t, row),
                recordRange = t.rowRange(T.TYPE_REF, row),
            )
        }

        private fun typeRefScope(t: Tables, row: Int): String {
            var current = row
            repeat(MAX_NESTING) {
                val scope = decodeCoded(ClrCodedIndex.ResolutionScope, t.cell(T.TYPE_REF, current, 0)) ?: return ""
                when (scope.first) {
                    T.MODULE -> return "<this module>"
                    T.MODULE_REF -> return heaps.string(t.cell(T.MODULE_REF, scope.second, 0))
                    T.ASSEMBLY_REF -> return heaps.string(t.cell(T.ASSEMBLY_REF, scope.second, 6))
                    else -> current = scope.second
                }
            }
            return ""
        }

        private fun decodeTypeDefs(t: Tables): List<ClrTypeDef> = List(t.rowCount(T.TYPE_DEF)) { index ->
            val row = index + 1
            val flags = t.cell(T.TYPE_DEF, row, 0).toUInt()
            val fullName = typeDefFullName(row)
            val extends = typeDefOrRefName(t.cell(T.TYPE_DEF, row, 3))
            ClrTypeDef(
                row = row,
                namespace = heaps.string(t.cell(T.TYPE_DEF, row, 2)),
                name = heaps.string(t.cell(T.TYPE_DEF, row, 1)),
                fullName = fullName,
                flags = flags,
                kind = when {
                    row == 1 && fullName == "<Module>" -> ClrTypeKind.Module
                    flags and TYPE_INTERFACE != 0u -> ClrTypeKind.Interface
                    extends == "System.Enum" -> ClrTypeKind.Enum
                    extends == "System.ValueType" && fullName != "System.Enum" -> ClrTypeKind.Struct
                    extends == "System.MulticastDelegate" -> ClrTypeKind.Delegate
                    else -> ClrTypeKind.Class
                },
                extends = extends,
                interfaces = interfaces[row].orEmpty(),
                enclosingTypeRow = enclosingTypes[row],
                genericParameters = typeGenerics[row].orEmpty(),
                recordRange = t.rowRange(T.TYPE_DEF, row),
            )
        }

        private fun decodeMembers(t: Tables): Pair<List<ClrField>, List<ClrMethodDef>> {
            fieldOwners = memberOwners(t, column = 4, T.FIELD_PTR, T.FIELD)
            val methodOwners = memberOwners(t, column = 5, T.METHOD_PTR, T.METHOD_DEF)
            val parameterNames = parameterNames(t)

            val fields = List(t.rowCount(T.FIELD)) { index ->
                val row = index + 1
                val owner = fieldOwners[row]
                ClrField(
                    row = row,
                    name = heaps.string(t.cell(T.FIELD, row, 1)),
                    flags = t.cell(T.FIELD, row, 0),
                    type = signatures.fieldType(heaps.blob(t.cell(T.FIELD, row, 2)), ClrGenericContext(typeGenerics[owner].orEmpty())),
                    declaringTypeRow = owner,
                    recordRange = t.rowRange(T.FIELD, row),
                )
            }
            val methods = List(t.rowCount(T.METHOD_DEF)) { index ->
                val row = index + 1
                val owner = methodOwners[row]
                val rva = t.cell(T.METHOD_DEF, row, 0).toUInt()
                val implFlags = t.cell(T.METHOD_DEF, row, 1)
                val name = heaps.string(t.cell(T.METHOD_DEF, row, 3))
                ClrMethodDef(
                    row = row,
                    name = name,
                    rva = rva,
                    implFlags = implFlags,
                    flags = t.cell(T.METHOD_DEF, row, 2),
                    signature = signatures.methodSignature(
                        blob = heaps.blob(t.cell(T.METHOD_DEF, row, 4)),
                        name = name,
                        parameterNames = parameterNames[row].orEmpty(),
                        context = ClrGenericContext(typeGenerics[owner].orEmpty(), methodGenerics[row].orEmpty()),
                    ),
                    declaringTypeRow = owner,
                    // ImplFlags code type (II.23.1.11): only IL methods have an IL body at their RVA;
                    // native (C++/CLI), OPTIL and runtime methods point at something else.
                    body = if (rva != 0u && implFlags and CODE_TYPE_MASK == CODE_TYPE_IL) resolve(rva)?.let(::parseMethodBody) else null,
                    recordRange = t.rowRange(T.METHOD_DEF, row),
                )
            }
            return fields to methods
        }

        /**
         * Maps each member row (1-based) to the TypeDef row that owns it. A TypeDef owns the run of
         * its member list column up to the next TypeDef's start; in uncompressed `#-` streams the
         * list indexes a Ptr table that holds the real member rows.
         */
        private fun memberOwners(t: Tables, column: Int, pointerTable: Int, memberTable: Int): IntArray {
            val memberCount = t.rowCount(memberTable)
            val owners = IntArray(memberCount + 1)
            val usesPointers = t.rowCount(pointerTable) > 0
            forEachMemberList(t, T.TYPE_DEF, column, if (usesPointers) t.rowCount(pointerTable) else memberCount) { typeRow, list ->
                for (listIndex in list) {
                    val member = if (usesPointers) t.cell(pointerTable, listIndex, 0) else listIndex
                    if (member in 1..memberCount) owners[member] = typeRow
                }
            }
            return owners
        }

        /** Parameter names per MethodDef row, indexed by parameter position (sequence 1 is index 0). */
        private fun parameterNames(t: Tables): Map<Int, List<String?>> {
            val paramCount = t.rowCount(T.PARAM)
            if (paramCount == 0) return emptyMap()
            val usesPointers = t.rowCount(T.PARAM_PTR) > 0
            val result = HashMap<Int, List<String?>>()
            forEachMemberList(t, T.METHOD_DEF, 5, if (usesPointers) t.rowCount(T.PARAM_PTR) else paramCount) { methodRow, list ->
                if (list.isEmpty()) return@forEachMemberList
                fun param(listIndex: Int) = if (usesPointers) t.cell(T.PARAM_PTR, listIndex, 0) else listIndex
                // Sequence 0 is the return value; positions can be sparse, so size by the largest one.
                var maxSequence = 0
                for (listIndex in list) {
                    val param = param(listIndex).takeIf { it in 1..paramCount } ?: continue
                    t.cell(T.PARAM, param, 1).takeIf { it in 1..MAX_PARAMETERS }?.let { maxSequence = maxOf(maxSequence, it) }
                }
                if (maxSequence == 0) return@forEachMemberList
                val names = arrayOfNulls<String>(maxSequence)
                for (listIndex in list) {
                    val param = param(listIndex).takeIf { it in 1..paramCount } ?: continue
                    val sequence = t.cell(T.PARAM, param, 1).takeIf { it in 1..maxSequence } ?: continue
                    names[sequence - 1] = heaps.string(t.cell(T.PARAM, param, 2))
                }
                result[methodRow] = names.asList()
            }
            return result
        }

        /**
         * Calls [action] with each [ownerTable] row and the run of list indices it owns: from its
         * [column] value up to the next row's (II.22: these lists are ascending and contiguous).
         * Malformed metadata may repeat or reorder starts; runs are clamped so they never overlap,
         * which keeps the work linear in the list length instead of rows × list length.
         */
        private inline fun forEachMemberList(t: Tables, ownerTable: Int, column: Int, listLength: Int, action: (row: Int, list: IntRange) -> Unit) {
            val ownerCount = t.rowCount(ownerTable)
            var claimedUntil = 1
            var unordered = false
            for (row in 1..ownerCount) {
                val declaredStart = t.cell(ownerTable, row, column)
                if (declaredStart < claimedUntil) unordered = true
                val start = declaredStart.coerceIn(claimedUntil, listLength + 1)
                val nextStart = if (row < ownerCount) t.cell(ownerTable, row + 1, column) else listLength + 1
                val end = nextStart.coerceIn(start, listLength + 1)
                action(row, start until end)
                claimedUntil = end
            }
            if (unordered) {
                warn(
                    "clr.unordered_member_lists",
                    "${clrTableName(ownerTable)} member lists are not ascending; overlapping runs were ignored.",
                    t.infos.firstOrNull { it.id == ownerTable }?.range,
                )
            }
        }

        private fun decodeMemberRefs(t: Tables): List<ClrMemberRef> = List(t.rowCount(T.MEMBER_REF)) { index ->
            val row = index + 1
            val parent = decodeCoded(ClrCodedIndex.MemberRefParent, t.cell(T.MEMBER_REF, row, 0))
            val parentRow = parent?.second ?: 0
            val name = heaps.string(t.cell(T.MEMBER_REF, row, 1))
            val blob = heaps.blob(t.cell(T.MEMBER_REF, row, 2))
            val isField = signatures.isFieldSignature(blob)
            val context = if (parent?.first == T.TYPE_DEF) ClrGenericContext(typeGenerics[parentRow].orEmpty()) else ClrGenericContext.Empty
            ClrMemberRef(
                row = row,
                parent = when (parent?.first) {
                    T.TYPE_DEF -> typeDefFullName(parentRow)
                    T.TYPE_REF -> typeRefFullName(parentRow)
                    T.MODULE_REF -> heaps.string(t.cell(T.MODULE_REF, parentRow, 0))
                    T.METHOD_DEF -> heaps.string(t.cell(T.METHOD_DEF, parentRow, 3))
                    T.TYPE_SPEC -> typeSpecName(parentRow, ClrGenericContext.Empty, 0)
                    else -> ""
                },
                name = name,
                signature = if (isField) signatures.fieldType(blob, context) else signatures.methodSignature(blob, name, emptyList(), context),
                isField = isField,
                recordRange = t.rowRange(T.MEMBER_REF, row),
            )
        }

        private fun decodeManifestResources(t: Tables): List<ClrManifestResource> {
            val resourcesOffset = if (header.resourcesRva != 0u) resolve(header.resourcesRva) else null
            val resourcesSize = header.resourcesSize.toBoundedInt() ?: 0
            return List(t.rowCount(T.MANIFEST_RESOURCE)) { index ->
                val row = index + 1
                val offset = t.cell(T.MANIFEST_RESOURCE, row, 0).toUInt()
                val implementation = decodeCoded(ClrCodedIndex.Implementation, t.cell(T.MANIFEST_RESOURCE, row, 3))
                var size: UInt? = null
                var dataRange: NativeByteRange? = null
                if (implementation == null && resourcesOffset != null) {
                    val relative = offset.toBoundedInt()
                    val lengthOffset = relative?.let { checkedAdd(resourcesOffset, it) }
                    if (relative != null && lengthOffset != null && relative + 4 <= resourcesSize) {
                        size = bytes.u32LeOrNull(lengthOffset)
                        val length = size?.toBoundedInt()
                        if (length != null && length <= resourcesSize - relative - 4 && bytes.containsRange(lengthOffset + 4, length)) {
                            dataRange = NativeByteRange(lengthOffset + 4, length)
                        }
                    }
                }
                ClrManifestResource(
                    row = row,
                    name = heaps.string(t.cell(T.MANIFEST_RESOURCE, row, 2)),
                    offset = offset,
                    flags = t.cell(T.MANIFEST_RESOURCE, row, 1).toUInt(),
                    implementation = when (implementation?.first) {
                        null -> "embedded"
                        T.FILE -> "file " + heaps.string(t.cell(T.FILE, implementation.second, 1))
                        T.ASSEMBLY_REF -> "assembly " + heaps.string(t.cell(T.ASSEMBLY_REF, implementation.second, 6))
                        else -> "exported type #${implementation.second}"
                    },
                    size = size,
                    dataRange = dataRange,
                    recordRange = t.rowRange(T.MANIFEST_RESOURCE, row),
                )
            }
        }

        /** Reads `[assembly: TargetFramework("...")]` from the CustomAttribute table. */
        private fun decodeTargetFramework(t: Tables): String? {
            for (row in 1..t.rowCount(T.CUSTOM_ATTRIBUTE)) {
                val parent = decodeCoded(ClrCodedIndex.HasCustomAttribute, t.cell(T.CUSTOM_ATTRIBUTE, row, 0)) ?: continue
                if (parent.first != T.ASSEMBLY) continue
                val constructor = decodeCoded(ClrCodedIndex.CustomAttributeType, t.cell(T.CUSTOM_ATTRIBUTE, row, 1)) ?: continue
                if (constructor.first != T.MEMBER_REF || constructor.second > t.rowCount(T.MEMBER_REF)) continue
                val attributeType = decodeCoded(ClrCodedIndex.MemberRefParent, t.cell(T.MEMBER_REF, constructor.second, 0)) ?: continue
                val typeName = when (attributeType.first) {
                    T.TYPE_REF -> typeRefFullName(attributeType.second)
                    T.TYPE_DEF -> typeDefFullName(attributeType.second)
                    else -> continue
                }
                if (typeName != TARGET_FRAMEWORK_ATTRIBUTE) continue
                val value = heaps.blob(t.cell(T.CUSTOM_ATTRIBUTE, row, 2)) ?: continue
                return readSerString(value)
            }
            return null
        }

        /** First fixed argument of a custom attribute blob (II.23.3): prolog `0x0001` then a SerString. */
        private fun readSerString(blob: NativeByteRange): String? {
            if (blob.size < 3 || bytes.u16LeOrNull(blob.offset) != 0x0001) return null
            val (length, prefix) = readCompressed(bytes, blob.offset + 2, blob.offset + blob.size) ?: return null
            val start = blob.offset + 2 + prefix
            if (length > blob.offset + blob.size - start) return null
            return ByteArray(length) { bytes[start + it] }.decodeToString()
        }

        private fun describeEntryPoint(t: Tables?, typeDefs: List<ClrTypeDef>, methods: List<ClrMethodDef>): String? {
            val token = header.entryPointToken
            if (token == 0u) return null
            if (header.hasNativeEntryPoint) return "native RVA 0x${token.toString(16).uppercase()}"
            val table = (token shr 24).toInt()
            val row = (token and 0x00FFFFFFu).toInt()
            return when (table) {
                T.METHOD_DEF -> methods.getOrNull(row - 1)?.let { method ->
                    val type = typeDefs.getOrNull(method.declaringTypeRow - 1)?.fullName
                    if (type != null) "$type::${method.name}" else method.name
                }
                T.FILE -> t?.takeIf { row in 1..it.rowCount(T.FILE) }?.let { "file " + heaps.string(it.cell(T.FILE, row, 1)) }
                else -> null
            } ?: "token 0x${token.toString(16).uppercase().padStart(8, '0')}"
        }

        private fun parseMethodBody(offset: Int): ClrMethodBody? {
            val first = bytes.byteOrNull(offset) ?: return null
            return when (first and 0x3) {
                TINY_FORMAT -> {
                    val codeSize = first ushr 2
                    if (!bytes.containsRange(offset + 1, codeSize)) return null
                    ClrMethodBody(
                        isFat = false,
                        maxStack = 8,
                        codeSize = codeSize,
                        localVarSigToken = 0u,
                        hasExceptionSections = false,
                        range = NativeByteRange(offset, 1 + codeSize),
                        codeRange = NativeByteRange(offset + 1, codeSize),
                    )
                }
                FAT_FORMAT -> {
                    val flags = bytes.u16LeOrNull(offset) ?: return null
                    val headerSize = (flags ushr 12) * 4
                    if (headerSize < FAT_HEADER_SIZE) return null
                    val codeSize = bytes.u32LeOrNull(offset + 4)?.toBoundedInt() ?: return null
                    val codeOffset = offset + headerSize
                    if (!bytes.containsRange(codeOffset, codeSize)) return null
                    val exceptionSections = if (flags and MORE_SECTIONS != 0) parseExceptionSections(codeOffset + codeSize) else null
                    ClrMethodBody(
                        isFat = true,
                        maxStack = bytes.u16LeOrNull(offset + 2) ?: return null,
                        codeSize = codeSize,
                        localVarSigToken = bytes.u32LeOrNull(offset + 8) ?: return null,
                        hasExceptionSections = flags and MORE_SECTIONS != 0,
                        range = NativeByteRange(offset, headerSize + codeSize),
                        codeRange = NativeByteRange(codeOffset, codeSize),
                        exceptionClauses = exceptionSections?.first.orEmpty(),
                        initLocals = flags and INIT_LOCALS != 0,
                        exceptionSectionsRange = exceptionSections?.second,
                    )
                }
                else -> null
            }
        }

        /**
         * Exception handling sections (II.25.4.5) that follow a fat body's code, 4-byte aligned:
         * their clauses, and the bytes from the first section to the end of the last.
         */
        private fun parseExceptionSections(codeEnd: Int): Pair<List<ClrExceptionClause>, NativeByteRange?> {
            val clauses = ArrayList<ClrExceptionClause>()
            val first = align4(codeEnd)
            var position = first
            fun result() = clauses to NativeByteRange(first, position - first).takeIf { it.size > 0 }
            repeat(MAX_EXCEPTION_SECTIONS) {
                val kind = bytes.byteOrNull(position) ?: return result()
                if (kind and SECTION_EH_TABLE == 0) return result()
                val fat = kind and SECTION_FAT_FORMAT != 0
                val dataSize = if (fat) {
                    (bytes.u32LeOrNull(position) ?: return result()).toInt() ushr 8
                } else {
                    bytes.byteOrNull(position + 1) ?: return result()
                }
                if (dataSize < 4 || !bytes.containsRange(position, dataSize)) return result()
                val clauseSize = if (fat) 24 else 12
                for (index in 0 until (dataSize - 4) / clauseSize) {
                    parseExceptionClause(position + 4 + index * clauseSize, fat)?.let(clauses::add)
                }
                val more = kind and SECTION_MORE_SECTIONS != 0
                position += dataSize
                if (!more) return result()
                position = align4(position)
            }
            return result()
        }

        private fun parseExceptionClause(offset: Int, fat: Boolean): ClrExceptionClause? {
            val flags: Int
            val tryOffset: Int
            val tryLength: Int
            val handlerOffset: Int
            val handlerLength: Int
            if (fat) {
                flags = bytes.u32LeOrNull(offset)?.toInt() ?: return null
                tryOffset = bytes.u32LeOrNull(offset + 4)?.toBoundedInt() ?: return null
                tryLength = bytes.u32LeOrNull(offset + 8)?.toBoundedInt() ?: return null
                handlerOffset = bytes.u32LeOrNull(offset + 12)?.toBoundedInt() ?: return null
                handlerLength = bytes.u32LeOrNull(offset + 16)?.toBoundedInt() ?: return null
            } else {
                flags = bytes.u16LeOrNull(offset) ?: return null
                tryOffset = bytes.u16LeOrNull(offset + 2) ?: return null
                tryLength = bytes.byteOrNull(offset + 4) ?: return null
                handlerOffset = bytes.u16LeOrNull(offset + 5) ?: return null
                handlerLength = bytes.byteOrNull(offset + 7) ?: return null
            }
            val classTokenOrFilter = bytes.u32LeOrNull(offset + if (fat) 20 else 8) ?: return null
            val kind = when (flags) {
                0 -> ClrExceptionClauseKind.Catch
                1 -> ClrExceptionClauseKind.Filter
                2 -> ClrExceptionClauseKind.Finally
                4 -> ClrExceptionClauseKind.Fault
                else -> return null
            }
            return ClrExceptionClause(
                kind = kind,
                tryOffset = tryOffset,
                tryLength = tryLength,
                handlerOffset = handlerOffset,
                handlerLength = handlerLength,
                catchType = if (kind == ClrExceptionClauseKind.Catch) typeTokenName(classTokenOrFilter, ClrGenericContext.Empty) else null,
                filterOffset = if (kind == ClrExceptionClauseKind.Filter) classTokenOrFilter.toBoundedInt() else null,
            )
        }

        /** Full name of a TypeDef, TypeRef or TypeSpec token, or null for any other table. */
        private fun typeTokenName(token: UInt, context: ClrGenericContext): String? {
            val row = (token and 0x00FFFFFFu).toInt()
            return when ((token shr 24).toInt()) {
                T.TYPE_DEF -> typeDefFullName(row)
                T.TYPE_REF -> typeRefFullName(row)
                T.TYPE_SPEC -> typeSpecName(row, context, 0)
                else -> null
            }
        }

        // endregion

        // region IL operands

        private inner class Resolver(private val methods: List<ClrMethodDef>) : ClrTokenResolver {
            override fun describe(token: UInt, method: ClrMethodDef?): String? {
                val table = (token shr 24).toInt()
                val row = (token and 0x00FFFFFFu).toInt()
                if (table == USER_STRING_TOKEN_TABLE) return heaps.userString(row)
                val t = tables ?: return null
                val tableId = table.takeIf { it < 64 } ?: return null
                if (row !in 1..t.rowCount(tableId)) return null
                val context = method?.let(::genericContextOf) ?: ClrGenericContext.Empty
                return when (table) {
                    T.TYPE_DEF -> typeDefDisplayName(row)
                    T.TYPE_REF -> typeRefFullName(row)
                    T.TYPE_SPEC -> typeSpecName(row, context, 0)
                    T.FIELD -> fieldText(t, row)
                    T.METHOD_DEF -> methodDefText(t, row, instantiation = null)
                    T.MEMBER_REF -> memberRefText(t, row, instantiation = null)
                    T.METHOD_SPEC -> methodSpecText(t, row, context)
                    T.STAND_ALONE_SIG -> signatures.methodSignature(heaps.blob(t.cell(T.STAND_ALONE_SIG, row, 0)), "method", emptyList(), context)
                    T.MODULE_REF -> heaps.string(t.cell(T.MODULE_REF, row, 0))
                    else -> null
                }
            }

            override fun localVariableTypes(method: ClrMethodDef): List<String> {
                val token = method.body?.localVarSigToken ?: return emptyList()
                if ((token shr 24).toInt() != T.STAND_ALONE_SIG) return emptyList()
                val t = tables ?: return emptyList()
                val row = (token and 0x00FFFFFFu).toInt()
                if (row !in 1..t.rowCount(T.STAND_ALONE_SIG)) return emptyList()
                return signatures.localTypes(heaps.blob(t.cell(T.STAND_ALONE_SIG, row, 0)), genericContextOf(method)).orEmpty()
            }

            private fun genericContextOf(method: ClrMethodDef) =
                ClrGenericContext(typeGenerics[method.declaringTypeRow].orEmpty(), methodGenerics[method.row].orEmpty())

            private fun fieldText(t: Tables, row: Int): String {
                val owner = fieldOwners.getOrElse(row) { 0 }
                val type = signatures.fieldType(heaps.blob(t.cell(T.FIELD, row, 2)), ClrGenericContext(typeGenerics[owner].orEmpty()))
                return "$type ${typeDefDisplayName(owner)}::${heaps.string(t.cell(T.FIELD, row, 1))}"
            }

            private fun methodDefText(t: Tables, row: Int, instantiation: List<String>?): String {
                val owner = methods.getOrNull(row - 1)?.declaringTypeRow ?: 0
                val name = heaps.string(t.cell(T.METHOD_DEF, row, 3))
                return signatures.methodSignature(
                    blob = heaps.blob(t.cell(T.METHOD_DEF, row, 4)),
                    name = "${typeDefDisplayName(owner)}::$name",
                    parameterNames = emptyList(),
                    context = ClrGenericContext(typeGenerics[owner].orEmpty(), instantiation ?: methodGenerics[row].orEmpty()),
                )
            }

            private fun memberRefText(t: Tables, row: Int, instantiation: List<String>?): String {
                val parent = decodeCoded(ClrCodedIndex.MemberRefParent, t.cell(T.MEMBER_REF, row, 0))
                val parentRow = parent?.second ?: 0
                val parentName = when (parent?.first) {
                    T.TYPE_DEF -> typeDefDisplayName(parentRow)
                    T.TYPE_REF -> typeRefFullName(parentRow)
                    T.TYPE_SPEC -> typeSpecName(parentRow, ClrGenericContext.Empty, 0)
                    T.MODULE_REF -> heaps.string(t.cell(T.MODULE_REF, parentRow, 0))
                    // A vararg call site: the MemberRef refines a MethodDef's signature.
                    T.METHOD_DEF -> return methodDefText(t, parentRow, instantiation)
                    else -> "<parent>"
                }
                val name = heaps.string(t.cell(T.MEMBER_REF, row, 1))
                val blob = heaps.blob(t.cell(T.MEMBER_REF, row, 2))
                val typeParameters = if (parent?.first == T.TYPE_DEF) typeGenerics[parentRow].orEmpty() else emptyList()
                if (signatures.isFieldSignature(blob)) {
                    return "${signatures.fieldType(blob, ClrGenericContext(typeParameters))} $parentName::$name"
                }
                return signatures.methodSignature(blob, "$parentName::$name", emptyList(), ClrGenericContext(typeParameters, instantiation.orEmpty()))
            }

            private fun methodSpecText(t: Tables, row: Int, context: ClrGenericContext): String? {
                val instantiation = signatures.genericInstantiation(heaps.blob(t.cell(T.METHOD_SPEC, row, 1)), context)
                val (table, methodRow) = decodeCoded(ClrCodedIndex.MethodDefOrRef, t.cell(T.METHOD_SPEC, row, 0)) ?: return null
                return when (table) {
                    T.METHOD_DEF -> if (methodRow <= t.rowCount(T.METHOD_DEF)) methodDefText(t, methodRow, instantiation) else null
                    else -> if (methodRow <= t.rowCount(T.MEMBER_REF)) memberRefText(t, methodRow, instantiation) else null
                }
            }
        }

        /** Full name with generic parameters spelled out instead of the arity suffix: `Ns.Box<T>`. */
        private fun typeDefDisplayName(row: Int): String {
            val fullName = typeDefFullName(row)
            val generics = typeGenerics[row] ?: return fullName
            return fullName.replace(GENERIC_ARITY, "") + generics.joinToString(", ", "<", ">")
        }

        // endregion

        private fun warn(code: String, message: String, range: NativeByteRange?) {
            diagnostics.add(NativeParserDiagnostic(code = code, message = message, severity = NativeParserSeverity.Warning, range = range))
        }
    }

    private class TableLayout(val offset: Int, val rowCount: Int, val rowSize: Int, val columnSizes: IntArray) {
        val columnOffsets: IntArray = IntArray(columnSizes.size).also { offsets ->
            for (index in 1 until columnSizes.size) offsets[index] = offsets[index - 1] + columnSizes[index - 1]
        }
    }

    private class Tables(
        private val bytes: CommonByteBuffer,
        private val layouts: Array<TableLayout?>,
        val infos: List<ClrTableInfo>,
    ) {
        /** Rows that can actually be read; tables whose layout is unknown expose none. */
        fun rowCount(table: Int): Int = layouts[table]?.rowCount ?: 0

        /** Reads column [column] of 1-based [row]; out-of-range rows read as 0 (a null index). */
        fun cell(table: Int, row: Int, column: Int): Int {
            val layout = layouts[table] ?: return 0
            if (row !in 1..layout.rowCount) return 0
            val offset = layout.offset + (row - 1) * layout.rowSize + layout.columnOffsets[column]
            return when (layout.columnSizes[column]) {
                1 -> bytes.byteOrNull(offset) ?: 0
                2 -> bytes.u16LeOrNull(offset) ?: 0
                else -> bytes.u32LeOrNull(offset)?.toInt() ?: 0
            }
        }

        fun rowRange(table: Int, row: Int): NativeByteRange {
            val layout = layouts[table] ?: return NativeByteRange(0, 0)
            return NativeByteRange(layout.offset + (row - 1) * layout.rowSize, layout.rowSize)
        }
    }

    private class ColumnSizing(private val rowCounts: IntArray, private val heapSizes: Int) {
        fun size(column: ClrColumn): Int = when (column) {
            is ClrColumn.Fixed -> column.size
            ClrColumn.StringIndex -> if (heapSizes and 0x01 != 0) 4 else 2
            ClrColumn.GuidIndex -> if (heapSizes and 0x02 != 0) 4 else 2
            ClrColumn.BlobIndex -> if (heapSizes and 0x04 != 0) 4 else 2
            is ClrColumn.TableIndex -> if (rowCounts[column.table] < 0x10000) 2 else 4
            is ClrColumn.Coded -> {
                val maxRows = column.kind.tables.filter { it >= 0 }.maxOf { rowCounts[it] }
                if (maxRows < (1 shl (16 - column.kind.tagBits))) 2 else 4
            }
        }
    }

    /** `#Strings`, `#Blob`, `#GUID` and `#US` heaps (II.24.2.3 - II.24.2.5). */
    private class Heaps(
        private val bytes: CommonByteBuffer,
        private val strings: NativeByteRange?,
        private val blobs: NativeByteRange?,
        private val guids: NativeByteRange?,
        private val userStrings: NativeByteRange?,
    ) {
        private val stringCache = HashMap<Int, String>()

        fun string(index: Int): String {
            val heap = strings ?: return ""
            if (index <= 0 || index >= heap.size) return ""
            return stringCache.getOrPut(index) { bytes.nullTerminatedString(heap.offset + index, heap.size - index) }
        }

        fun guid(index: Int): String? {
            val heap = guids ?: return null
            if (index <= 0 || index.toLong() * 16 > heap.size) return null
            return bytes.guidLeString(heap.offset + (index - 1) * 16)
        }

        /** Data range of blob [index], without its compressed length prefix. */
        fun blob(index: Int): NativeByteRange? {
            val heap = blobs ?: return null
            if (index < 0 || index >= heap.size) return null
            val end = heap.offset + heap.size
            val (length, prefix) = readCompressed(bytes, heap.offset + index, end) ?: return null
            val start = heap.offset + index + prefix
            return if (length <= end - start) NativeByteRange(start, length) else null
        }

        fun blobBytes(index: Int): ByteArray {
            val range = blob(index) ?: return ByteArray(0)
            return ByteArray(range.size) { bytes[range.offset + it] }
        }

        /** The `#US` entry at heap [offset] (the low 24 bits of an `ldstr` token), or null when out of range. */
        fun userString(offset: Int): String? {
            val heap = userStrings ?: return null
            if (offset <= 0 || offset >= heap.size) return null
            val end = heap.offset + heap.size
            val (length, prefix) = readCompressed(bytes, heap.offset + offset, end) ?: return null
            val start = heap.offset + offset + prefix
            if (length > end - start) return null
            return decodeUtf16(start, length)
        }

        // The final byte of a #US entry is a flag (II.24.2.4); the rest is UTF-16LE.
        private fun decodeUtf16(start: Int, length: Int): String =
            CharArray(((length - 1) / 2).coerceAtLeast(0)) { charIndex ->
                val low = bytes.byteOrNull(start + charIndex * 2) ?: 0
                val high = bytes.byteOrNull(start + charIndex * 2 + 1) ?: 0
                (low or (high shl 8)).toChar()
            }.concatToString()

        fun userStrings(): List<ClrUserString> {
            val heap = userStrings ?: return emptyList()
            val end = heap.offset + heap.size
            return buildList {
                var position = heap.offset + 1
                while (position < end && size < MAX_USER_STRINGS) {
                    val (length, prefix) = readCompressed(bytes, position, end) ?: break
                    if (length == 0) {
                        position += prefix
                        continue
                    }
                    val dataStart = position + prefix
                    if (length > end - dataStart) break
                    add(ClrUserString(position - heap.offset, decodeUtf16(dataStart, length), NativeByteRange(position, prefix + length)))
                    position = dataStart + length
                }
            }
        }
    }
}

/** Reads an ECMA-335 II.23.2 compressed unsigned integer; returns the value and its encoded size. */
/**
 * II.23.2 compressed signed integer: the unsigned encoding of the value rotated left by one bit,
 * so the sign sits in bit 0 and is extended from 6, 13 or 28 bits by encoded size.
 */
private fun readCompressedSigned(bytes: CommonByteBuffer, offset: Int, end: Int): Pair<Int, Int>? {
    val (raw, size) = readCompressed(bytes, offset, end) ?: return null
    var value = raw ushr 1
    if (raw and 1 != 0) {
        value = value or when (size) {
            1 -> 0xFFFFFFC0.toInt()
            2 -> 0xFFFFE000.toInt()
            else -> 0xF0000000.toInt()
        }
    }
    return value to size
}

private fun readCompressed(bytes: CommonByteBuffer, offset: Int, end: Int): Pair<Int, Int>? {
    if (offset >= end) return null
    val first = bytes.byteOrNull(offset) ?: return null
    return when {
        first and 0x80 == 0 -> first to 1
        first and 0xC0 == 0x80 -> {
            if (offset + 2 > end) return null
            (((first and 0x3F) shl 8) or (bytes.byteOrNull(offset + 1) ?: return null)) to 2
        }
        first and 0xE0 == 0xC0 -> {
            if (offset + 4 > end) return null
            val value = ((first and 0x1F) shl 24) or
                ((bytes.byteOrNull(offset + 1) ?: return null) shl 16) or
                ((bytes.byteOrNull(offset + 2) ?: return null) shl 8) or
                (bytes.byteOrNull(offset + 3) ?: return null)
            value to 4
        }
        else -> null
    }
}

private fun align4(value: Int): Int = (value + 3) and 3.inv()

/** Display name without namespace and without the generic arity suffix: `System.Collections.Generic.List`1` -> `List`. */
private fun shortTypeName(fullName: String): String {
    val simple = if ('+' in fullName) fullName.substringAfterLast('+') else fullName.substringAfterLast('.')
    return simple.substringBefore('`')
}

private const val CLI_HEADER_SIZE = 72
private const val PDB_STREAM_FIXED_SIZE = 32
private const val MAX_SEQUENCE_POINTS = 1_000_000
private const val MAX_PDB_DOCUMENTS = 1_000_000
private const val MAX_DOCUMENT_NAME_PARTS = 4096
private const val MAX_DOCUMENT_NAME_LENGTH = 32768
private const val METADATA_SIGNATURE = 0x424A5342u
private const val METADATA_ROOT_MIN_SIZE = 20
private const val MAX_VERSION_LENGTH = 255
private const val MAX_STREAMS = 16
private const val MAX_STREAM_NAME_LENGTH = 32
private const val TABLES_HEADER_SIZE = 24
private const val EXTRA_DATA_FLAG = 0x40
private const val MAX_NESTING = 32
private const val MAX_PARAMETERS = 1024
private const val MAX_USER_STRINGS = 200_000
private const val TYPE_INTERFACE = 0x20u
private const val TINY_FORMAT = 0x2
private const val FAT_FORMAT = 0x3
private const val FAT_HEADER_SIZE = 12
private const val MORE_SECTIONS = 0x8
private const val INIT_LOCALS = 0x10
private const val CODE_TYPE_MASK = 0x3
private const val CODE_TYPE_IL = 0x0
private const val SECTION_EH_TABLE = 0x01
private const val SECTION_FAT_FORMAT = 0x40
private const val SECTION_MORE_SECTIONS = 0x80
private const val MAX_EXCEPTION_SECTIONS = 16
private const val USER_STRING_TOKEN_TABLE = 0x70
private val GENERIC_ARITY = Regex("`[0-9]+")
private const val READY_TO_RUN_SIGNATURE = 0x00525452u
private const val TARGET_FRAMEWORK_ATTRIBUTE = "System.Runtime.Versioning.TargetFrameworkAttribute"
