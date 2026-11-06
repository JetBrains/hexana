package org.jetbrains.hexana.pdb

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.LargeBytes
import org.jetbrains.hexana.io.asLargeBytes
import org.jetbrains.hexana.parsing.common.NativeByteRange
import org.jetbrains.hexana.parsing.common.NativeParserDiagnostic
import org.jetbrains.hexana.parsing.common.NativeParserSeverity

/**
 * Decodes Windows PDB files: the MSF 7.00 container, the PDB info stream, the DBI stream and the
 * TPI/IPI headers. Layouts follow LLVM's PDB documentation (`llvm/docs/PDB`) and
 * Microsoft's `microsoft-pdb` reference sources.
 *
 * Streams are read on demand through their block lists, so a large PDB costs only the stream
 * directory plus the streams a caller touches.
 */
object PdbParser {
    /** True for an MSF 7.00 file, i.e. any PDB written by a toolchain of the last two decades. */
    fun isPdb(bytes: CommonByteBuffer): Boolean = isPdb(bytes.asLargeBytes())

    fun isPdb(bytes: LargeBytes): Boolean = bytes.startsWith(MSF7_MAGIC)

    /** True for the pre-2002 MSF 2.00 format, which [parse] recognizes but does not decode. */
    fun isLegacyPdb(bytes: CommonByteBuffer): Boolean = bytes.asLargeBytes().startsWith(MSF2_MAGIC)

    /** Returns null when [bytes] is not MSF 7.00 or its superblock is unusable. */
    fun parse(bytes: CommonByteBuffer): PdbFile? = parse(bytes.asLargeBytes())

    /** [parse] for a PDB of any size: its blocks are read wherever they are in the file. */
    fun parse(bytes: LargeBytes): PdbFile? {
        if (!isPdb(bytes)) return null
        return Decoder(bytes).decode()
    }

    private class Decoder(private val bytes: LargeBytes) {
        private val diagnostics = mutableListOf<NativeParserDiagnostic>()

        private fun warn(code: String, message: String, range: NativeByteRange? = null) {
            diagnostics.add(NativeParserDiagnostic(code, message, range, NativeParserSeverity.Warning))
        }

        fun decode(): PdbFile? {
            val superBlock = parseSuperBlock() ?: return null
            val (blockMapRange, streams) = parseDirectory(superBlock)
            // Decoders below rely on in-range reads succeeding, so they only ever see fully readable streams.
            fun stream(index: Int) = streams.getOrNull(index)?.takeIf { !it.isNil && it.size > 0 && it.isFullyReadable() }
            val info = stream(PDB_STREAM)?.let { parseInfo(it) }
            if (info == null) warn("pdb.missing_info_stream", "PDB info stream (stream 1) is missing or malformed.")
            val dbi = stream(DBI_STREAM)?.let { parseDbi(it) }
            if (dbi == null && streams.size > DBI_STREAM) warn("pdb.missing_dbi_stream", "DBI stream (stream 3) is missing or malformed.")
            return PdbFile(
                superBlock = superBlock,
                blockMapRange = blockMapRange,
                streams = streams,
                info = info,
                dbi = dbi,
                tpi = stream(TPI_STREAM)?.let { parseTypeStreamHeader(it, "TPI") },
                ipi = stream(IPI_STREAM)?.let { parseTypeStreamHeader(it, "IPI") },
                diagnostics = diagnostics,
            )
        }

        // region MSF

        private fun parseSuperBlock(): MsfSuperBlock? {
            val blockSize = bytes.u32(MSF7_MAGIC.size)?.toInt() ?: return null
            if (blockSize !in VALID_BLOCK_SIZES) return null
            val blockCount = bytes.u32(MSF7_MAGIC.size + 8) ?: return null
            val superBlock = MsfSuperBlock(
                blockSize = blockSize,
                freeBlockMapBlock = bytes.u32(MSF7_MAGIC.size + 4) ?: return null,
                blockCount = blockCount,
                directoryByteCount = bytes.u32(MSF7_MAGIC.size + 12) ?: return null,
                blockMapAddress = bytes.u32(MSF7_MAGIC.size + 20) ?: return null,
                range = NativeByteRange(0, SUPER_BLOCK_SIZE),
            )
            if (blockCount.toLong() * blockSize > bytes.size) {
                warn(
                    "pdb.truncated",
                    "The MSF superblock declares ${blockCount} blocks of $blockSize bytes, but the file has only ${bytes.size} bytes.",
                    superBlock.range,
                )
            }
            return superBlock
        }

