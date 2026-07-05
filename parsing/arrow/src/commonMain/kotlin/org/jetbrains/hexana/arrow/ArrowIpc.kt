/**
 * Apache Arrow IPC framing and FlatBuffers metadata accessors.
 *
 * FlatBuffers slot numbers derived from the canonical Arrow schemas:
 *   format/Message.fbs, format/Schema.fbs, format/File.fbs
 *   fetched from apache/arrow main branch, 2026-07-03.
 *   Arrow spec: https://arrow.apache.org/docs/format/IPC.html
 *
 * IPC wire format:
 *   Stream: sequence of encapsulated messages terminated by EOS.
 *   File:   ARROW1 magic (6B) | messages | EOS | FlatBuffers Footer | footer_len (i32 LE) | ARROW1 magic (6B)
 *
 * Each encapsulated message (stream or file body):
 *   [u32 continuation = 0xFFFFFFFF][u32 metadata_len LE][FlatBuffers Message blob][padding to 8-byte][body bytes]
 */
package org.jetbrains.hexana.arrow

import org.jetbrains.hexana.flatbuffers.FlatBufferTable
import org.jetbrains.hexana.flatbuffers.FlatBufferVector
import org.jetbrains.hexana.flatbuffers.FlatBuffers
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation

// ---------------------------------------------------------------------------
// Arrow IPC magic constants
// ---------------------------------------------------------------------------

private const val MAGIC = "ARROW1"
private val MAGIC_BYTES = byteArrayOf(0x41, 0x52, 0x52, 0x4F, 0x57, 0x31)
private const val CONTINUATION_MARKER = -1  // 0xFFFFFFFF as signed Int
private const val EOS_METADATA_LEN = 0

// ---------------------------------------------------------------------------
// Message header union type tag values (MessageHeader enum in Message.fbs)
// ---------------------------------------------------------------------------

enum class MessageHeaderType(val tag: Byte) {
    NONE(0),
    SCHEMA(1),
    DICTIONARY_BATCH(2),
    RECORD_BATCH(3),
    TENSOR(4),
    SPARSE_TENSOR(5);

    companion object {
        fun fromTag(tag: Byte): MessageHeaderType =
            entries.firstOrNull { it.tag == tag } ?: NONE
    }
}

// ---------------------------------------------------------------------------
// FlatBuffers accessor wrappers (hand-written; slots from .fbs declaration order)
// ---------------------------------------------------------------------------

/**
 * Accessor for the FlatBuffers `Message` table (Message.fbs).
 * Slots:
 *   0 = version (int16)
 *   1 = header_type (uint8, union discriminant)
 *   2 = header (table offset, union value)
 *   3 = bodyLength (int64)
 *   4 = custom_metadata (vector<KeyValue>)
 */
class ArrowMessage(val t: FlatBufferTable) {
    /** MetadataVersion enum value (slot 0). */
    val version: Short get() = t.getShort(0)

    /** Union type tag for the `header` field (slot 1). */
    val headerType: MessageHeaderType get() = MessageHeaderType.fromTag(t.getByte(1))

    /** Raw FlatBufferTable for the header union value (slot 2). Null if absent. */
    val headerTable: FlatBufferTable? get() = t.getTable(2)

    /** Total byte length of the message body following the metadata (slot 3). */
    val bodyLength: Long get() = t.getLong(3)

    /** Convenience: header as ArrowSchema, or null if headerType != SCHEMA. */
    fun asSchema(): ArrowSchema? = if (headerType == MessageHeaderType.SCHEMA) {
        headerTable?.let { ArrowSchema(it) }
    } else null

    /** Convenience: header as ArrowRecordBatch, or null if headerType != RECORD_BATCH. */
    fun asRecordBatch(): ArrowRecordBatch? = if (headerType == MessageHeaderType.RECORD_BATCH) {
        headerTable?.let { ArrowRecordBatch(it) }
    } else null

    /** Convenience: header as ArrowDictionaryBatch, or null if headerType != DICTIONARY_BATCH. */
    fun asDictionaryBatch(): ArrowDictionaryBatch? = if (headerType == MessageHeaderType.DICTIONARY_BATCH) {
        headerTable?.let { ArrowDictionaryBatch(it) }
    } else null
}

