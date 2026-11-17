package org.jetbrains.hexana.pdb

/**
 * C13 line information of a Windows PDB: every module stream ends with CodeView debug
 * subsections, of which `DEBUG_S_LINES` maps code to source lines and `DEBUG_S_FILECHKSMS`
 * names the files (through the `/names` string table) with their checksums.
 */
data class PdbLineRow(
    /** 1-based image section number; the image's section headers give its RVA. */
    val section: Int,
    val offset: UInt,
    val file: String,
    val line: Int,
    /** 1-based start column, or 0 when the PDB records no columns (MSVC and lld usually do not). */
    val column: Int,
    val isStatement: Boolean,
    val moduleIndex: Int,
)

/** One `DEBUG_S_LINES` file block: a contiguous code range of one function in one file. */
data class PdbLineBlock(
    val section: Int,
    val offset: UInt,
    val codeSize: UInt,
    val file: String,
    val moduleIndex: Int,
    val rows: List<PdbLineRow>,
)

/** A `DEBUG_S_FILECHKSMS` entry. */
data class PdbSourceFileChecksum(val file: String, val kind: PdbChecksumKind, val checksum: String)

enum class PdbChecksumKind(val label: String) { None("none"), Md5("MD5"), Sha1("SHA-1"), Sha256("SHA-256"), Unknown("unknown") }

/** The `/names` stream: a hash table of strings, read by byte offset into its buffer. */
class PdbStringTable internal constructor(private val stream: MsfStream, private val bufferStart: Int, private val bufferSize: Int) {
    fun string(offset: UInt): String? {
        if (offset.toLong() >= bufferSize) return null
        return stream.cString(bufferStart + offset.toInt(), bufferSize - offset.toInt())?.first
    }
}

internal fun readStringTable(stream: MsfStream): PdbStringTable? {
    if (stream.u32(0) != STRING_TABLE_SIGNATURE) return null
    val size = stream.u32(8)?.toLong() ?: return null
    if (size > stream.size - 12) return null
    return PdbStringTable(stream, 12, size.toInt())
}

/** Line blocks of [module], in stream order. Malformed subsections end the module's list. */
internal fun readModuleLines(pdb: PdbFile, module: PdbModule, names: PdbStringTable?): List<PdbLineBlock> {
    val stream = module.symbolStream?.let(pdb::stream)?.takeIf { it.isFullyReadable() } ?: return emptyList()
    val start = module.symbolByteSize.toLong() + module.c11ByteSize.toLong()
    val end = start + module.c13ByteSize.toLong()
    if (module.c13ByteSize == 0u || end > stream.size) return emptyList()

    // Checksums come first in practice, but nothing requires it: collect them in a first pass.
    val checksums = HashMap<UInt, String>()
    val lineSubsections = mutableListOf<Pair<Int, Int>>()
    var position = start.toInt()
    while (position + 8 <= end) {
        val kind = stream.u32(position)!! and SUBSECTION_IGNORE.inv()
        val length = stream.u32(position + 4)!!.toLong()
        val data = position + 8
        if (length > end - data) break
        when (kind) {
            DEBUG_S_FILECHKSMS -> readChecksums(stream, data, length.toInt(), names, checksums)
            DEBUG_S_LINES -> lineSubsections.add(data to length.toInt())
        }
        position = align4(data + length.toInt())
    }
    return lineSubsections.flatMap { (data, length) -> readLines(stream, data, length, checksums, module.index) }
}

/** Every file a module's checksum subsection names, with its checksum. */
internal fun readModuleChecksums(pdb: PdbFile, module: PdbModule, names: PdbStringTable?): List<PdbSourceFileChecksum> {
    val stream = module.symbolStream?.let(pdb::stream)?.takeIf { it.isFullyReadable() } ?: return emptyList()
    val start = module.symbolByteSize.toLong() + module.c11ByteSize.toLong()
    val end = start + module.c13ByteSize.toLong()
    if (module.c13ByteSize == 0u || end > stream.size) return emptyList()
    val result = mutableListOf<PdbSourceFileChecksum>()
    var position = start.toInt()
    while (position + 8 <= end) {
        val kind = stream.u32(position)!! and SUBSECTION_IGNORE.inv()
        val length = stream.u32(position + 4)!!.toLong()
        val data = position + 8
        if (length > end - data) break
        if (kind == DEBUG_S_FILECHKSMS) {
            forEachChecksum(stream, data, length.toInt(), names) { _, file, kindByte, checksum ->
                result.add(PdbSourceFileChecksum(file, checksumKind(kindByte), checksum))
            }
        }
        position = align4(data + length.toInt())
    }
    return result
}

