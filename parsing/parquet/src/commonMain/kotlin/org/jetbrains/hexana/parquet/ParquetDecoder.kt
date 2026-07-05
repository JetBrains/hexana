/**
 * Hand-written Parquet metadata decoders over ThriftCompactReader.
 *
 * Field IDs verified against apache/parquet-format master parquet.thrift, 2026-07-03.
 * See ParquetTypes.kt for the full field-ID reference per struct.
 */
package org.jetbrains.hexana.parquet

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.crossSectionOperation
import org.jetbrains.hexana.thrift.ThriftCompactReader
import org.jetbrains.hexana.thrift.ThriftField
import org.jetbrains.hexana.thrift.ThriftType

// ---------------------------------------------------------------------------
// Parquet file magic
// ---------------------------------------------------------------------------

private val PAR1_MAGIC = byteArrayOf(0x50, 0x41, 0x52, 0x31)  // "PAR1"

// ---------------------------------------------------------------------------
// Top-level entry point: read FileMetaData from the file tail
// ---------------------------------------------------------------------------

/**
 * Decode [FileMetaData] from a Parquet file buffer.
 *
 * Layout (from the tail):
 *   - Last 4 bytes: "PAR1" magic.
 *   - Bytes [-8..-5] (i32 LE): footer length.
 *   - Bytes [-(8+footerLen)..-9]: FileMetaData Thrift-compact blob.
 *   - First 4 bytes: "PAR1" magic (verified).
 */
fun readParquetFileMetaData(buf: CommonByteBuffer): FileMetaData {
    val size = buf.size
    require(size >= 12) { "Parquet file too small: $size bytes" }

    // Verify leading PAR1
    for (i in 0 until 4) {
        require(buf[i] == PAR1_MAGIC[i]) {
            "Parquet file missing leading PAR1 magic at byte $i: got 0x${buf[i].toString(16)}"
        }
    }
    // Verify trailing PAR1
    for (i in 0 until 4) {
        require(buf[size - 4 + i] == PAR1_MAGIC[i]) {
            "Parquet file missing trailing PAR1 magic at byte ${size - 4 + i}"
        }
    }

    // Footer length: 4 bytes at [size-8 .. size-5], little-endian
    val footerLen = buf.crossSectionOperation {
        buf.position(size - 8)
        buf.getIntLE()
    }
    require(footerLen > 0) { "Parquet footer length is 0" }
    val footerStart = size - 8 - footerLen
    require(footerStart >= 4) { "Parquet footer extends past the leading magic: footerStart=$footerStart" }

    val footerSlice = buf.slice(footerStart, footerLen)
    val reader = ThriftCompactReader(footerSlice)
    return decodeFileMetaData(reader)
}

// ---------------------------------------------------------------------------
// Struct decoders (each: readStructBegin → loop readFieldBegin until STOP → readStructEnd)
// ---------------------------------------------------------------------------

private fun decodeFileMetaData(r: ThriftCompactReader): FileMetaData {
    // Field IDs: version=1, schema=2, num_rows=3, row_groups=4,
    //   key_value_metadata=5, created_by=6.
    var version = 0
    val schema = mutableListOf<SchemaElement>()
    var numRows = 0L
    val rowGroups = mutableListOf<RowGroup>()
    var createdBy: String? = null

    r.readStructBegin()
    while (true) {
        val f = r.readFieldBegin()
        if (f.type == ThriftType.STOP) break
        when (f.id) {
            1 -> version = r.readI32()
            2 -> schema += decodeList(r) { decodeSchemaElement(r) }
            3 -> numRows = r.readI64()
            4 -> rowGroups += decodeList(r) { decodeRowGroup(r) }
            5 -> decodeList(r) { r.readStructBegin(); skipStruct(r); r.readStructEnd() }  // key_value_metadata
            6 -> createdBy = r.readString()
            else -> r.skip(f.type)
        }
    }
    r.readStructEnd()
    return FileMetaData(version, schema, numRows, rowGroups, createdBy)
}