/**
 * Accessor for the FlatBuffers `Schema` table (Schema.fbs).
 * Slots:
 *   0 = endianness (int16)
 *   1 = fields (vector<Field>)
 *   2 = custom_metadata (vector<KeyValue>)
 *   3 = features (vector<long>)
 */
class ArrowSchema(val t: FlatBufferTable) {
    /** Endianness enum (slot 0): 0 = Little, 1 = Big. */
    val endianness: Short get() = t.getShort(0)

    /** Vector of Field tables (slot 1). Null if absent (no fields). */
    val fieldsVector: FlatBufferVector? get() = t.getVector(1)

    /** Number of fields. */
    val fieldCount: Int get() = fieldsVector?.length ?: 0

    /** Read Field at position [i]. */
    fun field(i: Int): ArrowField = ArrowField(fieldsVector!!.table(i))
}

/**
 * Accessor for the FlatBuffers `Field` table (Schema.fbs).
 * Slots:
 *   0 = name (string)
 *   1 = nullable (bool)
 *   2 = type_type (uint8, union Type discriminant)
 *   3 = type (table offset, union Type value)
 *   4 = dictionary (DictionaryEncoding table)
 *   5 = children (vector<Field>)
 *   6 = custom_metadata (vector<KeyValue>)
 */
class ArrowField(val t: FlatBufferTable) {
    val name: String? get() = t.getString(0)
    val nullable: Boolean get() = t.getBool(1)

    /** Type union discriminant byte (slot 2). 1=Int, 2=FloatingPoint, 3=Binary, 4=Utf8, etc. */
    val typeTag: Byte get() = t.getByte(2)

    /** Type union table (slot 3). Null if absent (e.g. Null type has empty table). */
    val typeTable: FlatBufferTable? get() = t.getTable(3)

    /** Children vector (slot 5). Null for leaf fields. */
    val childrenVector: FlatBufferVector? get() = t.getVector(5)

    val childCount: Int get() = childrenVector?.length ?: 0

    fun child(i: Int): ArrowField = ArrowField(childrenVector!!.table(i))
}

// ---------------------------------------------------------------------------
// Type-specific FlatBuffers accessors (Schema.fbs type union tables)
// ---------------------------------------------------------------------------

/**
 * Accessor for the `Date` type table (Schema.fbs).
 * Slots:
 *   0 = unit (int16, DateUnit: 0=DAY, 1=MILLISECOND)
 */
class ArrowDateType(private val t: FlatBufferTable) {
    /** DateUnit: 0=DAY (int32, 4 bytes), 1=MILLISECOND (int64, 8 bytes). Default = MILLISECOND. */
    val unit: Short get() = t.getShort(0, default = 1)
}

/**
 * Accessor for the `Time` type table (Schema.fbs).
 * Slots:
 *   0 = unit (int16, TimeUnit: 0=SECOND, 1=MILLISECOND, 2=MICROSECOND, 3=NANOSECOND)
 *   1 = bitWidth (int32, 32 or 64)
 */
class ArrowTimeType(private val t: FlatBufferTable) {
    val unit: Short get() = t.getShort(0, default = 1)
    val bitWidth: Int get() = t.getInt(1, default = 32)
}

/**
 * Accessor for the `Timestamp` type table (Schema.fbs).
 * Slots:
 *   0 = unit (int16, TimeUnit)
 *   1 = timezone (string, optional)
 */
class ArrowTimestampType(private val t: FlatBufferTable) {
    val unit: Short get() = t.getShort(0)
    /** Timezone name, or null if no timezone (local/unzoned). */
    val timezone: String? get() = t.getString(1)
}

/**
 * Accessor for the `Duration` type table (Schema.fbs).
 * Slots:
 *   0 = unit (int16, TimeUnit)
 */
class ArrowDurationType(private val t: FlatBufferTable) {
    val unit: Short get() = t.getShort(0, default = 1)
}

/**
 * Accessor for the `Interval` type table (Schema.fbs).
 * Slots:
 *   0 = unit (int16, IntervalUnit: 0=YEAR_MONTH, 1=DAY_TIME, 2=MONTH_DAY_NANO)
 */
class ArrowIntervalType(private val t: FlatBufferTable) {
    /** IntervalUnit: 0=YEAR_MONTH (4B, int32 months), 1=DAY_TIME (8B), 2=MONTH_DAY_NANO (16B). */
    val unit: Short get() = t.getShort(0)
}

