/**
 * Arrow IPC record-batch value decoder.
 *
 * Decodes up to [rowLimit] values per column from an [ArrowRecordBatch] body,
 * producing [DecodedCell]s with byte provenance back into the source file.
 *
 * Buffer ordering follows the Arrow columnar spec (pre-order depth-first walk):
 *   https://arrow.apache.org/docs/format/Columnar.html#buffer-listing-for-each-layout
 *
 * Arrow type tags (Schema.fbs Type union, Arrow 1.0.0 and later):
 *   1=Null, 2=Int, 3=FloatingPoint, 4=Binary, 5=Utf8, 6=Bool,
 *   7=Decimal, 8=Date, 9=Time, 10=Timestamp, 11=Interval, 12=List, 13=Struct_,
 *   14=Union, 15=FixedSizeBinary, 16=FixedSizeList, 17=Map, 18=Duration,
 *   19=LargeBinary, 20=LargeUtf8, 21=LargeList, 22=RunEndEncoded, 23=BinaryView,
 *   24=Utf8View, 25=ListView, 26=LargeListView.
 */
package org.jetbrains.hexana.arrow

import org.jetbrains.hexana.codec.Codec
import org.jetbrains.hexana.codec.CodecException
import org.jetbrains.hexana.codec.decompress
import org.jetbrains.hexana.codec.isCodecSupported
import org.jetbrains.hexana.io.ByteProvenance
import org.jetbrains.hexana.io.ByteProvenanceKind
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.DecodedCell
import org.jetbrains.hexana.io.crossSectionOperation

/**
 * Decoded values for one leaf column in an Arrow record batch.
 *
 * @param leafPath   Dot-joined path through the schema (same convention as Parquet [ColumnValues]).
 * @param typeName   Human-readable Arrow type (e.g. "Int32", "Utf8", "Bool").
 * @param cells      Decoded cells. Empty when [fullyDecoded] is false.
 * @param fullyDecoded  True only for supported types decoded with per-value provenance.
 * @param undecodedReason  Non-null explanation when [fullyDecoded] is false.
 */
class ArrowColumnValues(
    val leafPath: List<String>,
    val typeName: String,
    val cells: List<DecodedCell>,
    val fullyDecoded: Boolean,
    val undecodedReason: String? = null,
)

// ---------------------------------------------------------------------------
// Arrow type tag constants
// ---------------------------------------------------------------------------

private const val TYPE_NULL: Byte = 1
private const val TYPE_INT: Byte = 2
private const val TYPE_FLOATING_POINT: Byte = 3
private const val TYPE_BINARY: Byte = 4
private const val TYPE_UTF8: Byte = 5
private const val TYPE_BOOL: Byte = 6
private const val TYPE_DECIMAL: Byte = 7
private const val TYPE_DATE: Byte = 8
private const val TYPE_TIME: Byte = 9
private const val TYPE_TIMESTAMP: Byte = 10
private const val TYPE_INTERVAL: Byte = 11
private const val TYPE_FIXED_SIZE_BINARY: Byte = 15
private const val TYPE_DURATION: Byte = 18
private const val TYPE_LARGE_BINARY: Byte = 19
private const val TYPE_LARGE_UTF8: Byte = 20

// DateUnit values (Schema.fbs DateUnit enum)
private const val DATE_UNIT_DAY: Short = 0
private const val DATE_UNIT_MILLISECOND: Short = 1

// TimeUnit values (Schema.fbs TimeUnit enum)
private const val TIME_UNIT_SECOND: Short = 0
private const val TIME_UNIT_MILLISECOND: Short = 1
private const val TIME_UNIT_MICROSECOND: Short = 2
private const val TIME_UNIT_NANOSECOND: Short = 3

// IntervalUnit values (Schema.fbs IntervalUnit enum)
private const val INTERVAL_UNIT_YEAR_MONTH: Short = 0
private const val INTERVAL_UNIT_DAY_TIME: Short = 1
private const val INTERVAL_UNIT_MONTH_DAY_NANO: Short = 2

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

/**
 * Decode up to [rowLimit] values for each leaf column in [schema] from the body of [batch].
 *
 * [file] must be the full source buffer; [bodyStart] is the absolute offset of the
 * record-batch body within [file] (from [ArrowEncapsulatedMessage.bodyStart]).
 *
 * Nested types (List, Struct, Map, Union, etc.) are not fully decoded in v1; they
 * return [ArrowColumnValues.fullyDecoded] = false. Buffer indices are still advanced
 * correctly so subsequent leaf columns are not misaligned.
 *
 * When the batch carries [ArrowRecordBatch.bodyCompression], each buffer is decompressed
 * before decoding; provenance degrades to the compressed buffer range (PAGE-level).
 */
