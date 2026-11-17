package org.jetbrains.hexana.providers

import org.jetbrains.hexana.io.ByteProvenance
import org.jetbrains.hexana.io.ByteProvenanceKind
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.parquet.ColumnValues
import org.jetbrains.hexana.parquet.ColumnMetaData
import org.jetbrains.hexana.io.asLargeBytes
import org.jetbrains.hexana.io.LargeBytes
import org.jetbrains.hexana.io.DecodedCell
import org.jetbrains.hexana.parquet.FileMetaData
import org.jetbrains.hexana.parquet.SchemaElement
import org.jetbrains.hexana.parquet.SchemaNode
import org.jetbrains.hexana.parquet.buildSchemaTree
import org.jetbrains.hexana.parquet.decodeColumnChunk
import org.jetbrains.hexana.parquet.readParquetFileMetaData

private val PAR1 = byteArrayOf(0x50, 0x41, 0x52, 0x31)

fun isParquetMagic(buf: CommonByteBuffer): Boolean {
    if (buf.size < 8) return false
    return (0 until 4).all { buf[it] == PAR1[it] } &&
        (0 until 4).all { buf[buf.size - 4 + it] == PAR1[it] }
}

class ParquetBinaryProvider(
    private val sharedBytes: CommonByteBuffer,
    val sourcePath: String? = null,
    /**
     * The whole file. Larger than [sharedBytes] for a file past 2 GiB, whose footer — and so its
     * schema and row groups — sits at the end; the metadata and column chunks are read from this.
     */
    val source: LargeBytes = sharedBytes.asLargeBytes(),
) : BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()

    override val fileSize: Long get() = source.size

    val metadata: FileMetaData? by lazy {
        try { readParquetFileMetaData(source) } catch (_: Throwable) { null }
    }

    /** Flat leaf elements only (type != null). */
    val leafElements: List<SchemaElement> by lazy {
        metadata?.schema?.filter { it.type != null }.orEmpty()
    }

    /**
     * One range per row group, plus the footer.
     *
     * A row group's extent is derived from its column chunks: each starts at its
     * dictionary page when it has one and at its first data page otherwise, and spans
     * `totalCompressedSize` bytes. `ColumnChunk.fileOffset` is deliberately not used —
     * writers are inconsistent about it and some leave it zero. Everything after the
     * last row group is the footer: the Thrift metadata, its length, and the trailing
     * `PAR1` magic. A file with no row groups — an empty table — is all footer, and
     * still gets that one region.
     */
    override val structureRanges: List<StructureRange> by lazy {
        val rowGroups = metadata?.rowGroups ?: return@lazy emptyList()
        val total = source.size
        buildList {
            // Regions begin after the leading magic, which belongs to no row group.
            var lastEnd = PAR1.size.toLong()
            rowGroups.forEachIndexed { index, rowGroup ->
                val chunks = rowGroup.columns.mapNotNull { it.metaData }
                if (chunks.isEmpty()) return@forEachIndexed
                val start = chunks.minOf { it.dictionaryPageOffset ?: it.dataPageOffset }
                val end = chunks.maxOf {
                    (it.dictionaryPageOffset ?: it.dataPageOffset) + it.totalCompressedSize
                }
                structureRangeOrNull(
                    offset = start,
                    size = end - start,
                    label = "Row group $index",
                    fileSize = total,
                    tabName = "Row Groups",
                )?.let {
                    add(it)
                    // Advance only past regions that passed the bounds check, so a page
                    // offset a hostile footer inflated cannot push the footer's start
                    // out of the file with it.
                    lastEnd = maxOf(lastEnd, it.offset + it.size)
                }
            }
            structureRangeOrNull(
                offset = lastEnd,
                size = total - lastEnd,
                label = "Footer",
                fileSize = total,
                tabName = "Metadata",
            )?.let { add(it) }
        }
    }

    /**
     * Decoded data table for the first row group, capped at [DEFAULT_ROW_LIMIT] rows.
     * Null when metadata is unavailable or decoding throws unexpectedly.
     */
    val decodedTable: DecodedTable? by lazy {
        try { buildDecodedTable() } catch (_: Throwable) { null }
    }

    /**
     * Decodes one column chunk. The decoder is Int-addressed, so a chunk past the buffer's reach is
     * handed a window over just itself, with its page offsets rebased onto the window.
     */
    private fun decodeChunk(chunk: ColumnMetaData, leaf: SchemaNode, rowLimit: Int): ColumnValues {
        val start = chunk.dictionaryPageOffset ?: chunk.dataPageOffset
        val length = chunk.totalCompressedSize
        // Validated before comparing with subtraction, so hostile metadata cannot wrap the extent.
        if (!extentFits(start, length, source.size)) {
            return ColumnValues(chunk.pathInSchema, chunk.type, emptyList(), false, "column chunk lies outside the file or is too large to decode")
        }
        if (extentFits(start, length, bytes.size.toLong())) return decodeColumnChunk(bytes, chunk, leaf, rowLimit)
        if (length > Int.MAX_VALUE) {
            return ColumnValues(chunk.pathInSchema, chunk.type, emptyList(), false, "column chunk lies outside the file or is too large to decode")
        }
        val rebased = chunk.copy(
            dataPageOffset = chunk.dataPageOffset - start,
            dictionaryPageOffset = chunk.dictionaryPageOffset?.minus(start),
            indexPageOffset = chunk.indexPageOffset?.minus(start)?.takeIf { it >= 0 },
        )
        val decoded = decodeColumnChunk(source.window(start, length.toInt()), rebased, leaf, rowLimit)
        // The decoder located values within the window; move them back to where they are in the file.
        val cells = decoded.cells.map { cell ->
            val at = cell.provenance
            if (at.kind == ByteProvenanceKind.NONE) cell
            else DecodedCell(cell.value, cell.isNull, ByteProvenance(at.offset + start, at.size, at.kind))
        }
        return ColumnValues(decoded.leafPath, decoded.type, cells, decoded.fullyDecoded, decoded.undecodedReason)
    }

    private fun buildDecodedTable(): DecodedTable? {
        val meta = metadata ?: return null
        if (meta.rowGroups.isEmpty()) return null
        val rowGroup = meta.rowGroups[0]
        val schema = buildSchemaTree(meta.schema)
        // Collect leaves in schema tree order (pre-order DFS, only leaf nodes).
        val leaves = buildList {
            fun collect(n: SchemaNode) {
                if (n.isLeaf) add(n) else n.children.forEach { collect(it) }
            }
            schema.children.forEach { collect(it) }
        }

        val rowLimit = DEFAULT_ROW_LIMIT
        val cappedRows = minOf(rowGroup.numRows, rowLimit.toLong()).toInt()
        val truncated = rowGroup.numRows > rowLimit || meta.rowGroups.size > 1

        val columnNames = mutableListOf<String>()
        val columnTypes = mutableListOf<String>()
        val columnCells = mutableListOf<List<DecodedCell>>()
        val columnDecoded = mutableListOf<Boolean>()
        val undecodedReasons = mutableListOf<String?>()

        val noneProvenance = ByteProvenance(0L, 0, ByteProvenanceKind.NONE)
        for ((leafIdx, leafNode) in leaves.withIndex()) {
            val chunkMeta = rowGroup.columns.getOrNull(leafIdx)?.metaData ?: continue
            val result = decodeChunk(chunkMeta, leafNode, rowLimit)
            columnNames += result.leafPath.lastOrNull() ?: leafNode.element.name
            columnTypes += result.type.name
            columnDecoded += result.fullyDecoded
            undecodedReasons += result.undecodedReason
            // Align cells to cappedRows: trim if over, pad with nulls if under.
            val cells = result.cells
            columnCells += when {
                cells.size >= cappedRows -> cells.subList(0, cappedRows)
                result.fullyDecoded -> cells + List(cappedRows - cells.size) {
                    DecodedCell(null, true, noneProvenance)
                }
                else -> List(cappedRows) { DecodedCell(null, false, noneProvenance) }
            }
        }

        if (columnNames.isEmpty()) return null
        return DecodedTable(
            columnNames = columnNames,
            columnTypes = columnTypes,
            rowCount = cappedRows,
            truncated = truncated,
            columns = columnCells,
            columnDecoded = columnDecoded,
            undecodedReason = undecodedReasons,
        )
    }
}