private fun readChecksums(stream: MsfStream, data: Int, length: Int, names: PdbStringTable?, into: MutableMap<UInt, String>) {
    forEachChecksum(stream, data, length, names) { entryOffset, file, _, _ -> into[entryOffset] = file }
}

/** Entries are `nameOffset u32, size u8, kind u8, checksum[size]`, each padded to 4 bytes. */
private inline fun forEachChecksum(
    stream: MsfStream,
    data: Int,
    length: Int,
    names: PdbStringTable?,
    action: (entryOffset: UInt, file: String, kind: Int, checksum: String) -> Unit,
) {
    var position = data
    val end = data + length
    while (position + 6 <= end) {
        val nameOffset = stream.u32(position)!!
        val size = stream.u8(position + 4)!!
        val kind = stream.u8(position + 5)!!
        if (position + 6 + size > end) break
        val checksum = stream.bytes(position + 6, size)!!.joinToString("") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
        val file = names?.string(nameOffset) ?: "<file name 0x${nameOffset.toString(16)}>"
        action((position - data).toUInt(), file, kind, checksum)
        position = align4(position + 6 + size)
    }
}

/**
 * `DEBUG_S_LINES`: a header (offset, section, flags, code size), then file blocks, each with
 * `(offset, packed line)` pairs and, when the header has the columns flag, `(start, end)` columns.
 */
private fun readLines(stream: MsfStream, data: Int, length: Int, checksums: Map<UInt, String>, moduleIndex: Int): List<PdbLineBlock> {
    if (length < LINES_HEADER_SIZE) return emptyList()
    val end = data + length
    val baseOffset = stream.u32(data)!!
    val section = stream.u16(data + 4)!!
    val hasColumns = stream.u16(data + 6)!! and LINES_HAVE_COLUMNS != 0
    val codeSize = stream.u32(data + 8)!!
    val blocks = mutableListOf<PdbLineBlock>()
    var position = data + LINES_HEADER_SIZE
    while (position + LINE_BLOCK_HEADER_SIZE <= end) {
        val checksumOffset = stream.u32(position)!!
        val lineCount = stream.u32(position + 4)!!.toLong()
        val blockSize = stream.u32(position + 8)!!.toLong()
        val columnsBytes = if (hasColumns) lineCount * 4 else 0
        if (blockSize < LINE_BLOCK_HEADER_SIZE + lineCount * 8 + columnsBytes || blockSize > end - position) break
        val file = checksums[checksumOffset] ?: "<file checksum 0x${checksumOffset.toString(16)}>"
        val linesStart = position + LINE_BLOCK_HEADER_SIZE
        val columnsStart = linesStart + lineCount.toInt() * 8
        val rows = buildList {
            for (index in 0 until lineCount.toInt()) {
                val packed = stream.u32(linesStart + index * 8 + 4)!!
                val line = (packed and 0xFFFFFFu).toInt()
                // 0xFEEFEE and 0xF00F00 mark compiler-generated code with no source line.
                if (line == HIDDEN_LINE || line == ALT_HIDDEN_LINE || line == 0) continue
                add(
                    PdbLineRow(
                        section = section,
                        offset = baseOffset + stream.u32(linesStart + index * 8)!!,
                        file = file,
                        line = line,
                        column = if (hasColumns) stream.u16(columnsStart + index * 4)!! else 0,
                        isStatement = packed and 0x80000000u != 0u,
                        moduleIndex = moduleIndex,
                    ),
                )
            }
        }
        blocks.add(PdbLineBlock(section, baseOffset, codeSize, file, moduleIndex, rows))
        position += blockSize.toInt()
    }
    return blocks
}

private fun checksumKind(kind: Int): PdbChecksumKind = when (kind) {
    0 -> PdbChecksumKind.None
    1 -> PdbChecksumKind.Md5
    2 -> PdbChecksumKind.Sha1
    3 -> PdbChecksumKind.Sha256
    else -> PdbChecksumKind.Unknown
}

private fun align4(value: Int): Int = (value + 3) and 3.inv()

private const val STRING_TABLE_SIGNATURE = 0xEFFEEFFEu
private const val SUBSECTION_IGNORE = 0x80000000u
private const val DEBUG_S_LINES = 0xF2u
private const val DEBUG_S_FILECHKSMS = 0xF4u
private const val LINES_HEADER_SIZE = 12
private const val LINE_BLOCK_HEADER_SIZE = 12
private const val LINES_HAVE_COLUMNS = 0x0001
private const val HIDDEN_LINE = 0xFEEFEE
private const val ALT_HIDDEN_LINE = 0xF00F00