/**
 * Accessor for the `Decimal` type table (Schema.fbs).
 * Slots:
 *   0 = precision (int32)
 *   1 = scale (int32)
 *   2 = bitWidth (int32, default=128; 128 or 256)
 */
class ArrowDecimalType(private val t: FlatBufferTable) {
    val precision: Int get() = t.getInt(0)
    val scale: Int get() = t.getInt(1)
    val bitWidth: Int get() = t.getInt(2, default = 128)
}

// Extension helpers on ArrowField to read typed type tables:

/** Interpret this field's typeTable as an ArrowDateType, or null if absent. */
fun ArrowField.dateType(): ArrowDateType? = typeTable?.let { ArrowDateType(it) }

/** Interpret this field's typeTable as an ArrowTimeType, or null if absent. */
fun ArrowField.timeType(): ArrowTimeType? = typeTable?.let { ArrowTimeType(it) }

/** Interpret this field's typeTable as an ArrowTimestampType, or null if absent. */
fun ArrowField.timestampType(): ArrowTimestampType? = typeTable?.let { ArrowTimestampType(it) }

/** Interpret this field's typeTable as an ArrowDurationType, or null if absent. */
fun ArrowField.durationType(): ArrowDurationType? = typeTable?.let { ArrowDurationType(it) }

/** Interpret this field's typeTable as an ArrowIntervalType, or null if absent. */
fun ArrowField.intervalType(): ArrowIntervalType? = typeTable?.let { ArrowIntervalType(it) }

/** Interpret this field's typeTable as an ArrowDecimalType, or null if absent. */
fun ArrowField.decimalType(): ArrowDecimalType? = typeTable?.let { ArrowDecimalType(it) }

/**
 * Compression codec for Arrow IPC body buffers (BodyCompression.codec field).
 * Maps to [org.jetbrains.hexana.codec.Codec] for decompression.
 */
enum class ArrowBodyCodec(val tag: Byte) {
    LZ4_FRAME(0),
    ZSTD(1);

