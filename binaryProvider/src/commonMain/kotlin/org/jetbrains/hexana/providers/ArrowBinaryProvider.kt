package org.jetbrains.hexana.providers

import org.jetbrains.hexana.arrow.ArrowFooter
import org.jetbrains.hexana.arrow.ArrowSchema
import org.jetbrains.hexana.arrow.ArrowStreamContents
import org.jetbrains.hexana.arrow.MessageHeaderType
import org.jetbrains.hexana.arrow.decodeRecordBatch
import org.jetbrains.hexana.arrow.readArrowFileFooter
import org.jetbrains.hexana.arrow.readArrowStream
import org.jetbrains.hexana.arrow.readEncapsulatedMessage
import org.jetbrains.hexana.io.ByteProvenance
import org.jetbrains.hexana.io.ByteProvenanceKind
import org.jetbrains.hexana.io.CommonByteBuffer
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
) : BinaryProvider {
    override val bytes: CommonByteBuffer get() = sharedBytes.duplicate()

    val streamContents: ArrowStreamContents? by lazy {
        if (format != ArrowFormat.STREAM) return@lazy null
        try { readArrowStream(bytes) } catch (_: Throwable) { null }
    }

    val fileFooter: ArrowFooter? by lazy {
        if (format != ArrowFormat.FILE) return@lazy null
        try { readArrowFileFooter(bytes) } catch (_: Throwable) { null }
    }

    val schema: ArrowSchema? get() = when (format) {
        ArrowFormat.STREAM -> streamContents?.schema
        ArrowFormat.FILE -> fileFooter?.schema
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
        val file = bytes

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
                // Read the first record-batch block's encapsulated message.
                val blockOff = footer.blockOffset(vec, 0).toInt()
                try { readEncapsulatedMessage(file, blockOff) } catch (_: Throwable) { null }
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
            val cells = col.cells
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