        private fun parseDirectory(superBlock: MsfSuperBlock): Pair<NativeByteRange?, List<MsfStream>> {
            val blockSize = superBlock.blockSize
            val directorySize = superBlock.directoryByteCount.toLong()
            val directoryBlockCount = ((directorySize + blockSize - 1) / blockSize).toInt()
            val blockMapOffset = superBlock.blockMapAddress.toLong() * blockSize
            if (directorySize < 4 || directoryBlockCount > blockSize / 4 ||
                blockMapOffset + directoryBlockCount * 4L > bytes.size
            ) {
                warn("pdb.invalid_directory", "The MSF stream directory is missing or malformed.", superBlock.range)
                return null to emptyList()
            }
            val blockMapRange = NativeByteRange(blockMapOffset, directoryBlockCount * 4L)
            val directoryBlocks = IntArray(directoryBlockCount) { index -> bytes.u32(blockMapRange.start + index * 4)!!.toInt() }
            val directory = MsfStream(-1, directorySize.toInt(), false, directoryBlocks, blockSize, bytes)
            if (!directory.isFullyReadable()) {
                warn("pdb.invalid_directory", "The MSF stream directory refers to blocks past the end of the file.", blockMapRange)
                return blockMapRange to emptyList()
            }

            val declaredStreams = directory.u32(0) ?: return blockMapRange to emptyList()
            val streamCount = declaredStreams.toLong().coerceAtMost((directorySize - 4) / 4).toInt()
            if (streamCount < declaredStreams.toLong()) {
                warn("pdb.invalid_directory", "The MSF stream directory declares $declaredStreams streams but has room for only $streamCount.", blockMapRange)
            }
            var position = 4 + streamCount * 4
            val streams = ArrayList<MsfStream>(streamCount)
            for (index in 0 until streamCount) {
                val rawSize = directory.u32(4 + index * 4)!!
                val isNil = rawSize == NIL_STREAM_SIZE
                val size = if (isNil) 0L else rawSize.toLong()
                val streamBlockCount = ((size + blockSize - 1) / blockSize)
                if (size > Int.MAX_VALUE || position + streamBlockCount * 4 > directorySize) {
                    warn("pdb.invalid_directory", "MSF stream $index declares $size bytes, more than the stream directory has block numbers for; it and later streams are dropped.", blockMapRange)
                    break
                }
                val blocks = IntArray(streamBlockCount.toInt()) { blockIndex -> directory.u32(position + blockIndex * 4)!!.toInt() }
                position += blocks.size * 4
                val stream = MsfStream(index, size.toInt(), isNil, blocks, blockSize, bytes)
                if (!stream.isFullyReadable()) warn("pdb.invalid_stream_block", "MSF stream $index refers to blocks past the end of the file; it is not decoded.")
                streams.add(stream)
            }
            return blockMapRange to streams
        }

        // endregion

        // region PDB info stream

        private fun parseInfo(stream: MsfStream): PdbInfoStream? {
            val version = stream.u32(0) ?: return null
            val signature = stream.u32(4) ?: return null
            val age = stream.u32(8) ?: return null
            // VC2 PDBs (version 19941610 and older) have no GUID; every modern linker writes VC70.
            val hasGuid = version >= PDB_VERSION_VC70
            val guid = if (hasGuid) stream.guid(12) else null
            var position = if (hasGuid) 28 else 12
            val namedStreams = parseNamedStreamMap(stream, position)
            position = namedStreams?.second ?: stream.size
            if (namedStreams == null) warn("pdb.invalid_named_streams", "The PDB named stream map is malformed.", stream.fileRange(position, 4))
            val features = buildList {
                while (position + 4 <= stream.size) {
                    val code = stream.u32(position) ?: break
                    PdbFeature.of(code)?.let(::add)
                    position += 4
                }
            }
            return PdbInfoStream(version, signature, age, guid, namedStreams?.first.orEmpty(), features)
        }

        /** The serialized `NMTNI` hash table: a string buffer, then present/deleted bit vectors and key/value pairs. */
        private fun parseNamedStreamMap(stream: MsfStream, start: Int): Pair<Map<String, Int>, Int>? {
            val bufferSize = stream.u32(start)?.toLong() ?: return null
            if (bufferSize > stream.size - start - 4) return null
            val bufferStart = start + 4
            var position = bufferStart + bufferSize.toInt()
            val size = stream.u32(position)?.toLong() ?: return null
            val capacity = stream.u32(position + 4)?.toLong() ?: return null
            position += 8
            if (size > capacity || capacity > MAX_HASH_CAPACITY) return null
            val present = readBitVector(stream, position) ?: return null
            position = present.second
            val deleted = readBitVector(stream, position) ?: return null
            position = deleted.second
            val result = LinkedHashMap<String, Int>()
            for (bucket in 0 until capacity.toInt()) {
                if (!present.first.isSet(bucket)) continue
                val key = stream.u32(position)?.toLong() ?: return null
                val value = stream.u32(position + 4) ?: return null
                position += 8
                if (key >= bufferSize) continue
                val name = stream.cString(bufferStart + key.toInt(), (bufferSize - key).toInt())?.first ?: continue
                result[name] = value.toInt()
            }
            return result.entries.sortedBy { it.value }.associate { it.key to it.value } to position
        }