private fun decodeSchemaElement(r: ThriftCompactReader): SchemaElement {
    // Field IDs: type=1, type_length=2, repetition_type=3, name=4, num_children=5.
    var type: ParquetType? = null
    var typeLength: Int? = null
    var repetitionType: FieldRepetitionType? = null
    var name = ""
    var numChildren: Int? = null

    r.readStructBegin()
    while (true) {
        val f = r.readFieldBegin()
        if (f.type == ThriftType.STOP) break
        when (f.id) {
            1 -> type = ParquetType.fromId(r.readI32())
            2 -> typeLength = r.readI32()
            3 -> repetitionType = FieldRepetitionType.fromId(r.readI32())
            4 -> name = r.readString()
            5 -> numChildren = r.readI32()
            else -> r.skip(f.type)
        }
    }
    r.readStructEnd()
    return SchemaElement(type, typeLength, repetitionType, name, numChildren)
}

private fun decodeRowGroup(r: ThriftCompactReader): RowGroup {
    // Field IDs: columns=1, total_byte_size=2, num_rows=3.
    val columns = mutableListOf<ColumnChunk>()
    var totalByteSize = 0L
    var numRows = 0L

    r.readStructBegin()
    while (true) {
        val f = r.readFieldBegin()
        if (f.type == ThriftType.STOP) break
        when (f.id) {
            1 -> columns += decodeList(r) { decodeColumnChunk(r) }
            2 -> totalByteSize = r.readI64()
            3 -> numRows = r.readI64()
            else -> r.skip(f.type)
        }
    }
    r.readStructEnd()
    return RowGroup(columns, totalByteSize, numRows)
}

private fun decodeColumnChunk(r: ThriftCompactReader): ColumnChunk {
    // Field IDs: file_path=1, file_offset=2, meta_data=3.
    var filePath: String? = null
    var fileOffset = 0L
    var metaData: ColumnMetaData? = null

    r.readStructBegin()
    while (true) {
        val f = r.readFieldBegin()
        if (f.type == ThriftType.STOP) break
        when (f.id) {
            1 -> filePath = r.readString()
            2 -> fileOffset = r.readI64()
            3 -> metaData = decodeColumnMetaData(r)
            else -> r.skip(f.type)
        }
    }
    r.readStructEnd()
    return ColumnChunk(filePath, fileOffset, metaData)
}

private fun decodeColumnMetaData(r: ThriftCompactReader): ColumnMetaData {
    // Field IDs: type=1, encodings=2, path_in_schema=3, codec=4, num_values=5,
    //   total_uncompressed_size=6, total_compressed_size=7, data_page_offset=9,
    //   index_page_offset=10, dictionary_page_offset=11.
    var type = ParquetType.BOOLEAN
    val encodings = mutableListOf<ParquetEncoding>()
    val pathInSchema = mutableListOf<String>()
    var codec = CompressionCodec.UNCOMPRESSED
    var numValues = 0L
    var totalUncompressedSize = 0L
    var totalCompressedSize = 0L
    var dataPageOffset = 0L
    var indexPageOffset: Long? = null
    var dictionaryPageOffset: Long? = null

    r.readStructBegin()
    while (true) {
        val f = r.readFieldBegin()
        if (f.type == ThriftType.STOP) break
        when (f.id) {
            1 -> type = ParquetType.fromId(r.readI32())
            2 -> encodings += decodeList(r) { ParquetEncoding.fromId(r.readI32()) }
            3 -> pathInSchema += decodeList(r) { r.readString() }
            4 -> codec = CompressionCodec.fromId(r.readI32())
            5 -> numValues = r.readI64()
            6 -> totalUncompressedSize = r.readI64()
            7 -> totalCompressedSize = r.readI64()
            9 -> dataPageOffset = r.readI64()
            10 -> indexPageOffset = r.readI64()
            11 -> dictionaryPageOffset = r.readI64()
            else -> r.skip(f.type)
        }
    }
    r.readStructEnd()
    return ColumnMetaData(
        type, encodings, pathInSchema, codec, numValues,
        totalUncompressedSize, totalCompressedSize, dataPageOffset, indexPageOffset, dictionaryPageOffset,
    )
}

/**
 * Decode a PageHeader from a buffer positioned at the start of the Thrift-compact blob.
 * Field IDs: type=1, uncompressed_page_size=2, compressed_page_size=3, crc=4,
 *   data_page_header=5, index_page_header=6, dictionary_page_header=7, data_page_header_v2=8.
 */