    companion object {
        fun fromTag(tag: Byte): ArrowBodyCodec? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * Accessor for the FlatBuffers `BodyCompression` table (Message.fbs).
 * Slots:
 *   0 = codec (uint8: 0=LZ4_FRAME, 1=ZSTD)
 *   1 = method (uint8: 0=BUFFER — each buffer prefixed with 8-byte LE uncompressed length)
 */
class ArrowBodyCompression(val t: FlatBufferTable) {
    val codec: ArrowBodyCodec? get() = ArrowBodyCodec.fromTag(t.getByte(0))
}

/**
 * Accessor for the FlatBuffers `RecordBatch` table (Message.fbs).
 * Slots:
 *   0 = length (int64)
 *   1 = nodes (vector<FieldNode struct>)
 *   2 = buffers (vector<Buffer struct>)
 *   3 = compression (BodyCompression table)
 *   4 = variadicBufferCounts (vector<long>)
 *
 * FieldNode struct: { length: long, null_count: long } = 16 bytes stride.
 * Buffer struct: { offset: long, length: long } = 16 bytes stride.
 */
class ArrowRecordBatch(val t: FlatBufferTable) {
    val length: Long get() = t.getLong(0)
    val nodesVector: FlatBufferVector? get() = t.getVector(1)
    val buffersVector: FlatBufferVector? get() = t.getVector(2)

    /** Non-null when the body is compressed; null in the common uncompressed case. */
    val bodyCompression: ArrowBodyCompression? get() = t.getTable(3)?.let { ArrowBodyCompression(it) }

    val nodeCount: Int get() = nodesVector?.length ?: 0
    val bufferCount: Int get() = buffersVector?.length ?: 0

    // FieldNode struct accessors (stride 16: length@0, null_count@8)
    fun nodeLength(i: Int): Long = t.buf.crossSectionOperation {
        t.buf.position(nodesVector!!.structPos(i, stride = 16))
        t.buf.getLongLE()
    }

    fun nodeNullCount(i: Int): Long = t.buf.crossSectionOperation {
        t.buf.position(nodesVector!!.structPos(i, stride = 16) + 8)
        t.buf.getLongLE()
    }

    // Buffer struct accessors (stride 16: offset@0, length@8)
    fun bufferOffset(i: Int): Long = t.buf.crossSectionOperation {
        t.buf.position(buffersVector!!.structPos(i, stride = 16))
        t.buf.getLongLE()
    }

    fun bufferLength(i: Int): Long = t.buf.crossSectionOperation {
        t.buf.position(buffersVector!!.structPos(i, stride = 16) + 8)
        t.buf.getLongLE()
    }
}

/**
 * Accessor for the FlatBuffers `DictionaryBatch` table (Message.fbs).
 * Slots:
 *   0 = id (int64)
 *   1 = data (RecordBatch table)
 *   2 = isDelta (bool)
 */
class ArrowDictionaryBatch(val t: FlatBufferTable) {
    val id: Long get() = t.getLong(0)
    val data: ArrowRecordBatch? get() = t.getTable(1)?.let { ArrowRecordBatch(it) }
    val isDelta: Boolean get() = t.getBool(2)
}

/**
 * Accessor for the FlatBuffers `Footer` table (File.fbs).
 * Slots:
 *   0 = version (int16)
 *   1 = schema (Schema table)
 *   2 = dictionaries (vector<Block struct>)
 *   3 = recordBatches (vector<Block struct>)
 *   4 = custom_metadata (vector<KeyValue>)
 *
 * Block struct: { offset: long (8B), metaDataLength: int (4B), _pad: 4B, bodyLength: long (8B) }
 *   = 24 bytes stride.
 */
class ArrowFooter(val t: FlatBufferTable) {
    val version: Short get() = t.getShort(0)
    val schema: ArrowSchema? get() = t.getTable(1)?.let { ArrowSchema(it) }
    val dictionariesVector: FlatBufferVector? get() = t.getVector(2)
    val recordBatchesVector: FlatBufferVector? get() = t.getVector(3)

    val dictionaryCount: Int get() = dictionariesVector?.length ?: 0
    val recordBatchBlockCount: Int get() = recordBatchesVector?.length ?: 0

    /** Block offset (absolute file offset to the encapsulated message). Stride 24, offset@0. */
    fun blockOffset(vec: FlatBufferVector, i: Int): Long = t.buf.crossSectionOperation {
        t.buf.position(vec.structPos(i, stride = 24))
        t.buf.getLongLE()
    }

    /** Block metaDataLength. Stride 24, metaDataLength@8. */
    fun blockMetaDataLength(vec: FlatBufferVector, i: Int): Int = t.buf.crossSectionOperation {
        t.buf.position(vec.structPos(i, stride = 24) + 8)
        t.buf.getIntLE()
    }

    /** Block bodyLength. Stride 24, bodyLength@16. */
    fun blockBodyLength(vec: FlatBufferVector, i: Int): Long = t.buf.crossSectionOperation {
        t.buf.position(vec.structPos(i, stride = 24) + 16)
        t.buf.getLongLE()
    }
}

// ---------------------------------------------------------------------------
// Encapsulated message framing
// ---------------------------------------------------------------------------

/** Result of reading one encapsulated Arrow IPC message from a buffer. */
data class ArrowEncapsulatedMessage(
    /** Decoded FlatBuffers Message header. */
    val message: ArrowMessage,
    /** Absolute start of the body in the source buffer (0 if bodyLength == 0). */
    val bodyStart: Int,
    /** Byte position immediately after this message (start of next message). */
    val nextMessageStart: Int,
)

/**
 * Read one encapsulated Arrow IPC message from [buf] at [pos].
 *
 * Format: [u32 continuation=0xFFFFFFFF][u32 metadata_len LE][metadata FlatBuffers][pad][body].
 * Returns null on EOS (metadata_len == 0 after the continuation marker).
 */
fun readEncapsulatedMessage(buf: CommonByteBuffer, pos: Int): ArrowEncapsulatedMessage? {
    require(pos + 8 <= buf.size) { "Buffer too small to read message header at pos=$pos" }
    val continuation = buf.crossSectionOperation {
        buf.position(pos)
        buf.getIntLE()
    }
    require(continuation == CONTINUATION_MARKER) {
        "Expected Arrow IPC continuation marker 0xFFFFFFFF at pos=$pos, got 0x${continuation.toUInt().toString(16)}"
    }
    val metadataLen = buf.crossSectionOperation {
        buf.position(pos + 4)
        buf.getIntLE()
    }
    if (metadataLen == EOS_METADATA_LEN) return null  // EOS marker

    val metadataStart = pos + 8
    val metadataEnd = metadataStart + metadataLen
    // Pad metadata + body start to 8-byte alignment
    val bodyStart = (metadataEnd + 7) and 7.inv()

    require(metadataEnd <= buf.size) { "Arrow IPC metadata extends past buffer: end=$metadataEnd, size=${buf.size}" }

    val metaSlice = buf.slice(metadataStart, metadataLen)
    val rootTable = FlatBuffers.root(metaSlice)
    val message = ArrowMessage(rootTable)

    val bodyEnd = bodyStart + message.bodyLength.toInt()
    return ArrowEncapsulatedMessage(
        message = message,
        bodyStart = if (message.bodyLength > 0) bodyStart else 0,
        nextMessageStart = bodyEnd,
    )
}

// ---------------------------------------------------------------------------
// Stream-format reader
// ---------------------------------------------------------------------------

/**
 * Reads the Schema message from an Arrow IPC stream in [buf].
 *
 * Stream format: sequence of encapsulated messages starting at offset 0.
 * The first message must be a Schema message.
 */
fun readArrowStreamSchema(buf: CommonByteBuffer): ArrowSchema {
    val msg = readEncapsulatedMessage(buf, 0)
        ?: error("Arrow IPC stream is empty (EOS at start)")
    return msg.message.asSchema()
        ?: error("First Arrow IPC stream message is not a Schema (got ${msg.message.headerType})")
}

/**
 * Reads all messages from an Arrow IPC stream in [buf].
 *
 * Returns the schema and the list of subsequent messages (RecordBatch, DictionaryBatch, etc.).
 */
data class ArrowStreamContents(
    val schema: ArrowSchema,
    val messages: List<ArrowEncapsulatedMessage>,
)

fun readArrowStream(buf: CommonByteBuffer): ArrowStreamContents {
    var pos = 0
    val first = readEncapsulatedMessage(buf, pos)
        ?: error("Arrow IPC stream is empty")
    val schema = first.message.asSchema()
        ?: error("First message is not Schema")
    pos = first.nextMessageStart
    val messages = mutableListOf<ArrowEncapsulatedMessage>()
    while (pos + 8 <= buf.size) {
        val msg = readEncapsulatedMessage(buf, pos) ?: break
        messages += msg
        pos = msg.nextMessageStart
    }
    return ArrowStreamContents(schema, messages)
}

// ---------------------------------------------------------------------------
// File-format reader
// ---------------------------------------------------------------------------

/**
 * Reads the Footer from an Arrow IPC file in [buf].
 *
 * File format:
 *   [ARROW1 (6B)][padding (2B)][messages...][EOS][Footer FlatBuffers][footer_len i32 LE][ARROW1 (6B)]
 */
fun readArrowFileFooter(buf: CommonByteBuffer): ArrowFooter {
    require(buf.size >= 12) { "Arrow file too small: ${buf.size}" }
    // Verify leading magic
    val leadingMagic = ByteArray(6) { buf[it] }
    require(leadingMagic.decodeToString() == MAGIC) {
        "Arrow file missing leading ARROW1 magic: ${leadingMagic.decodeToString()}"
    }
    // Verify trailing magic
    val trailingMagicStart = buf.size - 6
    val trailingMagic = ByteArray(6) { buf[trailingMagicStart + it] }
    require(trailingMagic.decodeToString() == MAGIC) {
        "Arrow file missing trailing ARROW1 magic"
    }
    // Footer length: 4 bytes immediately before the trailing magic
    val footerLenPos = buf.size - 10
    val footerLen = buf.crossSectionOperation {
        buf.position(footerLenPos)
        buf.getIntLE()
    }
    val footerStart = footerLenPos - footerLen
    require(footerStart >= 8) { "Arrow file footer start is before message area: $footerStart" }
    require(footerLen > 0) { "Arrow file footer length is 0" }

    val footerSlice = buf.slice(footerStart, footerLen)
    val rootTable = FlatBuffers.root(footerSlice)
    return ArrowFooter(rootTable)
}