        private fun readBitVector(stream: MsfStream, start: Int): Pair<IntArray, Int>? {
            val wordCount = stream.u32(start)?.toLong() ?: return null
            if (wordCount > (stream.size - start - 4) / 4) return null
            val words = IntArray(wordCount.toInt()) { stream.u32(start + 4 + it * 4)!!.toInt() }
            return words to start + 4 + words.size * 4
        }

        private fun IntArray.isSet(bit: Int): Boolean = bit / 32 < size && (this[bit / 32] ushr (bit % 32)) and 1 != 0

        // endregion

        // region DBI stream

        private fun parseDbi(stream: MsfStream): PdbDbiStream? {
            if (stream.size < DBI_HEADER_SIZE) return null
            val versionSignature = stream.i32(0)!!
            if (versionSignature != -1) {
                warn("pdb.unsupported_dbi", "The DBI stream uses the pre-VC4.1 layout, which is not supported.", stream.fileRange(0, 4))
                return null
            }
            val build = stream.u16(14)!!
            val substreamSizes = listOf(
                "Module info" to stream.i32(24)!!,
                "Section contributions" to stream.i32(28)!!,
                "Section map" to stream.i32(32)!!,
                "Source files" to stream.i32(36)!!,
                "Type server map" to stream.i32(40)!!,
                "EC info" to stream.i32(52)!!,
                "Optional debug header" to stream.i32(48)!!,
            )
            var position = DBI_HEADER_SIZE
            val substreams = buildList {
                for ((name, size) in substreamSizes) {
                    if (size < 0 || size > stream.size - position) {
                        warn("pdb.invalid_dbi_substream", "DBI substream '$name' extends past the DBI stream; it and later substreams are ignored.", stream.fileRange(0, DBI_HEADER_SIZE))
                        break
                    }
                    add(PdbDbiSubstream(name, position, size, stream.fileRange(position, size)))
                    position += size
                }
            }
            fun substream(name: String) = substreams.firstOrNull { it.name == name }

            val modules = substream("Module info")?.let { parseModules(stream, it) }.orEmpty()
            val sourceFiles = substream("Source files")?.let { parseSourceFiles(stream, it) }.orEmpty()
            return PdbDbiStream(
                versionSignature = versionSignature,
                version = stream.u32(4)!!,
                age = stream.u32(8)!!,
                globalSymbolStream = streamIndex(stream.u16(12)!!),
                publicSymbolStream = streamIndex(stream.u16(16)!!),
                symbolRecordStream = streamIndex(stream.u16(20)!!),
                buildMajor = (build ushr 8) and 0x7F,
                buildMinor = build and 0xFF,
                pdbDllVersion = stream.u16(18)!!,
                pdbDllRebuild = stream.u16(22)!!,
                flags = stream.u16(56)!!,
                machine = stream.u16(58)!!,
                modules = modules.mapIndexed { index, module -> module.copy(sourceFiles = sourceFiles.getOrNull(index).orEmpty()) },
                sectionContributions = substream("Section contributions")?.let { parseSectionContributions(stream, it) }.orEmpty(),
                optionalDebugStreams = substream("Optional debug header")?.let { parseOptionalDebugHeader(stream, it) }.orEmpty(),
                substreams = substreams,
            )
        }