fun decodeRecordBatch(
    file: CommonByteBuffer,
    bodyStart: Int,
    schema: ArrowSchema,
    batch: ArrowRecordBatch,
    rowLimit: Int = 10_000,
): List<ArrowColumnValues> {
    val compression = batch.bodyCompression
    val codec: Codec? = compression?.codec?.let { mapArrowCodec(it) }

    val ctx = DecodeContext(file, bodyStart, batch, codec, rowLimit)
    val results = mutableListOf<ArrowColumnValues>()

    for (i in 0 until schema.fieldCount) {
        ctx.walkField(schema.field(i), listOf(schema.field(i).name ?: "field$i"), results)
    }
    return results
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

private fun mapArrowCodec(c: ArrowBodyCodec): Codec = when (c) {
    ArrowBodyCodec.LZ4_FRAME -> Codec.LZ4
    ArrowBodyCodec.ZSTD -> Codec.ZSTD
}

/**
 * Mutable walk context that tracks which buffer/node in the batch we are currently consuming.
 * Arrow spec: buffers and nodes are emitted in pre-order depth-first schema traversal order.
 */
private class DecodeContext(
    val file: CommonByteBuffer,
    val bodyStart: Int,
    val batch: ArrowRecordBatch,
    val codec: Codec?,
    val rowLimit: Int,
) {
    var bufIdx = 0
    var nodeIdx = 0

    // Consume one buffer from the batch metadata and return its absolute offsets.
    // Returns (absOffset, length) in the source file (before decompression).
    fun nextBuffer(): Pair<Long, Long> {
        val i = bufIdx++
        val off = batch.bufferOffset(i)
        val len = batch.bufferLength(i)
        return Pair(bodyStart.toLong() + off, len)
    }

    // Consume one node from the batch metadata and return (rowCount, nullCount).
    fun nextNode(): Pair<Long, Long> {
        val i = nodeIdx++
        return Pair(batch.nodeLength(i), batch.nodeNullCount(i))
    }

    /**
     * Read a buffer from the source file as a byte array, decompressing if needed.
     * Returns (bytes, absoluteFileOffset, compressedLength, isCompressed).
     * For uncompressed buffers the decompressed bytes start at absoluteFileOffset.
     * For compressed buffers the bytes are decompressed; provenance is the compressed range.
     */
    fun readBuffer(absOff: Long, compLen: Long): Triple<ByteArray, Long, Long> {
        if (codec == null || compLen == 0L) {
            val bytes = ByteArray(compLen.toInt())
            if (compLen > 0) {
                file.crossSectionOperation {
                    file.position(absOff.toInt())
                    file.get(bytes, 0, compLen.toInt())
                }
            }
            return Triple(bytes, absOff, compLen)
        }
        // Compressed: first 8 bytes = LE uncompressed size; -1 means this buffer is uncompressed.
        val uncompressedSize = file.crossSectionOperation {
            file.position(absOff.toInt())
            file.getLongLE()
        }
        val dataLen = (compLen - 8).toInt()
        val data = ByteArray(dataLen)
        file.crossSectionOperation {
            file.position((absOff + 8).toInt())
            file.get(data, 0, dataLen)
        }
        val bytes = if (uncompressedSize == -1L) {
            data  // buffer not compressed despite global compression flag
        } else {
            decompress(codec, data, uncompressedSize.toInt())
        }
        return Triple(bytes, absOff, compLen)
    }

    /**
     * Walk one [ArrowField] (depth-first). Leaf types emit a result into [out];
     * nested types emit a coarse result and then walk children consuming their buffers.
     */
    fun walkField(
        field: ArrowField,
        path: List<String>,
        out: MutableList<ArrowColumnValues>,
    ) {
        val tag = field.typeTag
        when {
            tag == TYPE_NULL -> decodeNull(field, path, out)
            tag == TYPE_BOOL -> decodeBool(field, path, out)
            tag == TYPE_INT -> decodeInt(field, path, out)
            tag == TYPE_FLOATING_POINT -> decodeFloat(field, path, out)
            tag == TYPE_UTF8 || tag == TYPE_BINARY -> decodeVarWidth(field, path, out, largeOffsets = false)
            tag == TYPE_LARGE_UTF8 || tag == TYPE_LARGE_BINARY -> decodeVarWidth(field, path, out, largeOffsets = true)
            tag == TYPE_FIXED_SIZE_BINARY -> decodeFixedSizeBinary(field, path, out)
            tag == TYPE_DATE -> decodeDate(field, path, out)
            tag == TYPE_TIME -> decodeTime(field, path, out)
            tag == TYPE_TIMESTAMP -> decodeTimestamp(field, path, out)
            tag == TYPE_DURATION -> decodeDuration(field, path, out)
            tag == TYPE_INTERVAL -> decodeInterval(field, path, out)
            tag == TYPE_DECIMAL -> decodeDecimal(field, path, out)
            else -> decodeCoarseOrNested(field, path, out)
        }
    }

    // ----- Null -----

    private fun decodeNull(field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>) {
        // Null type: 0 buffers, 0 nodes (no data at all).
        val count = minOf(batch.length, rowLimit.toLong()).toInt()
        val cells = List(count) {
            DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
        }
        out += ArrowColumnValues(path, "Null", cells, true)
    }

    // ----- Bool -----

    private fun decodeBool(field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>) {
        val (nodeRows, nodeNulls) = nextNode()
        val (validOff, validLen) = nextBuffer()
        val (dataOff, dataLen) = nextBuffer()
        val count = minOf(nodeRows, rowLimit.toLong()).toInt()

        // Validity bitmap: bit i (LSB-first) = 1 means non-null.
        // null_count==0 means validity buffer may be empty/absent — treat as all-valid.
        val validBytes = if (validLen > 0L) {
            val b = ByteArray(validLen.toInt())
            file.crossSectionOperation { file.position(validOff.toInt()); file.get(b, 0, validLen.toInt()) }
            b
        } else null

        val (dataBytes, absDataOff, compDataLen) = readBuffer(dataOff, dataLen)

        val cells: List<DecodedCell>
        if (codec != null) {
            // Compressed: provenance is the compressed buffer range.
            val prov = ByteProvenance(dataOff, compDataLen.toInt(), ByteProvenanceKind.PAGE)
            cells = List(count) { i ->
                val isNull = nodeNulls > 0 && validBytes != null && !testBit(validBytes, i)
                if (isNull) {
                    DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
                } else {
                    val bitVal = if (dataBytes.isEmpty()) false
                        else (dataBytes[i / 8].toInt() ushr (i % 8)) and 1 == 1
                    DecodedCell(bitVal, false, prov)
                }
            }
            out += ArrowColumnValues(path, "Bool", cells, true)
        } else {
            // Uncompressed: provenance = the byte containing the bit (EXACT).
            cells = List(count) { i ->
                val isNull = nodeNulls > 0 && validBytes != null && !testBit(validBytes, i)
                if (isNull) {
                    DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
                } else {
                    val bitVal = if (dataBytes.isEmpty()) false
                        else (dataBytes[i / 8].toInt() ushr (i % 8)) and 1 == 1
                    val bitByteOff = absDataOff + (i / 8).toLong()
                    DecodedCell(bitVal, false, ByteProvenance(bitByteOff, 1, ByteProvenanceKind.EXACT))
                }
            }
            out += ArrowColumnValues(path, "Bool", cells, true)
        }
    }

    // ----- Fixed-width integers -----

    private fun decodeInt(field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>) {
        val typeTable = field.typeTable
        val bitWidth = typeTable?.getInt(0) ?: 32  // slot 0 = bitWidth
        val isSigned = typeTable?.getBool(1) ?: true  // slot 1 = isSigned
        val byteWidth = bitWidth / 8
        val typeName = "${if (isSigned) "Int" else "UInt"}$bitWidth"

        val (nodeRows, nodeNulls) = nextNode()
        val (validOff, validLen) = nextBuffer()
        val (dataOff, dataLen) = nextBuffer()
        val count = minOf(nodeRows, rowLimit.toLong()).toInt()

        val validBytes = readValidityBytes(validOff, validLen)
        val (dataBytes, absDataOff, compDataLen) = readBuffer(dataOff, dataLen)
        val provenanceKind = if (codec != null) ByteProvenanceKind.PAGE else ByteProvenanceKind.EXACT
        val coarseProv = ByteProvenance(dataOff, compDataLen.toInt(), ByteProvenanceKind.PAGE)

        val cells = List(count) { i ->
            val isNull = nodeNulls > 0 && validBytes != null && !testBit(validBytes, i)
            if (isNull) {
                DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
            } else if (codec != null) {
                val v = readFixedInt(dataBytes, i * byteWidth, byteWidth, isSigned)
                DecodedCell(v, false, coarseProv)
            } else {
                val off = absDataOff + i.toLong() * byteWidth
                val v = readFixedIntFromFile(file, off.toInt(), byteWidth, isSigned)
                DecodedCell(v, false, ByteProvenance(off, byteWidth, provenanceKind))
            }
        }
        out += ArrowColumnValues(path, typeName, cells, true)
    }

    // ----- Floating-point -----

    private fun decodeFloat(field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>) {
        val precision = field.typeTable?.getShort(0)?.toInt() ?: 1  // 0=HALF, 1=SINGLE, 2=DOUBLE
        val (typeName, byteWidth) = when (precision) {
            0 -> Pair("Float16", 2)
            1 -> Pair("Float32", 4)
            2 -> Pair("Float64", 8)
            else -> Pair("Float$precision", 4)
        }

        val (nodeRows, nodeNulls) = nextNode()
        val (validOff, validLen) = nextBuffer()
        val (dataOff, dataLen) = nextBuffer()
        val count = minOf(nodeRows, rowLimit.toLong()).toInt()

        val validBytes = readValidityBytes(validOff, validLen)
        val (dataBytes, absDataOff, compDataLen) = readBuffer(dataOff, dataLen)
        val coarseProv = ByteProvenance(dataOff, compDataLen.toInt(), ByteProvenanceKind.PAGE)

        val cells = List(count) { i ->
            val isNull = nodeNulls > 0 && validBytes != null && !testBit(validBytes, i)
            if (isNull) {
                DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
            } else if (codec != null) {
                val v = readFixedFloat(dataBytes, i * byteWidth, byteWidth)
                DecodedCell(v, false, coarseProv)
            } else {
                val off = absDataOff + i.toLong() * byteWidth
                val v = readFixedFloatFromFile(file, off.toInt(), byteWidth)
                DecodedCell(v, false, ByteProvenance(off, byteWidth, ByteProvenanceKind.EXACT))
            }
        }
        out += ArrowColumnValues(path, typeName, cells, true)
    }

    // ----- Variable-width (Utf8 / Binary / LargeUtf8 / LargeBinary) -----

    private fun decodeVarWidth(
        field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>,
        largeOffsets: Boolean,
    ) {
        val isUtf8 = field.typeTag == TYPE_UTF8 || field.typeTag == TYPE_LARGE_UTF8
        val typeName = when {
            !isUtf8 && largeOffsets -> "LargeBinary"
            !isUtf8 -> "Binary"
            largeOffsets -> "LargeUtf8"
            else -> "Utf8"
        }

        val (nodeRows, nodeNulls) = nextNode()
        val (validOff, validLen) = nextBuffer()
        val (offsetsBufOff, offsetsBufLen) = nextBuffer()
        val (dataBufOff, dataBufLen) = nextBuffer()
        val count = minOf(nodeRows, rowLimit.toLong()).toInt()

        val validBytes = readValidityBytes(validOff, validLen)

        if (codec != null) {
            // Compressed: decompress offsets and data, return PAGE provenance.
            val (offBytes, _, compOffLen) = readBuffer(offsetsBufOff, offsetsBufLen)
            val (dataBytes, _, compDataLen) = readBuffer(dataBufOff, dataBufLen)
            val coarseProv = ByteProvenance(dataBufOff, compDataLen.toInt(), ByteProvenanceKind.PAGE)
            val cells = List(count) { i ->
                val isNull = nodeNulls > 0 && validBytes != null && !testBit(validBytes, i)
                if (isNull) {
                    DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
                } else {
                    val (start, end) = readOffsetPair(offBytes, i, largeOffsets)
                    val len = (end - start).toInt().coerceAtLeast(0)
                    val bytes = if (len == 0) ByteArray(0) else dataBytes.copyOfRange(start.toInt(), end.toInt())
                    val value: Any = if (isUtf8) bytes.decodeToString() else bytes
                    DecodedCell(value, false, coarseProv)
                }
            }
            out += ArrowColumnValues(path, typeName, cells, true)
        } else {
            // Uncompressed: read offsets from file, then data slices with EXACT provenance.
            val offStride = if (largeOffsets) 8 else 4
            val cells = List(count) { i ->
                val isNull = nodeNulls > 0 && validBytes != null && !testBit(validBytes, i)
                if (isNull) {
                    DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
                } else {
                    val startOff = offsetsBufOff + i.toLong() * offStride
                    val (startVal, endVal) = readOffsetPairFromFile(file, startOff.toInt(), largeOffsets)
                    val len = (endVal - startVal).toInt().coerceAtLeast(0)
                    val absDataStart = dataBufOff + startVal
                    val bytes = ByteArray(len)
                    if (len > 0) {
                        file.crossSectionOperation {
                            file.position(absDataStart.toInt())
                            file.get(bytes, 0, len)
                        }
                    }
                    val value: Any = if (isUtf8) bytes.decodeToString() else bytes
                    DecodedCell(value, false, ByteProvenance(absDataStart, len, ByteProvenanceKind.EXACT))
                }
            }
            out += ArrowColumnValues(path, typeName, cells, true)
        }
    }

    // ----- FixedSizeBinary -----

    private fun decodeFixedSizeBinary(
        field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>,
    ) {
        val byteWidth = field.typeTable?.getInt(0) ?: 1  // slot 0 = byteWidth
        val (nodeRows, nodeNulls) = nextNode()
        val (validOff, validLen) = nextBuffer()
        val (dataOff, dataLen) = nextBuffer()
        val count = minOf(nodeRows, rowLimit.toLong()).toInt()

        val validBytes = readValidityBytes(validOff, validLen)
        val coarseProv = ByteProvenance(dataOff, dataLen.toInt(), ByteProvenanceKind.PAGE)

        val cells = List(count) { i ->
            val isNull = nodeNulls > 0 && validBytes != null && !testBit(validBytes, i)
            if (isNull) {
                DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
            } else if (codec != null) {
                DecodedCell(null, false, coarseProv)  // compressed: return coarse for FixedSizeBinary
            } else {
                val off = dataOff + i.toLong() * byteWidth
                val bytes = ByteArray(byteWidth)
                file.crossSectionOperation { file.position(off.toInt()); file.get(bytes, 0, byteWidth) }
                DecodedCell(bytes, false, ByteProvenance(off, byteWidth, ByteProvenanceKind.EXACT))
            }
        }
        out += ArrowColumnValues(path, "FixedSizeBinary[$byteWidth]", cells, true)
    }

    // ----- Date -----

    private fun decodeDate(field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>) {
        val dt = field.dateType()
        val unit = dt?.unit ?: DATE_UNIT_MILLISECOND
        // Date32 (DAY): int32, 4 bytes. Date64 (MILLISECOND): int64, 8 bytes.
        val (byteWidth, typeName) = when (unit) {
            DATE_UNIT_DAY -> Pair(4, "Date32")
            else -> Pair(8, "Date64")
        }
        decodeFixedWidthInt(field, path, out, byteWidth, typeName, signed = true)
    }

    // ----- Time -----

    private fun decodeTime(field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>) {
        val tt = field.timeType()
        val bitWidth = tt?.bitWidth ?: 32
        val unit = tt?.unit ?: TIME_UNIT_MILLISECOND
        val unitName = timeUnitName(unit)
        val byteWidth = bitWidth / 8
        val typeName = "Time${bitWidth}[$unitName]"
        decodeFixedWidthInt(field, path, out, byteWidth, typeName, signed = true)
    }

    // ----- Timestamp -----

    private fun decodeTimestamp(field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>) {
        val tst = field.timestampType()
        val unit = tst?.unit ?: TIME_UNIT_MILLISECOND
        val tz = tst?.timezone
        val unitName = timeUnitName(unit)
        val typeName = if (tz != null) "Timestamp[$unitName, $tz]" else "Timestamp[$unitName]"
        decodeFixedWidthInt(field, path, out, byteWidth = 8, typeName = typeName, signed = true)
    }

    // ----- Duration -----

    private fun decodeDuration(field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>) {
        val dur = field.durationType()
        val unit = dur?.unit ?: TIME_UNIT_MILLISECOND
        val unitName = timeUnitName(unit)
        decodeFixedWidthInt(field, path, out, byteWidth = 8, typeName = "Duration[$unitName]", signed = true)
    }

    // ----- Interval -----

    private fun decodeInterval(field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>) {
        val iv = field.intervalType()
        val unit = iv?.unit ?: INTERVAL_UNIT_DAY_TIME
        when (unit) {
            INTERVAL_UNIT_YEAR_MONTH -> {
                // YEAR_MONTH: single int32 = total months
                decodeFixedWidthInt(field, path, out, byteWidth = 4,
                    typeName = "Interval[YEAR_MONTH]", signed = true)
            }
            INTERVAL_UNIT_DAY_TIME -> {
                // DAY_TIME: 8 bytes = int32 days + int32 ms; decode as a pair string
                decodeFixedWidthInterval8(field, path, out)
            }
            else -> {
                // MONTH_DAY_NANO: 16 bytes = int32 months + int32 days + int64 nanos
                decodeFixedWidthInterval16(field, path, out)
            }
        }
    }

    /** Decode DAY_TIME interval (8 bytes = int32 days || int32 milliseconds). */
    private fun decodeFixedWidthInterval8(
        field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>,
    ) {
        val byteWidth = 8
        val (nodeRows, nodeNulls) = nextNode()
        val (validOff, validLen) = nextBuffer()
        val (dataOff, dataLen) = nextBuffer()
        val count = minOf(nodeRows, rowLimit.toLong()).toInt()

        val validBytes = readValidityBytes(validOff, validLen)
        val (dataBytes, absDataOff, compDataLen) = readBuffer(dataOff, dataLen)
        val coarseProv = ByteProvenance(dataOff, compDataLen.toInt(), ByteProvenanceKind.PAGE)

        val cells = List(count) { i ->
            val isNull = nodeNulls > 0 && validBytes != null && !testBit(validBytes, i)
            if (isNull) {
                DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
            } else if (codec != null) {
                val daysMs = readInterval8FromBytes(dataBytes, i * byteWidth)
                DecodedCell(daysMs, false, coarseProv)
            } else {
                val off = absDataOff + i.toLong() * byteWidth
                val daysMs = readInterval8FromFile(file, off.toInt())
                DecodedCell(daysMs, false, ByteProvenance(off, byteWidth, ByteProvenanceKind.EXACT))
            }
        }
        out += ArrowColumnValues(path, "Interval[DAY_TIME]", cells, true)
    }

    /** Decode MONTH_DAY_NANO interval (16 bytes = int32 months || int32 days || int64 nanos). */
    private fun decodeFixedWidthInterval16(
        field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>,
    ) {
        val byteWidth = 16
        val (nodeRows, nodeNulls) = nextNode()
        val (validOff, validLen) = nextBuffer()
        val (dataOff, dataLen) = nextBuffer()
        val count = minOf(nodeRows, rowLimit.toLong()).toInt()

        val validBytes = readValidityBytes(validOff, validLen)
        val (dataBytes, absDataOff, compDataLen) = readBuffer(dataOff, dataLen)
        val coarseProv = ByteProvenance(dataOff, compDataLen.toInt(), ByteProvenanceKind.PAGE)

        val cells = List(count) { i ->
            val isNull = nodeNulls > 0 && validBytes != null && !testBit(validBytes, i)
            if (isNull) {
                DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
            } else if (codec != null) {
                val repr = readInterval16Repr(dataBytes, i * byteWidth)
                DecodedCell(repr, false, coarseProv)
            } else {
                val off = absDataOff + i.toLong() * byteWidth
                val repr = readInterval16ReprFromFile(file, off.toInt())
                DecodedCell(repr, false, ByteProvenance(off, byteWidth, ByteProvenanceKind.EXACT))
            }
        }
        out += ArrowColumnValues(path, "Interval[MONTH_DAY_NANO]", cells, true)
    }

    // ----- Decimal -----

    /**
     * Decode Decimal128 (16 bytes) or Decimal256 (32 bytes) fixed-width columns.
     *
     * Values are two's-complement little-endian integers. java.math.BigInteger / BigDecimal are not
     * available in commonMain (Kotlin Multiplatform). We use a best-effort fallback:
     *   - If the value fits in a signed 64-bit Long (high bytes are sign-extension of bit 63),
     *     render as a scaled decimal string: sign + integer part + "." + fractional digits.
     *   - Otherwise render raw bytes as lowercase hex (little-endian order, as stored).
     * Both paths emit EXACT provenance at the full 16/32-byte element.
     */
    private fun decodeDecimal(field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>) {
        val dt = field.decimalType()
        val precision = dt?.precision ?: 10
        val scale = dt?.scale ?: 0
        val bitWidth = dt?.bitWidth ?: 128
        val byteWidth = bitWidth / 8
        val typeName = "Decimal$bitWidth(p=$precision,s=$scale)"

        val (nodeRows, nodeNulls) = nextNode()
        val (validOff, validLen) = nextBuffer()
        val (dataOff, dataLen) = nextBuffer()
        val count = minOf(nodeRows, rowLimit.toLong()).toInt()

        val validBytes = readValidityBytes(validOff, validLen)
        val (dataBytes, absDataOff, compDataLen) = readBuffer(dataOff, dataLen)
        val coarseProv = ByteProvenance(dataOff, compDataLen.toInt(), ByteProvenanceKind.PAGE)

        val cells = List(count) { i ->
            val isNull = nodeNulls > 0 && validBytes != null && !testBit(validBytes, i)
            if (isNull) {
                DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
            } else if (codec != null) {
                val repr = decimalRepr(dataBytes, i * byteWidth, byteWidth, scale)
                DecodedCell(repr, false, coarseProv)
            } else {
                val off = absDataOff + i.toLong() * byteWidth
                val raw = ByteArray(byteWidth)
                file.crossSectionOperation { file.position(off.toInt()); file.get(raw, 0, byteWidth) }
                val repr = decimalRepr(raw, 0, byteWidth, scale)
                DecodedCell(repr, false, ByteProvenance(off, byteWidth, ByteProvenanceKind.EXACT))
            }
        }
        out += ArrowColumnValues(path, typeName, cells, true)
    }

    // ----- Shared fixed-width int decoder -----

    /**
     * Generic [validity][data] decoder for any fixed-width signed or unsigned integer type.
     * Used by Date, Time, Timestamp, Duration, and YEAR_MONTH Interval.
     */
    private fun decodeFixedWidthInt(
        field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>,
        byteWidth: Int, typeName: String, signed: Boolean,
    ) {
        val (nodeRows, nodeNulls) = nextNode()
        val (validOff, validLen) = nextBuffer()
        val (dataOff, dataLen) = nextBuffer()
        val count = minOf(nodeRows, rowLimit.toLong()).toInt()

        val validBytes = readValidityBytes(validOff, validLen)
        val (dataBytes, absDataOff, compDataLen) = readBuffer(dataOff, dataLen)
        val coarseProv = ByteProvenance(dataOff, compDataLen.toInt(), ByteProvenanceKind.PAGE)

        val cells = List(count) { i ->
            val isNull = nodeNulls > 0 && validBytes != null && !testBit(validBytes, i)
            if (isNull) {
                DecodedCell(null, true, ByteProvenance(0L, 0, ByteProvenanceKind.NONE))
            } else if (codec != null) {
                val v = readFixedInt(dataBytes, i * byteWidth, byteWidth, signed)
                DecodedCell(v, false, coarseProv)
            } else {
                val off = absDataOff + i.toLong() * byteWidth
                val v = readFixedIntFromFile(file, off.toInt(), byteWidth, signed)
                DecodedCell(v, false, ByteProvenance(off, byteWidth, ByteProvenanceKind.EXACT))
            }
        }
        out += ArrowColumnValues(path, typeName, cells, true)
    }

    // ----- Nested / unsupported (coarse) -----

    private fun decodeCoarseOrNested(
        field: ArrowField, path: List<String>, out: MutableList<ArrowColumnValues>,
    ) {
        val tag = field.typeTag.toInt() and 0xFF
        val typeName = arrowTypeName(tag)
        // Consume the correct number of buffers + nodes for this type so the walk stays aligned.
        val (buffersConsumed, hasNode) = nestedBufferShape(tag)
        if (hasNode) nextNode()
        repeat(buffersConsumed) { nextBuffer() }
        // Walk children if any (List has 1 child, Struct has N, etc.).
        for (i in 0 until field.childCount) {
            val child = field.child(i)
            val childPath = path + (child.name ?: "item")
            walkField(child, childPath, out)
        }
        // Only emit one coarse entry for the parent (children are separate).
        if (field.childCount == 0) {
            out += ArrowColumnValues(path, typeName, emptyList(), false,
                "Arrow type '$typeName' (tag=$tag) is not decoded in v1")
        }
        // If we consumed buffers for a nested type but its children produced output, we're done.
    }

    // ----- Utility helpers -----

    private fun readValidityBytes(validOff: Long, validLen: Long): ByteArray? {
        if (validLen == 0L) return null
        return ByteArray(validLen.toInt()).also { b ->
            file.crossSectionOperation { file.position(validOff.toInt()); file.get(b, 0, validLen.toInt()) }
        }
    }
}

// ---------------------------------------------------------------------------
// Pure-function helpers (no context state)
// ---------------------------------------------------------------------------

private fun testBit(bitmap: ByteArray, i: Int): Boolean =
    (bitmap[i / 8].toInt() ushr (i % 8)) and 1 == 1

private fun readFixedIntFromFile(file: CommonByteBuffer, pos: Int, byteWidth: Int, signed: Boolean): Any {
    return file.crossSectionOperation {
        file.position(pos)
        when (byteWidth) {
            1 -> { val b = file.get(); if (signed) b.toInt() else b.toUByte().toInt() }
            2 -> { val v = file.getShortLE(); if (signed) v.toInt() else v.toUShort().toInt() }
            4 -> { val v = file.getIntLE(); if (signed) v else v.toUInt() }
            8 -> { val v = file.getLongLE(); if (signed) v else v.toULong() }
            else -> ByteArray(byteWidth).also { file.get(it, 0, byteWidth) }
        }
    }
}

private fun readFixedInt(bytes: ByteArray, pos: Int, byteWidth: Int, signed: Boolean): Any {
    return when (byteWidth) {
        1 -> { val b = bytes[pos]; if (signed) b.toInt() else b.toUByte().toInt() }
        2 -> {
            val v = (bytes[pos].toUByte().toInt() or (bytes[pos + 1].toUByte().toInt() shl 8)).toShort()
            if (signed) v.toInt() else v.toUShort().toInt()
        }
        4 -> {
            var v = 0; for (i in 0 until 4) v = v or (bytes[pos + i].toUByte().toInt() shl (i * 8))
            if (signed) v else v.toUInt()
        }
        8 -> {
            var v = 0L; for (i in 0 until 8) v = v or (bytes[pos + i].toUByte().toLong() shl (i * 8))
            if (signed) v else v.toULong()
        }
        else -> bytes.copyOfRange(pos, pos + byteWidth)
    }
}

private fun readFixedFloatFromFile(file: CommonByteBuffer, pos: Int, byteWidth: Int): Any {
    return file.crossSectionOperation {
        file.position(pos)
        when (byteWidth) {
            4 -> file.getFloatLE()
            8 -> file.getDoubleLE()
            else -> ByteArray(byteWidth).also { file.get(it, 0, byteWidth) }
        }
    }
}

private fun readFixedFloat(bytes: ByteArray, pos: Int, byteWidth: Int): Any {
    return when (byteWidth) {
        4 -> {
            var bits = 0; for (i in 0 until 4) bits = bits or (bytes[pos + i].toUByte().toInt() shl (i * 8))
            Float.fromBits(bits)
        }
        8 -> {
            var bits = 0L; for (i in 0 until 8) bits = bits or (bytes[pos + i].toUByte().toLong() shl (i * 8))
            Double.fromBits(bits)
        }
        else -> bytes.copyOfRange(pos, pos + byteWidth)
    }
}

/** Read a (start, end) offset pair from a decompressed offsets byte array at element [i]. */
private fun readOffsetPair(bytes: ByteArray, i: Int, large: Boolean): Pair<Long, Long> {
    return if (large) {
        val stride = 8
        val s = readLong8(bytes, i * stride)
        val e = readLong8(bytes, (i + 1) * stride)
        Pair(s, e)
    } else {
        val stride = 4
        val s = readInt4(bytes, i * stride).toLong()
        val e = readInt4(bytes, (i + 1) * stride).toLong()
        Pair(s, e)
    }
}

/** Read a (start, end) offset pair directly from the file at absolute [pos]. */
private fun readOffsetPairFromFile(file: CommonByteBuffer, pos: Int, large: Boolean): Pair<Long, Long> {
    return file.crossSectionOperation {
        file.position(pos)
        if (large) {
            val s = file.getLongLE()
            val e = file.getLongLE()
            Pair(s, e)
        } else {
            val s = file.getIntLE().toLong()
            val e = file.getIntLE().toLong()
            Pair(s, e)
        }
    }
}

private fun readInt4(bytes: ByteArray, pos: Int): Int {
    var v = 0; for (i in 0 until 4) v = v or (bytes[pos + i].toUByte().toInt() shl (i * 8))
    return v
}

private fun readLong8(bytes: ByteArray, pos: Int): Long {
    var v = 0L; for (i in 0 until 8) v = v or (bytes[pos + i].toUByte().toLong() shl (i * 8))
    return v
}

/**
 * Returns (buffersConsumed, hasNode) for nested/unsupported types so the walk stays aligned.
 *
 * Arrow columnar spec buffer counts per layout:
 *   List / LargeList / Map / FixedSizeList / LargeListView / ListView: 2 (validity + offsets/size-buffer), 1 node
 *   Struct_: 1 (validity), 1 node
 *   Union (dense): 2 (type-ids + offsets), 0 nodes
 *   Union (sparse): 1 (type-ids), 0 nodes
 *   RunEndEncoded: 0, 0 nodes (children only)
 *   Other unsupported: conservative 2, 1 node
 */
private fun nestedBufferShape(tag: Int): Pair<Int, Boolean> = when (tag) {
    12 -> Pair(2, true)   // List
    13 -> Pair(1, true)   // Struct_
    14 -> Pair(2, false)  // Union (dense; sparse=1 but use 2 conservatively)
    16 -> Pair(2, true)   // FixedSizeList
    17 -> Pair(2, true)   // Map
    21 -> Pair(2, true)   // LargeList
    22 -> Pair(0, false)  // RunEndEncoded (children only)
    25 -> Pair(2, true)   // ListView
    26 -> Pair(2, true)   // LargeListView
    else -> Pair(2, true) // Unknown: consume 2 + node to stay safe
}

// ---------------------------------------------------------------------------
// Temporal / Interval / Decimal helpers
// ---------------------------------------------------------------------------

private fun timeUnitName(unit: Short): String = when (unit) {
    TIME_UNIT_SECOND -> "s"
    TIME_UNIT_MILLISECOND -> "ms"
    TIME_UNIT_MICROSECOND -> "us"
    TIME_UNIT_NANOSECOND -> "ns"
    else -> "unit$unit"
}

/**
 * Read a DAY_TIME interval (8 bytes LE: int32 days at [pos], int32 ms at [pos+4])
 * from a decompressed byte array. Returns a human-readable "days=D ms=M" string.
 */
private fun readInterval8FromBytes(bytes: ByteArray, pos: Int): String {
    val days = readInt4(bytes, pos)
    val ms = readInt4(bytes, pos + 4)
    return "days=$days ms=$ms"
}

/**
 * Read a DAY_TIME interval from the source file at absolute [pos].
 */
private fun readInterval8FromFile(file: CommonByteBuffer, pos: Int): String {
    return file.crossSectionOperation {
        file.position(pos)
        val days = file.getIntLE()
        val ms = file.getIntLE()
        "days=$days ms=$ms"
    }
}

/**
 * Read a MONTH_DAY_NANO interval (16 bytes LE: int32 months, int32 days, int64 nanos)
 * from a decompressed byte array. Returns "months=M days=D nanos=N".
 */
private fun readInterval16Repr(bytes: ByteArray, pos: Int): String {
    val months = readInt4(bytes, pos)
    val days = readInt4(bytes, pos + 4)
    val nanos = readLong8(bytes, pos + 8)
    return "months=$months days=$days nanos=$nanos"
}

/**
 * Read a MONTH_DAY_NANO interval from the source file at absolute [pos].
 */
private fun readInterval16ReprFromFile(file: CommonByteBuffer, pos: Int): String {
    return file.crossSectionOperation {
        file.position(pos)
        val months = file.getIntLE()
        val days = file.getIntLE()
        val nanos = file.getLongLE()
        "months=$months days=$days nanos=$nanos"
    }
}

/**
 * Decode a Decimal128 or Decimal256 value from [bytes] at [pos] (byteWidth=16 or 32).
 *
 * The value is a two's-complement little-endian integer.
 * commonMain cannot use java.math.BigInteger. Approach:
 *   1. Read the low 8 bytes as a signed int64 (LE).
 *   2. Check that the remaining high bytes are pure sign-extension of bit 63 of the int64.
 *   3. If yes, render as a scaled decimal: divide by 10^scale with proper sign and decimal point.
 *   4. If no (value exceeds int64 range), render as lowercase hex of the raw bytes (LE order).
 */
private fun decimalRepr(bytes: ByteArray, pos: Int, byteWidth: Int, scale: Int): String {
    val low8 = readLong8(bytes, pos)
    // Check sign extension: all high bytes must equal 0x00 (positive) or 0xFF (negative)
    val signExtByte: Byte = if (low8 < 0) -1 else 0
    val fitsInLong = (8 until byteWidth).all { bytes[pos + it] == signExtByte }

    return if (fitsInLong) {
        scaledDecimalString(low8, scale)
    } else {
        // Fallback: hex of the raw LE bytes
        buildString {
            append("0x")
            for (i in pos until pos + byteWidth) {
                val b = bytes[i].toInt() and 0xFF
                append(HEX_DIGITS[b ushr 4])
                append(HEX_DIGITS[b and 0xF])
            }
        }
    }
}

private val HEX_DIGITS = "0123456789abcdef"

/**
 * Render [value] as a scaled decimal string with [scale] fractional digits.
 * Example: scaledDecimalString(12810, 2) = "128.10"
 * Example: scaledDecimalString(-1484, 2) = "-14.84"
 * Example: scaledDecimalString(32697, 0) = "32697"
 */
internal fun scaledDecimalString(value: Long, scale: Int): String {
    if (scale <= 0) return value.toString()
    val neg = value < 0
    // Work in absolute value; use Long arithmetic (safe for scale <= 18)
    val absVal = if (neg) {
        // Handle Long.MIN_VALUE: -Long.MIN_VALUE overflows, so use string approach for this edge case
        if (value == Long.MIN_VALUE) return buildMinValueDecimal(scale)
        -value
    } else value

    val divisor = pow10(scale)
    val intPart = absVal / divisor
    val fracPart = absVal % divisor
    // Format fraction with leading zeros to exactly `scale` digits
    val fracStr = fracPart.toString().padStart(scale, '0')
    return buildString {
        if (neg) append('-')
        append(intPart)
        append('.')
        append(fracStr)
    }
}

/** 10^n for n in 0..18. */
private fun pow10(n: Int): Long {
    var r = 1L
    repeat(n.coerceAtMost(18)) { r *= 10L }
    return r
}

/** Edge-case handler for Long.MIN_VALUE (-9223372036854775808) with scale digits. */
private fun buildMinValueDecimal(scale: Int): String {
    val raw = "9223372036854775808"
    if (scale >= raw.length) {
        return "-0." + "0".repeat(scale - raw.length) + raw
    }
    val intPart = raw.dropLast(scale)
    val fracPart = raw.takeLast(scale)
    return "-${intPart.ifEmpty { "0" }}.$fracPart"
}

private fun arrowTypeName(tag: Int): String = when (tag) {
    0 -> "None"; 1 -> "Null"; 2 -> "Int"; 3 -> "FloatingPoint"
    4 -> "Binary"; 5 -> "Utf8"; 6 -> "Bool"; 7 -> "Decimal"
    8 -> "Date"; 9 -> "Time"; 10 -> "Timestamp"; 11 -> "Interval"
    12 -> "List"; 13 -> "Struct"; 14 -> "Union"; 15 -> "FixedSizeBinary"
    16 -> "FixedSizeList"; 17 -> "Map"; 18 -> "Duration"; 19 -> "LargeBinary"
    20 -> "LargeUtf8"; 21 -> "LargeList"; 22 -> "RunEndEncoded"; 23 -> "BinaryView"
    24 -> "Utf8View"; 25 -> "ListView"; 26 -> "LargeListView"
    else -> "Unknown($tag)"
}