fun decodePageHeader(r: ThriftCompactReader): PageHeader {
    var type = PageType.DATA_PAGE
    var uncompressedPageSize = 0
    var compressedPageSize = 0
    var crc: Int? = null
    var dataPageHeader: DataPageHeader? = null
    var dictionaryPageHeader: DictionaryPageHeader? = null
    var dataPageHeaderV2: DataPageHeaderV2? = null

    r.readStructBegin()
    while (true) {
        val f = r.readFieldBegin()
        if (f.type == ThriftType.STOP) break
        when (f.id) {
            1 -> type = PageType.fromId(r.readI32())
            2 -> uncompressedPageSize = r.readI32()
            3 -> compressedPageSize = r.readI32()
            4 -> crc = r.readI32()
            5 -> dataPageHeader = decodeDataPageHeader(r)
            6 -> r.skip(f.type)  // index_page_header: skip (IndexPageHeader not decoded)
            7 -> dictionaryPageHeader = decodeDictionaryPageHeader(r)
            8 -> dataPageHeaderV2 = decodeDataPageHeaderV2(r)
            else -> r.skip(f.type)
        }
    }
    r.readStructEnd()
    return PageHeader(type, uncompressedPageSize, compressedPageSize, crc,
        dataPageHeader, dictionaryPageHeader, dataPageHeaderV2)
}

private fun decodeDataPageHeader(r: ThriftCompactReader): DataPageHeader {
    // Field IDs: num_values=1, encoding=2, definition_level_encoding=3, repetition_level_encoding=4.
    var numValues = 0
    var encoding = ParquetEncoding.PLAIN
    var defLevelEncoding = ParquetEncoding.RLE
    var repLevelEncoding = ParquetEncoding.RLE

    r.readStructBegin()
    while (true) {
        val f = r.readFieldBegin()
        if (f.type == ThriftType.STOP) break
        when (f.id) {
            1 -> numValues = r.readI32()
            2 -> encoding = ParquetEncoding.fromId(r.readI32())
            3 -> defLevelEncoding = ParquetEncoding.fromId(r.readI32())
            4 -> repLevelEncoding = ParquetEncoding.fromId(r.readI32())
            else -> r.skip(f.type)
        }
    }
    r.readStructEnd()
    return DataPageHeader(numValues, encoding, defLevelEncoding, repLevelEncoding)
}

private fun decodeDictionaryPageHeader(r: ThriftCompactReader): DictionaryPageHeader {
    // Field IDs: num_values=1, encoding=2, is_sorted=3.
    var numValues = 0
    var encoding = ParquetEncoding.PLAIN_DICTIONARY
    var isSorted: Boolean? = null

    r.readStructBegin()
    while (true) {
        val f = r.readFieldBegin()
        if (f.type == ThriftType.STOP) break
        when (f.id) {
            1 -> numValues = r.readI32()
            2 -> encoding = ParquetEncoding.fromId(r.readI32())
            3 -> isSorted = r.readBool(f)
            else -> r.skip(f.type)
        }
    }
    r.readStructEnd()
    return DictionaryPageHeader(numValues, encoding, isSorted)
}

private fun decodeDataPageHeaderV2(r: ThriftCompactReader): DataPageHeaderV2 {
    // Field IDs: num_values=1, num_nulls=2, num_rows=3, encoding=4,
    //   definition_levels_byte_length=5, repetition_levels_byte_length=6, is_compressed=7.
    var numValues = 0
    var numNulls = 0
    var numRows = 0
    var encoding = ParquetEncoding.PLAIN
    var defLevelsLen = 0
    var repLevelsLen = 0
    var isCompressed = true  // default=true per parquet.thrift

    r.readStructBegin()
    while (true) {
        val f = r.readFieldBegin()
        if (f.type == ThriftType.STOP) break
        when (f.id) {
            1 -> numValues = r.readI32()
            2 -> numNulls = r.readI32()
            3 -> numRows = r.readI32()
            4 -> encoding = ParquetEncoding.fromId(r.readI32())
            5 -> defLevelsLen = r.readI32()
            6 -> repLevelsLen = r.readI32()
            7 -> isCompressed = r.readBool(f)
            else -> r.skip(f.type)
        }
    }
    r.readStructEnd()
    return DataPageHeaderV2(numValues, numNulls, numRows, encoding, defLevelsLen, repLevelsLen, isCompressed)
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun <T> decodeList(r: ThriftCompactReader, decodeItem: () -> T): List<T> {
    val container = r.readListBegin()
    return (0 until container.size).map { decodeItem() }
}

private fun skipStruct(r: ThriftCompactReader) {
    while (true) {
        val f = r.readFieldBegin()
        if (f.type == ThriftType.STOP) break
        r.skip(f.type)
    }
}