        private fun parseModules(stream: MsfStream, substream: PdbDbiSubstream): List<PdbModule> = buildList {
            val end = substream.streamOffset + substream.size
            var position = substream.streamOffset
            while (position + MODULE_INFO_FIXED_SIZE <= end && size < MAX_MODULES) {
                val start = position
                val contribution = readSectionContribution(stream, position + 4) ?: break
                val moduleName = stream.cString(position + MODULE_INFO_FIXED_SIZE, end - position - MODULE_INFO_FIXED_SIZE)
                val objectName = moduleName?.let { stream.cString(position + MODULE_INFO_FIXED_SIZE + it.second, end - position - MODULE_INFO_FIXED_SIZE - it.second) }
                if (moduleName == null || objectName == null) {
                    warn("pdb.invalid_module_info", "DBI module record #$size is truncated.", stream.fileRange(position, end - position))
                    break
                }
                val recordSize = align4(MODULE_INFO_FIXED_SIZE + moduleName.second + objectName.second)
                add(
                    PdbModule(
                        index = size,
                        moduleName = moduleName.first,
                        objectFileName = objectName.first,
                        flags = stream.u16(position + 32)!!,
                        symbolStream = streamIndex(stream.u16(position + 34)!!),
                        symbolByteSize = stream.u32(position + 36)!!,
                        c11ByteSize = stream.u32(position + 40)!!,
                        c13ByteSize = stream.u32(position + 44)!!,
                        sourceFileCount = stream.u16(position + 48)!!,
                        sourceFiles = emptyList(),
                        contribution = contribution,
                        streamOffset = start,
                        recordSize = recordSize,
                        fileRange = stream.fileRange(start, recordSize),
                    ),
                )
                position += recordSize
            }
        }

        private fun readSectionContribution(stream: MsfStream, position: Int): PdbSectionContribution? =
            PdbSectionContribution(
                section = stream.u16(position) ?: return null,
                offset = stream.i32(position + 4) ?: return null,
                size = stream.i32(position + 8) ?: return null,
                characteristics = stream.u32(position + 12) ?: return null,
                moduleIndex = stream.u16(position + 16) ?: return null,
                dataCrc = stream.u32(position + 20) ?: return null,
                relocCrc = stream.u32(position + 24) ?: return null,
                fileRange = stream.fileRange(position, SECTION_CONTRIBUTION_SIZE),
            )

        private fun parseSectionContributions(stream: MsfStream, substream: PdbDbiSubstream): List<PdbSectionContribution> {
            if (substream.size < 4) return emptyList()
            val version = stream.u32(substream.streamOffset)!!
            val entrySize = when (version) {
                SECTION_CONTRIBUTION_V60 -> SECTION_CONTRIBUTION_SIZE
                SECTION_CONTRIBUTION_V2 -> SECTION_CONTRIBUTION_SIZE + 4
                else -> {
                    warn("pdb.unknown_section_contribution_version", "DBI section contributions use unknown version 0x${version.toString(16)}.", substream.fileRange)
                    return emptyList()
                }
            }
            val count = ((substream.size - 4) / entrySize).coerceAtMost(MAX_SECTION_CONTRIBUTIONS)
            return (0 until count).mapNotNull { readSectionContribution(stream, substream.streamOffset + 4 + it * entrySize) }
        }

        /**
         * The file-info substream: per-module file counts, then name offsets for every module in
         * order. Its 16-bit total file count overflows for large images, so the offsets are counted
         * from the per-module counts, as the reference implementation does.
         */
        private fun parseSourceFiles(stream: MsfStream, substream: PdbDbiSubstream): List<List<String>> {
            val start = substream.streamOffset
            val end = start + substream.size
            val moduleCount = stream.u16(start) ?: return emptyList()
            val countsStart = start + 4 + moduleCount * 2
            val offsetsStart = countsStart + moduleCount * 2
            if (offsetsStart > end) {
                warn("pdb.invalid_source_files", "The DBI source file substream is truncated.", substream.fileRange)
                return emptyList()
            }
            val counts = IntArray(moduleCount) { stream.u16(countsStart + it * 2)!! }
            // Up to 65535 modules of up to 65535 files each: the offset table size overflows an Int.
            val namesStartLong = offsetsStart + counts.sumOf { it.toLong() } * 4
            if (namesStartLong > end) {
                warn("pdb.invalid_source_files", "The DBI source file substream is truncated.", substream.fileRange)
                return emptyList()
            }
            val namesStart = namesStartLong.toInt()
            val names = HashMap<UInt, String>()
            var offsetIndex = 0
            return counts.map { count ->
                List(count) {
                    val nameOffset = stream.u32(offsetsStart + offsetIndex++ * 4)!!
                    names.getOrPut(nameOffset) {
                        val at = namesStart.toLong() + nameOffset.toLong()
                        if (at >= end) "<invalid name offset 0x${nameOffset.toString(16)}>"
                        else stream.cString(at.toInt(), end - at.toInt())?.first ?: "<unterminated name>"
                    }
                }
            }
        }

        private fun parseOptionalDebugHeader(stream: MsfStream, substream: PdbDbiSubstream): Map<PdbOptionalDebugStream, Int> = buildMap {
            for ((index, kind) in PdbOptionalDebugStream.entries.withIndex()) {
                if (index * 2 + 2 > substream.size) break
                streamIndex(stream.u16(substream.streamOffset + index * 2)!!)?.let { put(kind, it) }
            }
        }

