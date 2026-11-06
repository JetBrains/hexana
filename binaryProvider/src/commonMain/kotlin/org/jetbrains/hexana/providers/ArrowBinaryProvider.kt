package org.jetbrains.hexana.providers

import org.jetbrains.hexana.arrow.ArrowFooter
import org.jetbrains.hexana.arrow.ArrowSchema
import org.jetbrains.hexana.arrow.ArrowStreamContents
import org.jetbrains.hexana.arrow.MessageHeaderType
import org.jetbrains.hexana.arrow.decodeRecordBatch
import org.jetbrains.hexana.arrow.readArrowFileFooter
import org.jetbrains.hexana.arrow.readArrowStream
import org.jetbrains.hexana.arrow.readEncapsulatedMessage
import org.jetbrains.hexana.flatbuffers.FlatBufferVector
import org.jetbrains.hexana.io.ByteProvenance
import org.jetbrains.hexana.io.ByteProvenanceKind
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.asLargeBytes
import org.jetbrains.hexana.io.LargeBytes
import org.jetbrains.hexana.io.DecodedCell

private val ARROW1 = byteArrayOf(0x41, 0x52, 0x52, 0x4F, 0x57, 0x31)

fun isArrowFileMagic(buf: CommonByteBuffer): Boolean {
    if (buf.size < 12) return false
    return (0 until 6).all { buf[it] == ARROW1[it] } &&
        (0 until 6).all { buf[buf.size - 6 + it] == ARROW1[it] }
}

fun isArrowStreamMagic(buf: CommonByteBuffer): Boolean {
    // Stream: starts with 0xFFFFFFFF continuation marker (4 bytes)
    if (buf.size < 8) return false
    return buf[0] == 0xFF.toByte() && buf[1] == 0xFF.toByte() &&
        buf[2] == 0xFF.toByte() && buf[3] == 0xFF.toByte()
}

enum class ArrowFormat { FILE, STREAM }