        // endregion

        private fun parseTypeStreamHeader(stream: MsfStream, name: String): PdbTypeStreamHeader? {
            if (stream.size < TYPE_STREAM_HEADER_SIZE) {
                warn("pdb.invalid_type_stream", "The $name stream header is truncated.", stream.fileRange(0, stream.size))
                return null
            }
            return PdbTypeStreamHeader(
                version = stream.u32(0)!!,
                headerSize = stream.u32(4)!!,
                typeIndexBegin = stream.u32(8)!!,
                typeIndexEnd = stream.u32(12)!!,
                typeRecordBytes = stream.u32(16)!!,
                hashStream = streamIndex(stream.u16(20)!!),
                hashAuxStream = streamIndex(stream.u16(22)!!),
            )
        }
    }
}

internal fun computeStreamRoles(pdb: PdbFile): Map<Int, String> = buildMap {
    put(OLD_DIRECTORY_STREAM, "Old MSF directory")
    put(PDB_STREAM, "PDB info")
    put(TPI_STREAM, "TPI (types)")
    put(DBI_STREAM, "DBI (debug info)")
    put(IPI_STREAM, "IPI (type ids)")
    pdb.tpi?.hashStream?.let { put(it, "TPI hash") }
    pdb.tpi?.hashAuxStream?.let { put(it, "TPI aux hash") }
    pdb.ipi?.hashStream?.let { put(it, "IPI hash") }
    pdb.ipi?.hashAuxStream?.let { put(it, "IPI aux hash") }
    pdb.dbi?.let { dbi ->
        dbi.globalSymbolStream?.let { put(it, "Global symbol hash") }
        dbi.publicSymbolStream?.let { put(it, "Public symbol hash") }
        dbi.symbolRecordStream?.let { put(it, "Symbol records") }
        for ((kind, index) in dbi.optionalDebugStreams) put(index, kind.label)
        for (module in dbi.modules) module.symbolStream?.let { put(it, "Module \"${module.moduleName}\"") }
    }
    pdb.info?.namedStreams?.forEach { (name, index) -> put(index, "Named stream \"$name\"") }
}

private fun streamIndex(raw: Int): Int? = raw.takeIf { it != NIL_STREAM_INDEX }

private fun align4(value: Int): Int = (value + 3) and 3.inv()

private fun LargeBytes.startsWith(magic: ByteArray): Boolean =
    size >= magic.size && magic.indices.all { this[it.toLong()] == magic[it] }

private fun LargeBytes.u32(offset: Int): UInt? = u32(offset.toLong())

private fun LargeBytes.u32(offset: Long): UInt? {
    if (offset < 0 || offset > size - 4) return null
    return (this[offset].toUInt() and 0xFFu) or
        ((this[offset + 1].toUInt() and 0xFFu) shl 8) or
        ((this[offset + 2].toUInt() and 0xFFu) shl 16) or
        ((this[offset + 3].toUInt() and 0xFFu) shl 24)
}

private val MSF7_MAGIC = "Microsoft C/C++ MSF 7.00\r\n\u001aDS\u0000\u0000\u0000".encodeToByteArray()
private val MSF2_MAGIC = "Microsoft C/C++ program database 2.00\r\n\u001aJG\u0000\u0000".encodeToByteArray()
private val VALID_BLOCK_SIZES = setOf(512, 1024, 2048, 4096, 8192, 16384, 32768)
private const val SUPER_BLOCK_SIZE = 56
private const val NIL_STREAM_SIZE = 0xFFFFFFFFu
private const val NIL_STREAM_INDEX = 0xFFFF
private const val PDB_VERSION_VC70 = 20000404u
private const val MAX_HASH_CAPACITY = 1L shl 20
private const val DBI_HEADER_SIZE = 64
private const val MODULE_INFO_FIXED_SIZE = 64
private const val SECTION_CONTRIBUTION_SIZE = 28
private const val SECTION_CONTRIBUTION_V60 = 0xF12EBA2Du // 0xEFFE0000 + 19970605
private const val SECTION_CONTRIBUTION_V2 = 0xF13151E4u // 0xEFFE0000 + 20140516
private const val TYPE_STREAM_HEADER_SIZE = 56
private const val MAX_MODULES = 1_000_000
private const val MAX_SECTION_CONTRIBUTIONS = 4_000_000

internal const val OLD_DIRECTORY_STREAM = 0
internal const val PDB_STREAM = 1
internal const val TPI_STREAM = 2
internal const val DBI_STREAM = 3
internal const val IPI_STREAM = 4