class ArrowBinaryProvider(
    private val sharedBytes: CommonByteBuffer,
    val format: ArrowFormat,
    val sourcePath: String? = null,
    /**
     * The whole file. Larger than [sharedBytes] for an IPC file past 2 GiB, whose footer and block
     * index sit at the end; the file format is read from this. A stream is read from its start.
     */
    val source: LargeBytes = sharedBytes.asLargeBytes(),
) : BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()

    override val fileSize: Long get() = source.size

    val streamContents: ArrowStreamContents? by lazy {
        if (format != ArrowFormat.STREAM) return@lazy null
        try { readArrowStream(bytes) } catch (_: Throwable) { null }
    }

    val fileFooter: ArrowFooter? by lazy {
        if (format != ArrowFormat.FILE) return@lazy null
        try { readArrowFileFooter(source) } catch (_: Throwable) { null }
    }

    val schema: ArrowSchema? get() = when (format) {
        ArrowFormat.STREAM -> streamContents?.schema
        ArrowFormat.FILE -> fileFooter?.schema
    }

    /**
     * One range per IPC message.
     *
     * For the file format the footer's block vectors give each message's offset and
     * its metadata + body lengths directly, and whatever follows the last block is the
     * footer itself. For the stream format an encapsulated message only records where
     * the *next* one starts, so the messages are chained: the schema runs from 0 to the
     * start of `streamContents.messages[0]`, and each later message starts where its
     * predecessor ended.
     */
    override val structureRanges: List<StructureRange> by lazy {
        when (format) {
            ArrowFormat.FILE -> fileStructureRanges()
            ArrowFormat.STREAM -> streamStructureRanges()
        }
    }

    private fun fileStructureRanges(): List<StructureRange> {
        val footer = fileFooter ?: return emptyList()
        val total = source.size
        val ranges = mutableListOf<StructureRange>()
        var lastEnd = 0L
        val blockVectors = listOf(
            footer.dictionariesVector to "Dictionary",
            footer.recordBatchesVector to "Record batch",
        )
        for ((vector, label) in blockVectors) {
            val blocks: FlatBufferVector = vector ?: continue
            for (index in 0 until blocks.length) {
                val offset = footer.blockOffset(blocks, index)
                val size = footer.blockMetaDataLength(blocks, index).toLong() +
                    footer.blockBodyLength(blocks, index)
                structureRangeOrNull(offset, size, "$label $index", total, tabName = "Record Batches")
                    ?.let {
                        ranges.add(it)
                        // Only validated blocks move the footer boundary; a block length
                        // a hostile footer inflated would otherwise drag it past the file.
                        lastEnd = maxOf(lastEnd, it.offset + it.size)
                    }
            }
        }
        ranges.sortBy { it.offset }
        structureRangeOrNull(lastEnd, total - lastEnd, "Footer", total, tabName = "Metadata")
            ?.let { ranges.add(it) }
        return ranges
    }

    private fun streamStructureRanges(): List<StructureRange> {
        val messages = streamContents?.messages ?: return emptyList()
        val total = bytes.size
        // The schema message is consumed by readArrowStream and not kept, so re-read its
        // framing to learn where it ends.
        val schemaEnd = runCatching { readEncapsulatedMessage(bytes, 0)?.nextMessageStart }
            .getOrNull() ?: return emptyList()
        val ranges = mutableListOf<StructureRange>()
        structureRangeOrNull(0L, schemaEnd.toLong(), "Schema", total, tabName = "Schema")
            ?.let { ranges.add(it) }
        var start = schemaEnd
        messages.forEachIndexed { index, message ->
            structureRangeOrNull(
                offset = start.toLong(),
                size = (message.nextMessageStart - start).toLong(),
                label = "${messageLabel(message.message.headerType)} $index",
                fileSize = total,
                tabName = "Messages",
            )?.let { ranges.add(it) }
            start = message.nextMessageStart
        }
        return ranges
    }

    /**
     * Decoded data table for the first record batch, capped at [DEFAULT_ROW_LIMIT] rows.
     * Null when no record batch is available or decoding throws unexpectedly.
     */
    val decodedTable: DecodedTable? by lazy {
        try { buildDecodedTable() } catch (_: Throwable) { null }
    }

    private fun buildDecodedTable(): DecodedTable? {
        val arrowSchema = schema ?: return null
        if (arrowSchema.fieldCount == 0) return null

        val rowLimit = DEFAULT_ROW_LIMIT
        val noneProvenance = ByteProvenance(0L, 0, ByteProvenanceKind.NONE)
        var file = bytes
        // File offset of [file]'s first byte: non-zero when the first batch is read through a window.
        var base = 0L

        // Find the first RecordBatch message.
        val firstBatch = when (format) {
            ArrowFormat.STREAM -> {
                val contents = streamContents ?: return null
                contents.messages.firstOrNull { it.message.headerType == MessageHeaderType.RECORD_BATCH }
            }
            ArrowFormat.FILE -> {
                val footer = fileFooter ?: return null
                val vec = footer.recordBatchesVector ?: return null
                if (footer.recordBatchBlockCount == 0) return null
                // Read the first record-batch block's encapsulated message. The decoders are
                // Int-addressed, so a block past the buffer's reach is read through a window over it.
                val blockOff = footer.blockOffset(vec, 0)
                val blockLength = footer.blockMetaDataLength(vec, 0).toLong() + footer.blockBodyLength(vec, 0)
                if (blockOff + blockLength > file.size) {
                    if (blockOff < 0 || blockLength !in 0..Int.MAX_VALUE.toLong() || blockOff + blockLength > source.size) return null
                    file = source.window(blockOff, blockLength.toInt())
                    base = blockOff
                }
                try { readEncapsulatedMessage(file, (blockOff - base).toInt()) } catch (_: Throwable) { null }
            }
        } ?: return null

        val batch = firstBatch.message.asRecordBatch() ?: return null
        val cols = decodeRecordBatch(file, firstBatch.bodyStart, arrowSchema, batch, rowLimit)
        if (cols.isEmpty()) return null

        val cappedRows = minOf(batch.length, rowLimit.toLong()).toInt()
        val moreBatches = when (format) {
            ArrowFormat.STREAM -> (streamContents?.messages?.count {
                it.message.headerType == MessageHeaderType.RECORD_BATCH
            } ?: 0) > 1
            ArrowFormat.FILE -> (fileFooter?.recordBatchBlockCount ?: 0) > 1
        }
        val truncated = batch.length > rowLimit || moreBatches

        val columnNames = mutableListOf<String>()
        val columnTypes = mutableListOf<String>()
        val columnCells = mutableListOf<List<DecodedCell>>()
        val columnDecoded = mutableListOf<Boolean>()
        val undecodedReasons = mutableListOf<String?>()

        for (col in cols) {
            columnNames += col.leafPath.lastOrNull() ?: "?"
            columnTypes += col.typeName
            columnDecoded += col.fullyDecoded
            undecodedReasons += col.undecodedReason
            // Values decoded from a window are located within it; move them to their file offsets.
            val cells = if (base == 0L) col.cells else col.cells.map { cell ->
                val at = cell.provenance
                if (at.kind == ByteProvenanceKind.NONE) cell
                else DecodedCell(cell.value, cell.isNull, ByteProvenance(at.offset + base, at.size, at.kind))
            }
            columnCells += when {
                cells.size >= cappedRows -> cells.subList(0, cappedRows)
                col.fullyDecoded -> cells + List(cappedRows - cells.size) {
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

/** Hex-view label for a stream message, e.g. `RECORD_BATCH` -> `"Record batch"`. */
private fun messageLabel(headerType: MessageHeaderType): String =
    headerType.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
